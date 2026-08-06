package com.example.kernelman.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.kernelman.profile.CpuProfileRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
  private companion object {
    const val tag = "BootCompletedReceiver"
    const val profileWorkName = "apply-profile-on-boot"
    const val swapWorkName = "disable-swap-on-boot"
    const val retryBackoffSeconds = 15L
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

    val pendingResult = goAsync()
    val applicationContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        scheduleBootApplyIfEnabled(applicationContext)
      } catch (throwable: Throwable) {
        Log.e(tag, "Failed to schedule boot apply", throwable)
      } finally {
        pendingResult.finish()
      }
    }
  }

  private suspend fun scheduleBootApplyIfEnabled(context: Context) {
    val state = CpuProfileRepository(context).state.first()
    if (!state.bootSettings.enabled && !state.swapSettings.disableAtBoot) return

    val workManager = WorkManager.getInstance(context)
    val delaySeconds = state.bootSettings.delaySeconds.toLong()

    if (state.bootSettings.enabled) {
      val request =
        OneTimeWorkRequestBuilder<ApplyBootProfileWorker>()
          .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
          .setBackoffCriteria(BackoffPolicy.LINEAR, retryBackoffSeconds, TimeUnit.SECONDS)
          .build()
      workManager.enqueueUniqueWork(profileWorkName, ExistingWorkPolicy.REPLACE, request)
    }

    if (state.swapSettings.disableAtBoot) {
      val request =
        OneTimeWorkRequestBuilder<ApplySwapOnBootWorker>()
          .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
          .setBackoffCriteria(BackoffPolicy.LINEAR, retryBackoffSeconds, TimeUnit.SECONDS)
          .build()
      workManager.enqueueUniqueWork(swapWorkName, ExistingWorkPolicy.REPLACE, request)
    }
  }
}

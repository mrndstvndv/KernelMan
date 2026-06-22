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
    const val workName = "apply-profile-on-boot"
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
    if (!state.bootSettings.enabled) return

    val request =
      OneTimeWorkRequestBuilder<ApplyBootProfileWorker>()
        .setInitialDelay(state.bootSettings.delaySeconds.toLong(), TimeUnit.SECONDS)
        .setBackoffCriteria(BackoffPolicy.LINEAR, retryBackoffSeconds, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
  }
}

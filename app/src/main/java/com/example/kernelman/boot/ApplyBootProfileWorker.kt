package com.example.kernelman.boot

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.kernelman.profile.CpuProfile
import com.example.kernelman.profile.CpuProfileRepository
import com.example.kernelman.profile.KernelProfileApplier
import com.example.kernelman.profile.ProfileBootApplyResult
import com.example.kernelman.profile.ProfileBootApplyStatus
import com.example.kernelman.profile.ResolvedBootProfile
import com.example.kernelman.profile.isRetryableBootApplyFailure
import com.example.kernelman.profile.resolveBootProfile
import com.example.kernelman.profile.toKernelProfileErrorMessage
import kotlinx.coroutines.flow.first

class ApplyBootProfileWorker(
  appContext: Context,
  workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
  private companion object {
    const val tag = "ApplyBootProfileWorker"
    const val maxAttempts = 5
  }

  private val profileRepository = CpuProfileRepository(appContext)
  private val profileApplier = KernelProfileApplier(profileRepository)

  override suspend fun doWork() =
    when (val resolution = resolveBootProfile(profileRepository.state.first())) {
      is ResolvedBootProfile.Profile -> applyProfile(resolution.profile)
      is ResolvedBootProfile.Skipped -> skip(resolution.message)
    }

  private suspend fun applyProfile(profile: CpuProfile): Result =
    runCatching {
      profileApplier.apply(profile)
    }.fold(
      onSuccess = {
        val message = "${profile.name} applied after boot."
        Log.i(tag, message)
        profileRepository.setBootApplyStatus(ProfileBootApplyStatus(System.currentTimeMillis(), ProfileBootApplyResult.SUCCESS, message))
        BootNotificationHelper.showNotification(
          applicationContext,
          title = "Kernel Profile Applied",
          message = message
        )
        Result.success()
      },
      onFailure = { throwable ->
        val message = throwable.toKernelProfileErrorMessage()
        val shouldRetry = throwable.isRetryableBootApplyFailure() && runAttemptCount < maxAttempts - 1
        if (shouldRetry) {
          val retryMessage = "Attempt ${runAttemptCount + 1} of $maxAttempts failed. Retrying. $message"
          Log.w(tag, retryMessage, throwable)
          profileRepository.setBootApplyStatus(ProfileBootApplyStatus(System.currentTimeMillis(), ProfileBootApplyResult.FAILED, retryMessage))
          return Result.retry()
        }

        Log.e(tag, "Boot profile apply failed: $message", throwable)
        profileRepository.setBootApplyStatus(ProfileBootApplyStatus(System.currentTimeMillis(), ProfileBootApplyResult.FAILED, message))
        BootNotificationHelper.showNotification(
          applicationContext,
          title = "Kernel Profile Apply Failed",
          message = message
        )
        Result.failure()
      },
    )

  private suspend fun skip(message: String): Result {
    Log.i(tag, message)
    profileRepository.setBootApplyStatus(ProfileBootApplyStatus(System.currentTimeMillis(), ProfileBootApplyResult.SKIPPED, message))
    return Result.success()
  }
}

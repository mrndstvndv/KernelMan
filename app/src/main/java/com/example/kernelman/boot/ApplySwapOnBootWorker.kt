package com.example.kernelman.boot

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.kernelman.profile.CpuProfileRepository
import com.example.kernelman.profile.isRetryableBootApplyFailure
import com.example.kernelman.profile.toKernelProfileErrorMessage
import com.example.kernelman.swap.SwapApi
import com.example.kernelman.swap.SwapApplyResult
import com.example.kernelman.swap.SwapApplyStatus
import kotlinx.coroutines.flow.first

class ApplySwapOnBootWorker(
  appContext: Context,
  workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
  private companion object {
    const val tag = "ApplySwapOnBootWorker"
    const val maxAttempts = 5
  }

  private val profileRepository = CpuProfileRepository(appContext)

  override suspend fun doWork(): Result {
    val state = profileRepository.state.first()
    if (!state.swapSettings.disableAtBoot) return Result.success()

    return runCatching { SwapApi.disableAll() }.fold(
      onSuccess = {
        val message = "Virtual RAM swap was disabled after boot."
        Log.i(tag, message)
        profileRepository.setSwapApplyStatus(
          SwapApplyStatus(
            lastAttemptAtEpochMs = System.currentTimeMillis(),
            lastResult = SwapApplyResult.SUCCESS,
            lastMessage = message,
          ),
        )
        Result.success()
      },
      onFailure = { throwable ->
        val message = throwable.toKernelProfileErrorMessage()
        val shouldRetry = throwable.isRetryableBootApplyFailure() && runAttemptCount < maxAttempts - 1
        if (shouldRetry) {
          val retryMessage = "Attempt ${runAttemptCount + 1} of $maxAttempts failed. Retrying. $message"
          Log.w(tag, retryMessage, throwable)
          profileRepository.setSwapApplyStatus(
            SwapApplyStatus(
              lastAttemptAtEpochMs = System.currentTimeMillis(),
              lastResult = SwapApplyResult.FAILED,
              lastMessage = retryMessage,
            ),
          )
          return@fold Result.retry()
        }

        Log.e(tag, "Boot swap disable failed: $message", throwable)
        profileRepository.setSwapApplyStatus(
          SwapApplyStatus(
            lastAttemptAtEpochMs = System.currentTimeMillis(),
            lastResult = SwapApplyResult.FAILED,
            lastMessage = message,
          ),
        )
        Result.failure()
      },
    )
  }
}

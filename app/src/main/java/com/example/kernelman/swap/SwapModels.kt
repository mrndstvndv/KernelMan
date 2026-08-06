package com.example.kernelman.swap

data class SwapDevice(
  val path: String,
  val type: String,
  val sizeKb: Long,
  val usedKb: Long,
  val priority: Int,
) {
  val isZram: Boolean
    get() = path.substringAfterLast('/').startsWith("zram")
}

data class SwapSnapshot(val devices: List<SwapDevice> = emptyList()) {
  val totalSizeKb: Long
    get() = devices.sumOf(SwapDevice::sizeKb)

  val totalUsedKb: Long
    get() = devices.sumOf(SwapDevice::usedKb)

  val hasActiveSwap: Boolean
    get() = devices.isNotEmpty()
}

data class SwapSettings(val disableAtBoot: Boolean = false)

enum class SwapApplyResult {
  SUCCESS,
  FAILED,
}

data class SwapApplyStatus(
  val lastAttemptAtEpochMs: Long? = null,
  val lastResult: SwapApplyResult? = null,
  val lastMessage: String? = null,
)

sealed interface SwapError {
  val summary: String

  data object RootUnavailable : SwapError {
    override val summary = "Virtual RAM controls require root."
  }

  data class RootCommandFailed(val command: String, val exitCode: Int, val stderr: String) : SwapError {
    override val summary = buildString {
      append("Swap command failed")
      if (stderr.isNotBlank()) append(": $stderr")
      append(" (exit=$exitCode)")
    }
  }

  data class ParseFailure(val line: String) : SwapError {
    override val summary = "Could not parse /proc/swaps."
  }

  data class VerificationFailed(val remainingDevices: List<SwapDevice>) : SwapError {
    override val summary = "The kernel still reports active swap after the change."
  }

  data object NoConfiguredZram : SwapError {
    override val summary = "No configured zRAM device is available to enable."
  }

  data class Unknown(val throwable: Throwable) : SwapError {
    override val summary = throwable.message ?: "Unknown virtual RAM error."
  }
}

class SwapException(val error: SwapError, cause: Throwable? = null) : Exception(error.summary, cause)

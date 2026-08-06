package com.example.kernelman.swap

import android.util.Log
import com.example.kernelman.cpu.CpuError
import com.example.kernelman.cpu.CpuException
import com.example.kernelman.cpu.RootShell
import com.example.kernelman.cpu.ShellResult

object SwapApi {
  private const val tag = "SwapApi"
  private const val noConfiguredZramExitCode = 18
  private const val disableCommand = """
    while read -r swapPath _; do
      [ "${'$'}swapPath" = "Filename" ] && continue
      [ -n "${'$'}swapPath" ] || continue
      swapoff "${'$'}swapPath" || exit ${'$'}?
    done < /proc/swaps
  """
  private const val enableConfiguredZramCommand = """
    configured=0
    for zramPath in /dev/block/zram*; do
      [ -e "${'$'}zramPath" ] || continue
      zramName="${'$'}{zramPath##*/}"
      disksize="${'$'}(cat "/sys/block/${'$'}zramName/disksize" 2>/dev/null || echo 0)"
      case "${'$'}disksize" in
        ''|*[!0-9]*) continue ;;
      esac
      [ "${'$'}disksize" -gt 0 ] || continue
      swapon "${'$'}zramPath" || exit ${'$'}?
      configured=1
    done
    [ "${'$'}configured" -eq 1 ] || exit 18
  """

  suspend fun readSnapshot(): SwapSnapshot =
    try {
      val result = runRoot("cat /proc/swaps")
      ensureSuccess(result)
      SwapSnapshot(parseSwapTable(result.stdout))
    } catch (exception: SwapException) {
      Log.e(tag, "readSnapshot() failed error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "readSnapshot() failed", throwable)
      throw SwapException(SwapError.Unknown(throwable), throwable)
    }

  suspend fun disableAll(): SwapSnapshot {
    val currentSnapshot = readSnapshot()
    if (!currentSnapshot.hasActiveSwap) return currentSnapshot

    try {
      val result = runRoot(disableCommand)
      ensureSuccess(result)
      val remainingSnapshot = readSnapshot()
      if (remainingSnapshot.hasActiveSwap) {
        throw SwapException(SwapError.VerificationFailed(remainingSnapshot.devices))
      }
      return remainingSnapshot
    } catch (exception: SwapException) {
      Log.e(tag, "disableAll() failed error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "disableAll() failed", throwable)
      throw SwapException(SwapError.Unknown(throwable), throwable)
    }
  }

  suspend fun enableConfiguredZram(): SwapSnapshot {
    val currentSnapshot = readSnapshot()
    if (currentSnapshot.devices.any(SwapDevice::isZram)) return currentSnapshot

    try {
      val result = runRoot(enableConfiguredZramCommand)
      if (result.exitCode == noConfiguredZramExitCode) {
        throw SwapException(SwapError.NoConfiguredZram)
      }
      ensureSuccess(result)

      val enabledSnapshot = readSnapshot()
      if (enabledSnapshot.devices.none(SwapDevice::isZram)) {
        throw SwapException(SwapError.VerificationFailed(enabledSnapshot.devices))
      }
      return enabledSnapshot
    } catch (exception: SwapException) {
      Log.e(tag, "enableConfiguredZram() failed error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "enableConfiguredZram() failed", throwable)
      throw SwapException(SwapError.Unknown(throwable), throwable)
    }
  }

  internal fun parseSwapTable(raw: String): List<SwapDevice> {
    val lines = raw.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    if (lines.isEmpty()) return emptyList()
    if (!lines.first().split(Regex("\\s+")).firstOrNull().equals("Filename")) {
      throw SwapException(SwapError.ParseFailure(lines.first()))
    }

    return lines.drop(1).map { line ->
      val fields = line.split(Regex("\\s+"))
      if (fields.size < 5) throw SwapException(SwapError.ParseFailure(line))

      val sizeKb = fields[2].toLongOrNull() ?: throw SwapException(SwapError.ParseFailure(line))
      val usedKb = fields[3].toLongOrNull() ?: throw SwapException(SwapError.ParseFailure(line))
      val priority = fields[4].toIntOrNull() ?: throw SwapException(SwapError.ParseFailure(line))
      SwapDevice(path = fields[0], type = fields[1], sizeKb = sizeKb, usedKb = usedKb, priority = priority)
    }
  }

  private suspend fun runRoot(command: String): ShellResult =
    try {
      RootShell.run(command.trimIndent())
    } catch (exception: CpuException) {
      when (exception.error) {
        CpuError.RootUnavailable -> throw SwapException(SwapError.RootUnavailable, exception)
        else -> throw SwapException(SwapError.Unknown(exception), exception)
      }
    }

  private fun ensureSuccess(result: ShellResult) {
    if (result.exitCode != 0) {
      throw SwapException(SwapError.RootCommandFailed(result.command, result.exitCode, result.stderr))
    }
  }
}

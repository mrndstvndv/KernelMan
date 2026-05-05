package com.example.kernelman.cpu

import android.util.Log

private const val CpuFreqRoot = "/sys/devices/system/cpu/cpufreq"

data class CpuPolicy(
  val name: String,
  val cpuInfoMinFreqKhz: Long,
  val cpuInfoMaxFreqKhz: Long,
  val scalingMinFreqKhz: Long,
  val scalingMaxFreqKhz: Long,
  val scalingCurFreqKhz: Long?,
  val governor: String,
  val availableFreqsKhz: List<Long>,
  val availableGovernors: List<String>,
)

sealed interface CpuError {
  val summary: String

  data object RootUnavailable : CpuError {
    override val summary = "Root shell is unavailable"
  }

  data class NoPoliciesFound(val rootPath: String = CpuFreqRoot) : CpuError {
    override val summary = "No CPU policies found under $rootPath"
  }

  data class MissingNode(val path: String) : CpuError {
    override val summary = "Missing kernel node: $path"
  }

  data class ParseFailure(val path: String, val raw: String) : CpuError {
    override val summary = "Could not parse value from $path"
  }

  data class RootCommandFailed(val command: String, val exitCode: Int, val stderr: String) : CpuError {
    override val summary = buildString {
      append("Root command failed")
      if (stderr.isNotBlank()) append(": $stderr")
      append(" (exit=$exitCode)")
    }
  }

  data class Validation(val reason: String) : CpuError {
    override val summary = reason
  }

  data class Unknown(val throwable: Throwable) : CpuError {
    override val summary = throwable.message ?: "Unknown CPU error"
  }
}

class CpuException(val error: CpuError, cause: Throwable? = null) : Exception(error.summary, cause)

object CpuPolicyApi {
  private const val tag = "CpuPolicyApi"

  suspend fun loadPolicies(): List<CpuPolicy> =
    try {
      Log.d(tag, "loadPolicies()")
      val policyNames = listPolicyNames()
      Log.d(tag, "loadPolicies() policyNames=$policyNames")
      buildList {
        for (policyName in policyNames) add(readPolicy(policyName))
      }
    } catch (exception: CpuException) {
      Log.e(tag, "loadPolicies() failed error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "loadPolicies() unknown failure", throwable)
      throw CpuException(CpuError.Unknown(throwable), throwable)
    }

  suspend fun readPolicy(policyName: String): CpuPolicy =
    try {
      Log.d(tag, "readPolicy() policy=$policyName")
      val policy =
        CpuPolicy(
          name = policyName,
          cpuInfoMinFreqKhz = readRequiredLong(policyName, "cpuinfo_min_freq"),
          cpuInfoMaxFreqKhz = readRequiredLong(policyName, "cpuinfo_max_freq"),
          scalingMinFreqKhz = readRequiredLong(policyName, "scaling_min_freq"),
          scalingMaxFreqKhz = readRequiredLong(policyName, "scaling_max_freq"),
          scalingCurFreqKhz = readOptionalLong(policyName, "scaling_cur_freq"),
          governor = readRequiredText(nodePath(policyName, "scaling_governor")),
          availableFreqsKhz = readAvailableFrequencies(policyName),
          availableGovernors = readAvailableGovernors(policyName),
        )
      Log.d(tag, "readPolicy() loaded=$policy")
      policy
    } catch (exception: CpuException) {
      Log.e(tag, "readPolicy() failed policy=$policyName error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "readPolicy() unknown failure policy=$policyName", throwable)
      throw CpuException(CpuError.Unknown(throwable), throwable)
    }

  suspend fun readCurrentFreq(policyName: String): Long? =
    try {
      val currentFreqKhz = readOptionalLong(policyName, "scaling_cur_freq")
      Log.d(tag, "readCurrentFreq() policy=$policyName currentFreqKhz=$currentFreqKhz")
      currentFreqKhz
    } catch (exception: CpuException) {
      Log.e(tag, "readCurrentFreq() failed policy=$policyName error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "readCurrentFreq() unknown failure policy=$policyName", throwable)
      throw CpuException(CpuError.Unknown(throwable), throwable)
    }

  suspend fun applyPolicy(policy: CpuPolicy, minFreqKhz: Long, maxFreqKhz: Long, governor: String) {
    try {
      Log.d(tag, "applyPolicy() policy=${policy.name} min=$minFreqKhz max=$maxFreqKhz governor=$governor")
      validate(policy, minFreqKhz, maxFreqKhz, governor)

      if (minFreqKhz > policy.scalingMaxFreqKhz) {
        Log.d(tag, "applyPolicy() write order=maxThenMin policy=${policy.name}")
        writeLong(policy.name, "scaling_max_freq", maxFreqKhz)
        writeLong(policy.name, "scaling_min_freq", minFreqKhz)
      } else {
        Log.d(tag, "applyPolicy() write order=minThenMax policy=${policy.name}")
        writeLong(policy.name, "scaling_min_freq", minFreqKhz)
        writeLong(policy.name, "scaling_max_freq", maxFreqKhz)
      }

      if (governor != policy.governor) {
        Log.d(tag, "applyPolicy() writing governor policy=${policy.name} governor=$governor")
        writeText(policy.name, "scaling_governor", governor)
      }
    } catch (exception: CpuException) {
      Log.e(tag, "applyPolicy() failed policy=${policy.name} error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "applyPolicy() unknown failure policy=${policy.name}", throwable)
      throw CpuException(CpuError.Unknown(throwable), throwable)
    }
  }

  private suspend fun listPolicyNames(): List<String> {
    val result = RootShell.run("for p in $CpuFreqRoot/policy*; do [ -d \"\$p\" ] && echo \"\$p\"; done")
    if (result.exitCode != 0) throw CpuException(CpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))

    val names =
      result.stdout
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { it.substringAfterLast('/') }
        .sortedBy { it.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
        .toList()

    if (names.isEmpty()) throw CpuException(CpuError.NoPoliciesFound())
    return names
  }

  private suspend fun readRequiredLong(policyName: String, nodeName: String): Long {
    val path = nodePath(policyName, nodeName)
    val raw = readRequiredText(path)
    return raw.toLongOrNull() ?: throw CpuException(CpuError.ParseFailure(path, raw))
  }

  private suspend fun readOptionalLong(policyName: String, nodeName: String): Long? {
    val path = nodePath(policyName, nodeName)
    val raw = readOptionalText(path) ?: return null
    return raw.toLongOrNull() ?: throw CpuException(CpuError.ParseFailure(path, raw))
  }

  private suspend fun readAvailableFrequencies(policyName: String): List<Long> {
    val path = nodePath(policyName, "scaling_available_frequencies")
    val raw = readOptionalText(path) ?: return emptyList()
    return raw
      .split(Regex("\\s+"))
      .filter(String::isNotBlank)
      .map { token -> token.toLongOrNull() ?: throw CpuException(CpuError.ParseFailure(path, raw)) }
      .distinct()
      .sorted()
  }

  private suspend fun readAvailableGovernors(policyName: String): List<String> {
    val path = nodePath(policyName, "scaling_available_governors")
    val raw = readOptionalText(path) ?: return emptyList()
    return raw.split(Regex("\\s+")).filter(String::isNotBlank).distinct().sorted()
  }

  private suspend fun readRequiredText(path: String): String {
    val result = RootShell.run("if [ -f \"$path\" ]; then cat \"$path\"; else exit 17; fi")
    if (result.exitCode == 17) throw CpuException(CpuError.MissingNode(path))
    if (result.exitCode != 0) throw CpuException(CpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))
    val raw = result.stdout.trim()
    if (raw.isBlank()) throw CpuException(CpuError.MissingNode(path))
    return raw
  }

  private suspend fun readOptionalText(path: String): String? {
    val result = RootShell.run("if [ -f \"$path\" ]; then cat \"$path\"; fi")
    if (result.exitCode != 0) throw CpuException(CpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))
    return result.stdout.trim().takeIf(String::isNotBlank)
  }

  private suspend fun writeLong(policyName: String, nodeName: String, value: Long) {
    val path = nodePath(policyName, nodeName)
    Log.d(tag, "writeLong() path=$path value=$value")
    val result = RootShell.run("echo $value > \"$path\"")
    if (result.exitCode != 0) throw CpuException(CpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))
  }

  private suspend fun writeText(policyName: String, nodeName: String, value: String) {
    val path = nodePath(policyName, nodeName)
    Log.d(tag, "writeText() path=$path value=$value")
    val result = RootShell.run("echo $value > \"$path\"")
    if (result.exitCode != 0) throw CpuException(CpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))
  }

  private fun validate(policy: CpuPolicy, minFreqKhz: Long, maxFreqKhz: Long, governor: String) {
    if (minFreqKhz > maxFreqKhz) {
      throw CpuException(CpuError.Validation("Min frequency must be less than or equal to max frequency"))
    }
    if (minFreqKhz < policy.cpuInfoMinFreqKhz) {
      throw CpuException(CpuError.Validation("Min frequency is below the supported policy minimum"))
    }
    if (maxFreqKhz > policy.cpuInfoMaxFreqKhz) {
      throw CpuException(CpuError.Validation("Max frequency is above the supported policy maximum"))
    }
    if (policy.availableFreqsKhz.isNotEmpty() && minFreqKhz !in policy.availableFreqsKhz) {
      throw CpuException(CpuError.Validation("Selected min frequency is not in the kernel scaling list"))
    }
    if (policy.availableFreqsKhz.isNotEmpty() && maxFreqKhz !in policy.availableFreqsKhz) {
      throw CpuException(CpuError.Validation("Selected max frequency is not in the kernel scaling list"))
    }
    if (governor.isBlank()) {
      throw CpuException(CpuError.Validation("Governor must not be blank"))
    }
    if (policy.availableGovernors.isNotEmpty() && governor !in policy.availableGovernors) {
      throw CpuException(CpuError.Validation("Selected governor is not in the kernel governor list"))
    }
  }

  private fun nodePath(policyName: String, nodeName: String) = "$CpuFreqRoot/$policyName/$nodeName"
}

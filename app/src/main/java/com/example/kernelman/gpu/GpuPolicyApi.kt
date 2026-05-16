package com.example.kernelman.gpu

import android.util.Log
import com.example.kernelman.cpu.RootShell

private const val GpuDevfreqRoot = "/sys/class/devfreq"
private const val KgslRoot = "/sys/class/kgsl/kgsl-3d0"

data class GpuPolicy(
  val name: String,
  val minFreqHz: Long,
  val maxFreqHz: Long,
  val curFreqHz: Long?,
  val governor: String?,
  val availableFreqsHz: List<Long>,
  val availableGovernors: List<String>,
  val minPowerLevel: Int?,
  val maxPowerLevel: Int?,
  val defaultPowerLevel: Int?,
  val numPowerLevels: Int?,
)

sealed interface GpuError {
  val summary: String

  data object RootUnavailable : GpuError {
    override val summary = "Root shell is unavailable"
  }

  data class NoPoliciesFound(val rootPath: String = GpuDevfreqRoot) : GpuError {
    override val summary = "No GPU devfreq policies found under $rootPath"
  }

  data class MissingNode(val path: String) : GpuError {
    override val summary = "Missing kernel node: $path"
  }

  data class ParseFailure(val path: String, val raw: String) : GpuError {
    override val summary = "Could not parse value from $path"
  }

  data class RootCommandFailed(val command: String, val exitCode: Int, val stderr: String) : GpuError {
    override val summary = buildString {
      append("Root command failed")
      if (stderr.isNotBlank()) append(": $stderr")
      append(" (exit=$exitCode)")
    }
  }

  data class Validation(val reason: String) : GpuError {
    override val summary = reason
  }

  data class Unknown(val throwable: Throwable) : GpuError {
    override val summary = throwable.message ?: "Unknown GPU error"
  }
}

class GpuException(val error: GpuError, cause: Throwable? = null) : Exception(error.summary, cause)

object GpuPolicyApi {
  private const val tag = "GpuPolicyApi"

  suspend fun loadPolicies(): List<GpuPolicy> =
    try {
      Log.d(tag, "loadPolicies()")
      val policyNames = listPolicyNames()
      buildList {
        for (policyName in policyNames) add(readPolicy(policyName))
      }
    } catch (exception: GpuException) {
      Log.e(tag, "loadPolicies() failed error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "loadPolicies() unknown failure", throwable)
      throw toGpuException(throwable)
    }

  suspend fun readPolicy(policyName: String): GpuPolicy =
    try {
      Log.d(tag, "readPolicy() policy=$policyName")
      GpuPolicy(
        name = policyName,
        minFreqHz = readRequiredLong(devfreqNodePath(policyName, "min_freq")),
        maxFreqHz = readRequiredLong(devfreqNodePath(policyName, "max_freq")),
        curFreqHz = readOptionalLong(devfreqNodePath(policyName, "cur_freq")),
        governor = readOptionalText(devfreqNodePath(policyName, "governor")),
        availableFreqsHz = readAvailableFrequencies(policyName),
        availableGovernors = readAvailableGovernors(policyName),
        minPowerLevel = readOptionalInt(kgslNodePath("min_pwrlevel")),
        maxPowerLevel = readOptionalInt(kgslNodePath("max_pwrlevel")),
        defaultPowerLevel = readOptionalInt(kgslNodePath("default_pwrlevel")),
        numPowerLevels = readOptionalInt(kgslNodePath("num_pwrlevels")),
      )
    } catch (exception: GpuException) {
      Log.e(tag, "readPolicy() failed policy=$policyName error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "readPolicy() unknown failure policy=$policyName", throwable)
      throw toGpuException(throwable)
    }

  suspend fun readCurrentFreq(policyName: String): Long? =
    try {
      readOptionalLong(devfreqNodePath(policyName, "cur_freq"))
    } catch (exception: GpuException) {
      Log.e(tag, "readCurrentFreq() failed policy=$policyName error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "readCurrentFreq() unknown failure policy=$policyName", throwable)
      throw toGpuException(throwable)
    }

  suspend fun applyPolicy(
    policy: GpuPolicy,
    minFreqHz: Long,
    maxFreqHz: Long,
    governor: String?,
    defaultPowerLevel: Int?,
  ) {
    try {
      Log.d(
        tag,
        "applyPolicy() policy=${policy.name} minFreq=$minFreqHz maxFreq=$maxFreqHz governor=$governor defaultPwr=$defaultPowerLevel",
      )
      validate(policy, minFreqHz, maxFreqHz, governor, defaultPowerLevel)
      applyFrequencies(policy, minFreqHz, maxFreqHz)
      applyGovernor(policy, governor)
      applyDefaultPowerLevel(policy, defaultPowerLevel)
    } catch (exception: GpuException) {
      Log.e(tag, "applyPolicy() failed policy=${policy.name} error=${exception.error.summary}", exception)
      throw exception
    } catch (throwable: Throwable) {
      Log.e(tag, "applyPolicy() unknown failure policy=${policy.name}", throwable)
      throw toGpuException(throwable)
    }
  }

  private suspend fun listPolicyNames(): List<String> {
    val command =
      "for p in $GpuDevfreqRoot/*; do [ -d \"\$p\" ] || continue; b=\"\${p##*/}\"; case \"\$b\" in *kgsl-3d0*|*gpu*) case \"\$b\" in *busmon*) ;; *) echo \"\$b\" ;; esac ;; esac; done"
    val result = RootShell.run(command)
    if (result.exitCode != 0) throw GpuException(GpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))

    val names = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()
    if (names.isEmpty()) throw GpuException(GpuError.NoPoliciesFound())
    return names
  }

  private suspend fun readAvailableFrequencies(policyName: String): List<Long> {
    val path = devfreqNodePath(policyName, "available_frequencies")
    val raw = readOptionalText(path) ?: return emptyList()
    return raw
      .split(Regex("\\s+"))
      .filter(String::isNotBlank)
      .map { token -> token.toLongOrNull() ?: throw GpuException(GpuError.ParseFailure(path, raw)) }
      .distinct()
      .sorted()
  }

  private suspend fun readAvailableGovernors(policyName: String): List<String> {
    val path = devfreqNodePath(policyName, "available_governors")
    val raw = readOptionalText(path) ?: return emptyList()
    return raw.split(Regex("\\s+")).filter(String::isNotBlank).distinct().sorted()
  }

  private suspend fun readRequiredLong(path: String): Long {
    val raw = readRequiredText(path)
    return raw.toLongOrNull() ?: throw GpuException(GpuError.ParseFailure(path, raw))
  }

  private suspend fun readOptionalLong(path: String): Long? {
    val raw = readOptionalText(path) ?: return null
    return raw.toLongOrNull() ?: throw GpuException(GpuError.ParseFailure(path, raw))
  }

  private suspend fun readOptionalInt(path: String): Int? {
    val raw = readOptionalText(path) ?: return null
    return raw.toIntOrNull() ?: throw GpuException(GpuError.ParseFailure(path, raw))
  }

  private suspend fun readRequiredText(path: String): String {
    val result = RootShell.run("if [ -f \"$path\" ]; then cat \"$path\"; else exit 17; fi")
    if (result.exitCode == 17) throw GpuException(GpuError.MissingNode(path))
    if (result.exitCode != 0) throw GpuException(GpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))
    return result.stdout.trim().takeIf(String::isNotBlank) ?: throw GpuException(GpuError.MissingNode(path))
  }

  private suspend fun readOptionalText(path: String): String? {
    val result = RootShell.run("if [ -f \"$path\" ]; then cat \"$path\"; fi")
    if (result.exitCode != 0) throw GpuException(GpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))
    return result.stdout.trim().takeIf(String::isNotBlank)
  }

  private suspend fun applyFrequencies(policy: GpuPolicy, minFreqHz: Long, maxFreqHz: Long) {
    val minChanged = minFreqHz != policy.minFreqHz
    val maxChanged = maxFreqHz != policy.maxFreqHz
    if (!minChanged && !maxChanged) return

    if (minChanged && maxChanged) {
      when {
        minFreqHz > policy.maxFreqHz -> {
          writeLong(devfreqNodePath(policy.name, "max_freq"), maxFreqHz)
          writeLong(devfreqNodePath(policy.name, "min_freq"), minFreqHz)
        }
        maxFreqHz < policy.minFreqHz -> {
          writeLong(devfreqNodePath(policy.name, "min_freq"), minFreqHz)
          writeLong(devfreqNodePath(policy.name, "max_freq"), maxFreqHz)
        }
        else -> {
          writeLong(devfreqNodePath(policy.name, "min_freq"), minFreqHz)
          writeLong(devfreqNodePath(policy.name, "max_freq"), maxFreqHz)
        }
      }
      return
    }

    if (minChanged) {
      writeLong(devfreqNodePath(policy.name, "min_freq"), minFreqHz)
      return
    }

    writeLong(devfreqNodePath(policy.name, "max_freq"), maxFreqHz)
  }

  private suspend fun applyGovernor(policy: GpuPolicy, governor: String?) {
    val currentGovernor = policy.governor
    if (currentGovernor == null || governor == null || governor == currentGovernor) return
    writeText(devfreqNodePath(policy.name, "governor"), governor)
  }

  private suspend fun applyDefaultPowerLevel(policy: GpuPolicy, defaultPowerLevel: Int?) {
    if (defaultPowerLevel == null || policy.defaultPowerLevel == null || defaultPowerLevel == policy.defaultPowerLevel) return
    writeInt(kgslNodePath("default_pwrlevel"), defaultPowerLevel)
  }

  private suspend fun writeLong(path: String, value: Long) {
    val result = RootShell.run("echo $value > \"$path\"")
    if (result.exitCode != 0) throw GpuException(GpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))
  }

  private suspend fun writeInt(path: String, value: Int) {
    val result = RootShell.run("echo $value > \"$path\"")
    if (result.exitCode != 0) throw GpuException(GpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))
  }

  private suspend fun writeText(path: String, value: String) {
    val result = RootShell.run("echo $value > \"$path\"")
    if (result.exitCode != 0) throw GpuException(GpuError.RootCommandFailed(result.command, result.exitCode, result.stderr))
  }

  private fun validate(
    policy: GpuPolicy,
    minFreqHz: Long,
    maxFreqHz: Long,
    governor: String?,
    defaultPowerLevel: Int?,
  ) {
    if (minFreqHz > maxFreqHz) throw GpuException(GpuError.Validation("GPU min frequency must be less than or equal to max frequency"))
    if (policy.availableFreqsHz.isNotEmpty() && minFreqHz !in policy.availableFreqsHz) {
      throw GpuException(GpuError.Validation("Selected GPU min frequency is not in the kernel frequency list"))
    }
    if (policy.availableFreqsHz.isNotEmpty() && maxFreqHz !in policy.availableFreqsHz) {
      throw GpuException(GpuError.Validation("Selected GPU max frequency is not in the kernel frequency list"))
    }
    if (!governor.isNullOrBlank() && policy.availableGovernors.isNotEmpty() && governor !in policy.availableGovernors) {
      throw GpuException(GpuError.Validation("Selected GPU governor is not in the kernel governor list"))
    }

    val numPowerLevels = policy.numPowerLevels
    if (numPowerLevels != null && defaultPowerLevel != null && (defaultPowerLevel < 0 || defaultPowerLevel >= numPowerLevels)) {
      throw GpuException(GpuError.Validation("GPU default power level must be between 0 and ${numPowerLevels - 1}"))
    }

    if (defaultPowerLevel != null && policy.maxPowerLevel != null && defaultPowerLevel < policy.maxPowerLevel) {
      throw GpuException(GpuError.Validation("GPU default power level must be within the kernel power-level window"))
    }
    if (defaultPowerLevel != null && policy.minPowerLevel != null && defaultPowerLevel > policy.minPowerLevel) {
      throw GpuException(GpuError.Validation("GPU default power level must be within the kernel power-level window"))
    }
  }

  private fun devfreqNodePath(policyName: String, nodeName: String) = "$GpuDevfreqRoot/$policyName/$nodeName"

  private fun kgslNodePath(nodeName: String) = "$KgslRoot/$nodeName"

  private fun toGpuException(throwable: Throwable) =
    when (throwable) {
      is GpuException -> throwable
      else -> GpuException(GpuError.Unknown(throwable), throwable)
    }
}

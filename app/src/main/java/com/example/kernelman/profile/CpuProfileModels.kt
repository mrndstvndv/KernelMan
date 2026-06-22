package com.example.kernelman.profile

import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.gpu.GpuPolicy
import com.example.kernelman.ui.screen.CpuPolicyDraft
import com.example.kernelman.ui.screen.GpuPolicyDraft
import kotlinx.serialization.Serializable

const val DefaultProfileBootDelaySeconds = 15
const val MaxProfileBootDelaySeconds = 300

@Serializable
data class CpuProfilesStore(val profiles: List<CpuProfile> = emptyList())

enum class ProfileBootMode {
  LAST_APPLIED,
  SPECIFIC_PROFILE,
}

data class ProfileBootSettings(
  val enabled: Boolean = false,
  val delaySeconds: Int = DefaultProfileBootDelaySeconds,
  val mode: ProfileBootMode = ProfileBootMode.LAST_APPLIED,
  val specificProfileId: String? = null,
)

enum class ProfileBootApplyResult {
  SUCCESS,
  FAILED,
  SKIPPED,
}

data class ProfileBootApplyStatus(
  val lastAttemptAtEpochMs: Long? = null,
  val lastResult: ProfileBootApplyResult? = null,
  val lastMessage: String? = null,
)

@Serializable
data class CpuProfile(
  val id: String,
  val name: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val policies: List<CpuProfilePolicy> = emptyList(),
  val gpuPolicies: List<GpuProfilePolicy> = emptyList(),
)

@Serializable
data class CpuProfilePolicy(
  val policyName: String,
  val minFreqKhz: Long,
  val maxFreqKhz: Long,
  val governor: String,
)

@Serializable
data class GpuProfilePolicy(
  val policyName: String,
  val minFreqHz: Long,
  val maxFreqHz: Long,
  val governor: String? = null,
  val defaultPowerLevel: Int? = null,
)

data class KernelProfileSnapshot(
  val cpuPolicies: List<CpuProfilePolicy> = emptyList(),
  val gpuPolicies: List<GpuProfilePolicy> = emptyList(),
)

data class CpuProfileState(
  val profiles: List<CpuProfile> = emptyList(),
  val lastAppliedProfileId: String? = null,
  val lastAppliedAtEpochMs: Long? = null,
  val bootSettings: ProfileBootSettings = ProfileBootSettings(),
  val bootApplyStatus: ProfileBootApplyStatus = ProfileBootApplyStatus(),
)

sealed interface ResolvedBootProfile {
  data class Profile(val profile: CpuProfile) : ResolvedBootProfile

  data class Skipped(val message: String) : ResolvedBootProfile
}

fun clampProfileBootDelaySeconds(delaySeconds: Int) = delaySeconds.coerceIn(0, MaxProfileBootDelaySeconds)

fun resolveBootProfile(state: CpuProfileState): ResolvedBootProfile {
  if (!state.bootSettings.enabled) return ResolvedBootProfile.Skipped("Boot apply is disabled.")

  return when (state.bootSettings.mode) {
    ProfileBootMode.LAST_APPLIED -> resolveLastAppliedBootProfile(state)
    ProfileBootMode.SPECIFIC_PROFILE -> resolveSpecificBootProfile(state)
  }
}

private fun resolveLastAppliedBootProfile(state: CpuProfileState): ResolvedBootProfile {
  val profileId = state.lastAppliedProfileId ?: return ResolvedBootProfile.Skipped("No last applied profile is available yet.")
  val profile = state.profiles.firstOrNull { it.id == profileId } ?: return ResolvedBootProfile.Skipped("The last applied profile no longer exists.")
  return ResolvedBootProfile.Profile(profile)
}

private fun resolveSpecificBootProfile(state: CpuProfileState): ResolvedBootProfile {
  val profileId = state.bootSettings.specificProfileId ?: return ResolvedBootProfile.Skipped("No boot profile is selected.")
  val profile = state.profiles.firstOrNull { it.id == profileId } ?: return ResolvedBootProfile.Skipped("The selected boot profile no longer exists.")
  return ResolvedBootProfile.Profile(profile)
}

fun buildProfileSnapshot(
  cpuPolicies: List<CpuPolicy>,
  cpuDrafts: Map<String, CpuPolicyDraft>,
  gpuPolicies: List<GpuPolicy>,
  gpuDrafts: Map<String, GpuPolicyDraft>,
): KernelProfileSnapshot {
  val savedCpuPolicies =
    cpuPolicies
      .sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
      .map { policy ->
        val draft = cpuDrafts[policy.name]
        CpuProfilePolicy(
          policyName = policy.name,
          minFreqKhz = draft?.minFreqKhz ?: policy.scalingMinFreqKhz,
          maxFreqKhz = draft?.maxFreqKhz ?: policy.scalingMaxFreqKhz,
          governor = draft?.governor ?: policy.governor,
        )
      }

  val savedGpuPolicies =
    gpuPolicies
      .sortedBy(GpuPolicy::name)
      .map { policy ->
        val draft = gpuDrafts[policy.name]
        GpuProfilePolicy(
          policyName = policy.name,
          minFreqHz = draft?.minFreqHz ?: policy.minFreqHz,
          maxFreqHz = draft?.maxFreqHz ?: policy.maxFreqHz,
          governor = draft?.governor ?: policy.governor,
          defaultPowerLevel = draft?.defaultPowerLevel ?: policy.defaultPowerLevel,
        )
      }

  if (savedCpuPolicies.isEmpty() && savedGpuPolicies.isEmpty()) {
    throw IllegalArgumentException("No CPU or GPU settings available to save in a profile")
  }

  return KernelProfileSnapshot(cpuPolicies = savedCpuPolicies, gpuPolicies = savedGpuPolicies)
}

fun findProfileCompatibilityIssue(profile: CpuProfile, cpuPolicies: List<CpuPolicy>, gpuPolicies: List<GpuPolicy>): String? {
  if (profile.policies.isEmpty() && profile.gpuPolicies.isEmpty()) {
    return "Profile does not contain any saved CPU or GPU settings."
  }

  val cpuPoliciesByName = cpuPolicies.associateBy(CpuPolicy::name)
  for (savedPolicy in profile.policies) {
    val currentPolicy = cpuPoliciesByName[savedPolicy.policyName] ?: return "CPU policy ${savedPolicy.policyName} is no longer available."
    if (savedPolicy.governor.isBlank()) return "Saved CPU governor is blank for ${savedPolicy.policyName}."

    if (savedPolicy.minFreqKhz > savedPolicy.maxFreqKhz) {
      return "Saved CPU min frequency is greater than max for ${savedPolicy.policyName}."
    }

    val minChanged = savedPolicy.minFreqKhz != currentPolicy.scalingMinFreqKhz
    val maxChanged = savedPolicy.maxFreqKhz != currentPolicy.scalingMaxFreqKhz
    val governorChanged = savedPolicy.governor != currentPolicy.governor

    if (governorChanged && currentPolicy.availableGovernors.isNotEmpty() && savedPolicy.governor !in currentPolicy.availableGovernors) {
      return "CPU governor \"${savedPolicy.governor}\" is no longer available for ${savedPolicy.policyName}."
    }

    if (minChanged && currentPolicy.availableFreqsKhz.isNotEmpty() && savedPolicy.minFreqKhz !in currentPolicy.availableFreqsKhz) {
      return "CPU min frequency ${savedPolicy.minFreqKhz} kHz is no longer available for ${savedPolicy.policyName}."
    }

    if (maxChanged && currentPolicy.availableFreqsKhz.isNotEmpty() && savedPolicy.maxFreqKhz !in currentPolicy.availableFreqsKhz) {
      return "CPU max frequency ${savedPolicy.maxFreqKhz} kHz is no longer available for ${savedPolicy.policyName}."
    }
  }

  val gpuPoliciesByName = gpuPolicies.associateBy(GpuPolicy::name)
  for (savedPolicy in profile.gpuPolicies) {
    val currentPolicy = gpuPoliciesByName[savedPolicy.policyName] ?: return "GPU policy ${savedPolicy.policyName} is no longer available."
    if (savedPolicy.governor?.isBlank() == true) return "Saved GPU governor is blank for ${savedPolicy.policyName}."

    if (savedPolicy.minFreqHz > savedPolicy.maxFreqHz) {
      return "Saved GPU min frequency is greater than max for ${savedPolicy.policyName}."
    }

    val minChanged = savedPolicy.minFreqHz != currentPolicy.minFreqHz
    val maxChanged = savedPolicy.maxFreqHz != currentPolicy.maxFreqHz
    val governorChanged = savedPolicy.governor != currentPolicy.governor

    if (governorChanged && !savedPolicy.governor.isNullOrBlank() && currentPolicy.availableGovernors.isNotEmpty() && savedPolicy.governor !in currentPolicy.availableGovernors) {
      return "GPU governor \"${savedPolicy.governor}\" is no longer available for ${savedPolicy.policyName}."
    }

    if (minChanged && currentPolicy.availableFreqsHz.isNotEmpty() && savedPolicy.minFreqHz !in currentPolicy.availableFreqsHz) {
      return "GPU min frequency ${savedPolicy.minFreqHz} Hz is no longer available for ${savedPolicy.policyName}."
    }

    if (maxChanged && currentPolicy.availableFreqsHz.isNotEmpty() && savedPolicy.maxFreqHz !in currentPolicy.availableFreqsHz) {
      return "GPU max frequency ${savedPolicy.maxFreqHz} Hz is no longer available for ${savedPolicy.policyName}."
    }

    val defaultPowerLevel = savedPolicy.defaultPowerLevel
    val defaultPowerLevelChanged = defaultPowerLevel != currentPolicy.defaultPowerLevel
    if (!defaultPowerLevelChanged || defaultPowerLevel == null) continue

    val numPowerLevels = currentPolicy.numPowerLevels
    if (numPowerLevels != null && (defaultPowerLevel < 0 || defaultPowerLevel >= numPowerLevels)) {
      return "GPU default power level ${savedPolicy.defaultPowerLevel} is no longer available for ${savedPolicy.policyName}."
    }

    if (currentPolicy.defaultPowerLevel == null) {
      return "GPU default power level control is no longer available for ${savedPolicy.policyName}."
    }

    if (currentPolicy.maxPowerLevel != null && defaultPowerLevel < currentPolicy.maxPowerLevel) {
      return "GPU default power level is outside the kernel power window for ${savedPolicy.policyName}."
    }

    if (currentPolicy.minPowerLevel != null && defaultPowerLevel > currentPolicy.minPowerLevel) {
      return "GPU default power level is outside the kernel power window for ${savedPolicy.policyName}."
    }
  }

  return null
}

package com.example.kernelman.profile

import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.gpu.GpuPolicy
import com.example.kernelman.ui.screen.CpuPolicyDraft
import com.example.kernelman.ui.screen.GpuPolicyDraft
import kotlinx.serialization.Serializable

@Serializable
data class CpuProfilesStore(val profiles: List<CpuProfile> = emptyList())

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
)

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

    if (savedPolicy.minFreqKhz > savedPolicy.maxFreqKhz) {
      return "Saved CPU min frequency is greater than max for ${savedPolicy.policyName}."
    }

    if (currentPolicy.availableGovernors.isNotEmpty() && savedPolicy.governor !in currentPolicy.availableGovernors) {
      return "CPU governor \"${savedPolicy.governor}\" is no longer available for ${savedPolicy.policyName}."
    }

    if (currentPolicy.availableFreqsKhz.isNotEmpty() && savedPolicy.minFreqKhz !in currentPolicy.availableFreqsKhz) {
      return "CPU min frequency ${savedPolicy.minFreqKhz} kHz is no longer available for ${savedPolicy.policyName}."
    }

    if (currentPolicy.availableFreqsKhz.isNotEmpty() && savedPolicy.maxFreqKhz !in currentPolicy.availableFreqsKhz) {
      return "CPU max frequency ${savedPolicy.maxFreqKhz} kHz is no longer available for ${savedPolicy.policyName}."
    }
  }

  val gpuPoliciesByName = gpuPolicies.associateBy(GpuPolicy::name)
  for (savedPolicy in profile.gpuPolicies) {
    val currentPolicy = gpuPoliciesByName[savedPolicy.policyName] ?: return "GPU policy ${savedPolicy.policyName} is no longer available."

    if (savedPolicy.minFreqHz > savedPolicy.maxFreqHz) {
      return "Saved GPU min frequency is greater than max for ${savedPolicy.policyName}."
    }

    if (!savedPolicy.governor.isNullOrBlank() && currentPolicy.availableGovernors.isNotEmpty() && savedPolicy.governor !in currentPolicy.availableGovernors) {
      return "GPU governor \"${savedPolicy.governor}\" is no longer available for ${savedPolicy.policyName}."
    }

    if (currentPolicy.availableFreqsHz.isNotEmpty() && savedPolicy.minFreqHz !in currentPolicy.availableFreqsHz) {
      return "GPU min frequency ${savedPolicy.minFreqHz} Hz is no longer available for ${savedPolicy.policyName}."
    }

    if (currentPolicy.availableFreqsHz.isNotEmpty() && savedPolicy.maxFreqHz !in currentPolicy.availableFreqsHz) {
      return "GPU max frequency ${savedPolicy.maxFreqHz} Hz is no longer available for ${savedPolicy.policyName}."
    }

    val defaultPowerLevel = savedPolicy.defaultPowerLevel
    val numPowerLevels = currentPolicy.numPowerLevels
    if (numPowerLevels != null && defaultPowerLevel != null && (defaultPowerLevel < 0 || defaultPowerLevel >= numPowerLevels)) {
      return "GPU default power level ${savedPolicy.defaultPowerLevel} is no longer available for ${savedPolicy.policyName}."
    }

    if (defaultPowerLevel != null && currentPolicy.defaultPowerLevel == null) {
      return "GPU default power level control is no longer available for ${savedPolicy.policyName}."
    }

    if (defaultPowerLevel != null && currentPolicy.maxPowerLevel != null && defaultPowerLevel < currentPolicy.maxPowerLevel) {
      return "GPU default power level is outside the kernel power window for ${savedPolicy.policyName}."
    }

    if (defaultPowerLevel != null && currentPolicy.minPowerLevel != null && defaultPowerLevel > currentPolicy.minPowerLevel) {
      return "GPU default power level is outside the kernel power window for ${savedPolicy.policyName}."
    }
  }

  return null
}

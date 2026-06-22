package com.example.kernelman.profile

import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.cpu.CpuPolicyApi
import com.example.kernelman.gpu.GpuPolicy
import com.example.kernelman.gpu.GpuPolicyApi

class KernelProfileApplier(private val profileRepository: CpuProfileRepository) {
  suspend fun apply(profile: CpuProfile) {
    val currentCpuPolicies = if (profile.policies.isNotEmpty()) CpuPolicyApi.loadPolicies() else emptyList()
    val currentGpuPolicies = if (profile.gpuPolicies.isNotEmpty()) GpuPolicyApi.loadPolicies() else emptyList()
    val compatibilityIssue = findProfileCompatibilityIssue(profile, currentCpuPolicies, currentGpuPolicies)
    if (compatibilityIssue != null) throw IllegalArgumentException(compatibilityIssue)

    applyCpuPolicies(profile, currentCpuPolicies)
    applyGpuPolicies(profile, currentGpuPolicies)
    profileRepository.setLastApplied(profile.id, System.currentTimeMillis())
  }

  private suspend fun applyCpuPolicies(profile: CpuProfile, currentCpuPolicies: List<CpuPolicy>) {
    for (savedPolicy in profile.policies) {
      val currentPolicy =
        currentCpuPolicies.firstOrNull { it.name == savedPolicy.policyName }
          ?: throw IllegalArgumentException("CPU policy ${savedPolicy.policyName} is no longer available.")

      CpuPolicyApi.applyPolicy(currentPolicy, savedPolicy.minFreqKhz, savedPolicy.maxFreqKhz, savedPolicy.governor)
    }
  }

  private suspend fun applyGpuPolicies(profile: CpuProfile, currentGpuPolicies: List<GpuPolicy>) {
    for (savedPolicy in profile.gpuPolicies) {
      val currentPolicy =
        currentGpuPolicies.firstOrNull { it.name == savedPolicy.policyName }
          ?: throw IllegalArgumentException("GPU policy ${savedPolicy.policyName} is no longer available.")

      GpuPolicyApi.applyPolicy(
        policy = currentPolicy,
        minFreqHz = savedPolicy.minFreqHz,
        maxFreqHz = savedPolicy.maxFreqHz,
        governor = savedPolicy.governor,
        defaultPowerLevel = savedPolicy.defaultPowerLevel,
      )
    }
  }
}

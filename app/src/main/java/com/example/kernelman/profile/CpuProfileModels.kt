package com.example.kernelman.profile

import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.ui.screen.CpuPolicyDraft
import kotlinx.serialization.Serializable

@Serializable
data class CpuProfilesStore(val profiles: List<CpuProfile> = emptyList())

@Serializable
data class CpuProfile(
  val id: String,
  val name: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val policies: List<CpuProfilePolicy>,
)

@Serializable
data class CpuProfilePolicy(
  val policyName: String,
  val minFreqKhz: Long,
  val maxFreqKhz: Long,
  val governor: String,
)

data class CpuProfileState(
  val profiles: List<CpuProfile> = emptyList(),
  val lastAppliedProfileId: String? = null,
  val lastAppliedAtEpochMs: Long? = null,
)

fun buildProfileSnapshot(policies: List<CpuPolicy>, drafts: Map<String, CpuPolicyDraft>): List<CpuProfilePolicy> {
  if (policies.isEmpty()) throw IllegalArgumentException("No CPU policies available to save in a profile")

  return policies
    .sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
    .map { policy ->
      val draft = drafts[policy.name]
      CpuProfilePolicy(
        policyName = policy.name,
        minFreqKhz = draft?.minFreqKhz ?: policy.scalingMinFreqKhz,
        maxFreqKhz = draft?.maxFreqKhz ?: policy.scalingMaxFreqKhz,
        governor = draft?.governor ?: policy.governor,
      )
    }
}

fun findProfileCompatibilityIssue(profile: CpuProfile, policies: List<CpuPolicy>): String? {
  if (profile.policies.isEmpty()) return "Profile does not contain any saved CPU policies."

  val policiesByName = policies.associateBy(CpuPolicy::name)
  for (savedPolicy in profile.policies) {
    val currentPolicy = policiesByName[savedPolicy.policyName] ?: return "Policy ${savedPolicy.policyName} is no longer available."

    if (savedPolicy.minFreqKhz > savedPolicy.maxFreqKhz) {
      return "Saved min frequency is greater than max for ${savedPolicy.policyName}."
    }

    if (currentPolicy.availableGovernors.isNotEmpty() && savedPolicy.governor !in currentPolicy.availableGovernors) {
      return "Governor \"${savedPolicy.governor}\" is no longer available for ${savedPolicy.policyName}."
    }

    if (currentPolicy.availableFreqsKhz.isNotEmpty() && savedPolicy.minFreqKhz !in currentPolicy.availableFreqsKhz) {
      return "Min frequency ${savedPolicy.minFreqKhz} kHz is no longer available for ${savedPolicy.policyName}."
    }

    if (currentPolicy.availableFreqsKhz.isNotEmpty() && savedPolicy.maxFreqKhz !in currentPolicy.availableFreqsKhz) {
      return "Max frequency ${savedPolicy.maxFreqKhz} kHz is no longer available for ${savedPolicy.policyName}."
    }
  }

  return null
}

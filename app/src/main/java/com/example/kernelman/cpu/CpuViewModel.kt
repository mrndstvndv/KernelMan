package com.example.kernelman.cpu

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CpuPolicyDraft(val minFreqKhz: Long, val maxFreqKhz: Long, val governor: String)

data class CpuScreenState(
  val isLoading: Boolean = true,
  val policies: List<CpuPolicy> = emptyList(),
  val currentFreqsKhz: Map<String, Long?> = emptyMap(),
  val drafts: Map<String, CpuPolicyDraft> = emptyMap(),
  val savingPolicyName: String? = null,
  val error: CpuError? = null,
)

class CpuViewModel : ViewModel() {
  private companion object {
    const val tag = "CpuViewModel"
    const val refreshIntervalMs = 1000L
  }

  private val mutableUiState = MutableStateFlow(CpuScreenState())
  val uiState: StateFlow<CpuScreenState> = mutableUiState.asStateFlow()

  init {
    Log.d(tag, "init")
    viewModelScope.launch {
      refreshPolicies()
      while (isActive) {
        delay(refreshIntervalMs)
        if (uiState.value.savingPolicyName != null) continue
        refreshCurrentFrequencies()
      }
    }
  }

  fun updateMin(policyName: String, minFreqKhz: Long) = updateDraft(policyName) { copy(minFreqKhz = minFreqKhz) }

  fun updateMax(policyName: String, maxFreqKhz: Long) = updateDraft(policyName) { copy(maxFreqKhz = maxFreqKhz) }

  fun updateGovernor(policyName: String, governor: String) = updateDraft(policyName) { copy(governor = governor) }

  fun savePolicy(policyName: String) {
    val policy = uiState.value.policies.firstOrNull { it.name == policyName }
    if (policy == null) {
      Log.w(tag, "savePolicy() missing policy=$policyName")
      return
    }

    val draft = uiState.value.drafts[policyName] ?: CpuPolicyDraft(policy.scalingMinFreqKhz, policy.scalingMaxFreqKhz, policy.governor)
    Log.d(tag, "savePolicy() policy=$policyName draft=$draft")

    viewModelScope.launch {
      mutableUiState.update { it.copy(savingPolicyName = policyName, error = null) }
      try {
        CpuPolicyApi.applyPolicy(policy, draft.minFreqKhz, draft.maxFreqKhz, draft.governor)
        mutableUiState.update { state -> state.copy(savingPolicyName = null, drafts = state.drafts - policyName, error = null) }
        refreshPolicies()
      } catch (throwable: Throwable) {
        val error = toCpuError(throwable)
        Log.e(tag, "savePolicy() failed policy=$policyName error=${error.summary}", throwable)
        mutableUiState.update { it.copy(savingPolicyName = null, error = error) }
      }
    }
  }

  private fun updateDraft(policyName: String, transform: CpuPolicyDraft.() -> CpuPolicyDraft) {
    val policy = uiState.value.policies.firstOrNull { it.name == policyName }
    if (policy == null) {
      Log.w(tag, "updateDraft() missing policy=$policyName")
      return
    }

    mutableUiState.update { state ->
      val currentDraft = state.drafts[policyName] ?: CpuPolicyDraft(policy.scalingMinFreqKhz, policy.scalingMaxFreqKhz, policy.governor)
      val updatedDraft = currentDraft.transform()
      Log.d(tag, "updateDraft() policy=$policyName draft=$updatedDraft")

      val drafts =
        if (
          updatedDraft.minFreqKhz == policy.scalingMinFreqKhz &&
            updatedDraft.maxFreqKhz == policy.scalingMaxFreqKhz &&
            updatedDraft.governor == policy.governor
        ) {
          state.drafts - policyName
        } else {
          state.drafts + (policyName to updatedDraft)
        }

      state.copy(drafts = drafts, error = null)
    }
  }

  private suspend fun refreshPolicies() {
    Log.d(tag, "refreshPolicies()")
    try {
      val policies = CpuPolicyApi.loadPolicies()
      Log.d(tag, "refreshPolicies() success count=${policies.size}")
      mutableUiState.update { state ->
        state.copy(
          isLoading = false,
          policies = policies,
          currentFreqsKhz = policies.associate { it.name to it.scalingCurFreqKhz },
          drafts = syncDrafts(state.drafts, policies),
          error = null,
        )
      }
    } catch (throwable: Throwable) {
      val error = toCpuError(throwable)
      Log.e(tag, "refreshPolicies() failed error=${error.summary}", throwable)
      mutableUiState.update { state -> state.copy(isLoading = false, error = error) }
    }
  }

  private suspend fun refreshCurrentFrequencies() {
    val policyNames = uiState.value.policies.map(CpuPolicy::name)
    if (policyNames.isEmpty()) return

    Log.d(tag, "refreshCurrentFrequencies() policyNames=$policyNames")
    try {
      val currentFreqs = buildMap {
        for (policyName in policyNames) put(policyName, CpuPolicyApi.readCurrentFreq(policyName))
      }
      mutableUiState.update { state -> state.copy(currentFreqsKhz = state.currentFreqsKhz + currentFreqs, error = null) }
    } catch (throwable: Throwable) {
      val error = toCpuError(throwable)
      Log.e(tag, "refreshCurrentFrequencies() failed error=${error.summary}", throwable)
      mutableUiState.update { state -> state.copy(error = error) }
    }
  }

  private fun syncDrafts(drafts: Map<String, CpuPolicyDraft>, policies: List<CpuPolicy>): Map<String, CpuPolicyDraft> {
    val policiesByName = policies.associateBy(CpuPolicy::name)
    return drafts.filter { (name, draft) ->
      val policy = policiesByName[name] ?: return@filter false
      draft.minFreqKhz != policy.scalingMinFreqKhz || draft.maxFreqKhz != policy.scalingMaxFreqKhz || draft.governor != policy.governor
    }
  }

  private fun toCpuError(throwable: Throwable) = (throwable as? CpuException)?.error ?: CpuError.Unknown(throwable)
}

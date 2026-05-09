package com.example.kernelman.ui.screen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kernelman.cpu.CpuError
import com.example.kernelman.cpu.CpuException
import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.cpu.CpuPolicyApi
import com.example.kernelman.profile.CpuProfile
import com.example.kernelman.profile.CpuProfilePolicy
import com.example.kernelman.profile.CpuProfileRepository
import com.example.kernelman.profile.buildProfileSnapshot
import com.example.kernelman.profile.findProfileCompatibilityIssue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CpuPolicyDraft(val minFreqKhz: Long, val maxFreqKhz: Long, val governor: String)

sealed interface ProfileDialogState {
  data class Create(val name: String = "") : ProfileDialogState

  data class Rename(val profileId: String, val name: String) : ProfileDialogState

  data class Update(val profileId: String) : ProfileDialogState

  data class Delete(val profileId: String) : ProfileDialogState

  data class Apply(val profileId: String) : ProfileDialogState
}

sealed interface ProfileAction {
  data object Creating : ProfileAction

  data class Renaming(val profileId: String) : ProfileAction

  data class Updating(val profileId: String) : ProfileAction

  data class Deleting(val profileId: String) : ProfileAction

  data class Applying(val profileId: String) : ProfileAction
}

data class CpuScreenState(
  val isLoading: Boolean = true,
  val policies: List<CpuPolicy> = emptyList(),
  val currentFreqsKhz: Map<String, Long?> = emptyMap(),
  val drafts: Map<String, CpuPolicyDraft> = emptyMap(),
  val savingPolicyName: String? = null,
  val profiles: List<CpuProfile> = emptyList(),
  val lastAppliedProfileId: String? = null,
  val isProfilesSheetVisible: Boolean = false,
  val profileDialogState: ProfileDialogState? = null,
  val profileActionInFlight: ProfileAction? = null,
  val error: CpuError? = null,
)

class CpuViewModel(application: Application) : AndroidViewModel(application) {
  private companion object {
    const val tag = "CpuViewModel"
    const val refreshIntervalMs = 1000L
  }

  private val profileRepository = CpuProfileRepository(application.applicationContext)
  private val mutableUiState = MutableStateFlow(CpuScreenState())
  val uiState: StateFlow<CpuScreenState> = mutableUiState.asStateFlow()

  private val mutableSnackbarMessages = MutableSharedFlow<String>()
  val snackbarMessages: SharedFlow<String> = mutableSnackbarMessages.asSharedFlow()

  init {
    Log.d(tag, "init")
    observeProfiles()
    viewModelScope.launch {
      refreshPolicies()
      while (isActive) {
        delay(refreshIntervalMs)
        if (uiState.value.savingPolicyName != null || uiState.value.profileActionInFlight != null) continue
        refreshCurrentFrequencies()
      }
    }
  }

  fun showProfilesSheet() {
    mutableUiState.update { it.copy(isProfilesSheetVisible = true, error = null) }
  }

  fun hideProfilesSheet() {
    if (uiState.value.profileActionInFlight != null) return
    mutableUiState.update { it.copy(isProfilesSheetVisible = false) }
  }

  fun showCreateProfileDialog() {
    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Create(), error = null) }
  }

  fun showRenameProfileDialog(profileId: String) {
    val profile = findProfile(profileId) ?: return
    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Rename(profileId, profile.name), error = null) }
  }

  fun showUpdateProfileDialog(profileId: String) {
    if (findProfile(profileId) == null) return
    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Update(profileId), error = null) }
  }

  fun showDeleteProfileDialog(profileId: String) {
    if (findProfile(profileId) == null) return
    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Delete(profileId), error = null) }
  }

  fun promptApplyProfile(profileId: String) {
    if (findProfile(profileId) == null) return
    if (uiState.value.drafts.isEmpty()) {
      applyProfile(profileId)
      return
    }

    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Apply(profileId), error = null) }
  }

  fun dismissProfileDialog() {
    if (uiState.value.profileActionInFlight != null) return
    mutableUiState.update { it.copy(profileDialogState = null) }
  }

  fun updateProfileDialogName(name: String) {
    mutableUiState.update { state ->
      val dialogState =
        when (val currentDialogState = state.profileDialogState) {
          is ProfileDialogState.Create -> currentDialogState.copy(name = name)
          is ProfileDialogState.Rename -> currentDialogState.copy(name = name)
          else -> return@update state
        }

      state.copy(profileDialogState = dialogState, error = null)
    }
  }

  fun createProfile() {
    val dialogState = uiState.value.profileDialogState as? ProfileDialogState.Create ?: return
    val snapshot = currentProfileSnapshotOrNull() ?: return

    viewModelScope.launch {
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Creating, error = null) }
      try {
        profileRepository.createProfile(dialogState.name, snapshot)
        mutableUiState.update { it.copy(profileActionInFlight = null, profileDialogState = null, error = null) }
        mutableSnackbarMessages.emit("Profile saved")
      } catch (throwable: Throwable) {
        handleProfileFailure(throwable)
      }
    }
  }

  fun renameProfile() {
    val dialogState = uiState.value.profileDialogState as? ProfileDialogState.Rename ?: return

    viewModelScope.launch {
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Renaming(dialogState.profileId), error = null) }
      try {
        profileRepository.renameProfile(dialogState.profileId, dialogState.name)
        mutableUiState.update { it.copy(profileActionInFlight = null, profileDialogState = null, error = null) }
        mutableSnackbarMessages.emit("Profile renamed")
      } catch (throwable: Throwable) {
        handleProfileFailure(throwable)
      }
    }
  }

  fun updateProfileFromCurrent() {
    val dialogState = uiState.value.profileDialogState as? ProfileDialogState.Update ?: return
    val snapshot = currentProfileSnapshotOrNull() ?: return

    viewModelScope.launch {
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Updating(dialogState.profileId), error = null) }
      try {
        profileRepository.updateProfile(dialogState.profileId, snapshot)
        mutableUiState.update { it.copy(profileActionInFlight = null, profileDialogState = null, error = null) }
        mutableSnackbarMessages.emit("Profile updated")
      } catch (throwable: Throwable) {
        handleProfileFailure(throwable)
      }
    }
  }

  fun deleteProfile() {
    val dialogState = uiState.value.profileDialogState as? ProfileDialogState.Delete ?: return

    viewModelScope.launch {
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Deleting(dialogState.profileId), error = null) }
      try {
        profileRepository.deleteProfile(dialogState.profileId)
        mutableUiState.update { it.copy(profileActionInFlight = null, profileDialogState = null, error = null) }
        mutableSnackbarMessages.emit("Profile deleted")
      } catch (throwable: Throwable) {
        handleProfileFailure(throwable)
      }
    }
  }

  fun confirmApplyProfile() {
    val dialogState = uiState.value.profileDialogState as? ProfileDialogState.Apply ?: return
    applyProfile(dialogState.profileId)
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

  private fun observeProfiles() {
    viewModelScope.launch {
      profileRepository.state.collect { profileState ->
        mutableUiState.update { state ->
          state.copy(
            profiles = profileState.profiles,
            lastAppliedProfileId = profileState.lastAppliedProfileId,
          )
        }
      }
    }
  }

  private fun applyProfile(profileId: String) {
    val profile = findProfile(profileId) ?: return

    viewModelScope.launch {
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Applying(profileId), error = null) }
      try {
        val currentPolicies = CpuPolicyApi.loadPolicies()
        val issue = findProfileCompatibilityIssue(profile, currentPolicies)
        if (issue != null) throw IllegalArgumentException(issue)

        for (savedPolicy in profile.policies) {
          val currentPolicy =
            currentPolicies.firstOrNull { it.name == savedPolicy.policyName }
              ?: throw IllegalArgumentException("Policy ${savedPolicy.policyName} is no longer available.")
          CpuPolicyApi.applyPolicy(currentPolicy, savedPolicy.minFreqKhz, savedPolicy.maxFreqKhz, savedPolicy.governor)
        }

        profileRepository.setLastApplied(profile.id, System.currentTimeMillis())
        mutableUiState.update {
          it.copy(
            profileActionInFlight = null,
            profileDialogState = null,
            isProfilesSheetVisible = false,
            drafts = emptyMap(),
            error = null,
          )
        }
        refreshPolicies()
        mutableSnackbarMessages.emit("${profile.name} applied")
      } catch (throwable: Throwable) {
        handleProfileFailure(throwable)
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

  private fun currentProfileSnapshotOrNull(): List<CpuProfilePolicy>? =
    runCatching { buildProfileSnapshot(uiState.value.policies, uiState.value.drafts) }
      .onFailure { throwable ->
        val error = toCpuError(throwable)
        Log.e(tag, "currentProfileSnapshotOrNull() failed error=${error.summary}", throwable)
        mutableUiState.update { it.copy(error = error) }
      }.getOrNull()

  private fun findProfile(profileId: String): CpuProfile? = uiState.value.profiles.firstOrNull { it.id == profileId }

  private fun handleProfileFailure(throwable: Throwable) {
    val error = toCpuError(throwable)
    Log.e(tag, "handleProfileFailure() error=${error.summary}", throwable)
    mutableUiState.update { it.copy(profileActionInFlight = null, error = error) }
  }

  private fun toCpuError(throwable: Throwable) =
    when (throwable) {
      is CpuException -> throwable.error
      is IllegalArgumentException -> CpuError.Validation(throwable.message ?: "Invalid input")
      else -> CpuError.Unknown(throwable)
    }
}

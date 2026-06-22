package com.example.kernelman.ui.screen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kernelman.cpu.CpuError
import com.example.kernelman.cpu.CpuException
import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.cpu.CpuPolicyApi
import com.example.kernelman.gpu.GpuError
import com.example.kernelman.gpu.GpuException
import com.example.kernelman.gpu.GpuPolicy
import com.example.kernelman.gpu.GpuPolicyApi
import com.example.kernelman.profile.CpuProfile
import com.example.kernelman.profile.CpuProfileRepository
import com.example.kernelman.profile.KernelProfileApplier
import com.example.kernelman.profile.KernelProfileSnapshot
import com.example.kernelman.profile.buildProfileSnapshot
import com.example.kernelman.profile.toKernelProfileErrorMessage
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

data class GpuPolicyDraft(
  val minFreqHz: Long,
  val maxFreqHz: Long,
  val governor: String?,
  val defaultPowerLevel: Int?,
)

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
  val cpuPolicies: List<CpuPolicy> = emptyList(),
  val cpuSupportMessage: String? = null,
  val currentCpuFreqsKhz: Map<String, Long?> = emptyMap(),
  val cpuDrafts: Map<String, CpuPolicyDraft> = emptyMap(),
  val savingCpuPolicyName: String? = null,
  val gpuPolicies: List<GpuPolicy> = emptyList(),
  val gpuSupportMessage: String? = null,
  val currentGpuFreqsHz: Map<String, Long?> = emptyMap(),
  val gpuDrafts: Map<String, GpuPolicyDraft> = emptyMap(),
  val savingGpuPolicyName: String? = null,
  val profiles: List<CpuProfile> = emptyList(),
  val lastAppliedProfileId: String? = null,
  val isProfilesSheetVisible: Boolean = false,
  val profileDialogState: ProfileDialogState? = null,
  val profileActionInFlight: ProfileAction? = null,
  val errorMessage: String? = null,
)

class CpuViewModel(application: Application) : AndroidViewModel(application) {
  private companion object {
    const val tag = "CpuViewModel"
    const val refreshIntervalMs = 1000L
  }

  private val profileRepository = CpuProfileRepository(application.applicationContext)
  private val profileApplier = KernelProfileApplier(profileRepository)
  private val mutableUiState = MutableStateFlow(CpuScreenState())
  val uiState: StateFlow<CpuScreenState> = mutableUiState.asStateFlow()

  private val mutableSnackbarMessages = MutableSharedFlow<String>()
  val snackbarMessages: SharedFlow<String> = mutableSnackbarMessages.asSharedFlow()

  private val mutableProfileSavedEvents = MutableSharedFlow<Unit>()
  val profileSavedEvents: SharedFlow<Unit> = mutableProfileSavedEvents.asSharedFlow()

  init {
    Log.d(tag, "init")
    observeProfiles()
    viewModelScope.launch {
      refreshPolicies()
      while (isActive) {
        delay(refreshIntervalMs)
        val state = uiState.value
        if (state.savingCpuPolicyName != null || state.savingGpuPolicyName != null || state.profileActionInFlight != null) continue
        refreshCurrentFrequencies()
      }
    }
  }

  fun startEditingProfile(profileId: String?) {
    if (profileId == null) {
      mutableUiState.update { it.copy(cpuDrafts = emptyMap(), gpuDrafts = emptyMap(), errorMessage = null) }
    } else {
      val profile = findProfile(profileId) ?: return
      val cpuDrafts = profile.policies.associate { policy ->
        policy.policyName to CpuPolicyDraft(
          minFreqKhz = policy.minFreqKhz,
          maxFreqKhz = policy.maxFreqKhz,
          governor = policy.governor,
        )
      }
      val gpuDrafts = profile.gpuPolicies.associate { policy ->
        policy.policyName to GpuPolicyDraft(
          minFreqHz = policy.minFreqHz,
          maxFreqHz = policy.maxFreqHz,
          governor = policy.governor,
          defaultPowerLevel = policy.defaultPowerLevel,
        )
      }
      mutableUiState.update { it.copy(cpuDrafts = cpuDrafts, gpuDrafts = gpuDrafts, errorMessage = null) }
    }
  }

  fun showProfilesSheet() {
    mutableUiState.update { it.copy(isProfilesSheetVisible = true, errorMessage = null) }
  }

  fun hideProfilesSheet() {
    if (uiState.value.profileActionInFlight != null) return
    mutableUiState.update { it.copy(isProfilesSheetVisible = false) }
  }

  fun showCreateProfileDialog() {
    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Create(), errorMessage = null) }
  }

  fun showRenameProfileDialog(profileId: String) {
    val profile = findProfile(profileId) ?: return
    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Rename(profileId, profile.name), errorMessage = null) }
  }

  fun showUpdateProfileDialog(profileId: String) {
    if (findProfile(profileId) == null) return
    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Update(profileId), errorMessage = null) }
  }

  fun showDeleteProfileDialog(profileId: String) {
    if (findProfile(profileId) == null) return
    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Delete(profileId), errorMessage = null) }
  }

  fun promptApplyProfile(profileId: String) {
    if (findProfile(profileId) == null) return
    if (uiState.value.cpuDrafts.isEmpty() && uiState.value.gpuDrafts.isEmpty()) {
      applyProfile(profileId)
      return
    }

    mutableUiState.update { it.copy(profileDialogState = ProfileDialogState.Apply(profileId), errorMessage = null) }
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

      state.copy(profileDialogState = dialogState, errorMessage = null)
    }
  }

  fun createProfile() {
    val dialogState = uiState.value.profileDialogState as? ProfileDialogState.Create ?: return
    val snapshot = currentProfileSnapshotOrNull() ?: return

    viewModelScope.launch {
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Creating, errorMessage = null) }
      try {
        profileRepository.createProfile(dialogState.name, snapshot)
        mutableUiState.update { it.copy(profileActionInFlight = null, profileDialogState = null, errorMessage = null) }
        mutableSnackbarMessages.emit("Profile saved")
        mutableProfileSavedEvents.emit(Unit)
      } catch (throwable: Throwable) {
        handleProfileFailure(throwable)
      }
    }
  }

  fun renameProfile() {
    val dialogState = uiState.value.profileDialogState as? ProfileDialogState.Rename ?: return

    viewModelScope.launch {
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Renaming(dialogState.profileId), errorMessage = null) }
      try {
        profileRepository.renameProfile(dialogState.profileId, dialogState.name)
        mutableUiState.update { it.copy(profileActionInFlight = null, profileDialogState = null, errorMessage = null) }
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
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Updating(dialogState.profileId), errorMessage = null) }
      try {
        profileRepository.updateProfile(dialogState.profileId, snapshot)
        mutableUiState.update { it.copy(profileActionInFlight = null, profileDialogState = null, errorMessage = null) }
        mutableSnackbarMessages.emit("Profile updated")
        mutableProfileSavedEvents.emit(Unit)
      } catch (throwable: Throwable) {
        handleProfileFailure(throwable)
      }
    }
  }

  fun deleteProfile() {
    val dialogState = uiState.value.profileDialogState as? ProfileDialogState.Delete ?: return

    viewModelScope.launch {
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Deleting(dialogState.profileId), errorMessage = null) }
      try {
        profileRepository.deleteProfile(dialogState.profileId)
        mutableUiState.update { it.copy(profileActionInFlight = null, profileDialogState = null, errorMessage = null) }
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

  fun updateMin(policyName: String, minFreqKhz: Long) = updateCpuDraft(policyName) { copy(minFreqKhz = minFreqKhz) }

  fun updateMax(policyName: String, maxFreqKhz: Long) = updateCpuDraft(policyName) { copy(maxFreqKhz = maxFreqKhz) }

  fun updateGovernor(policyName: String, governor: String) = updateCpuDraft(policyName) { copy(governor = governor) }

  fun updateGpuMin(policyName: String, minFreqHz: Long) = updateGpuDraft(policyName) { copy(minFreqHz = minFreqHz) }

  fun updateGpuMax(policyName: String, maxFreqHz: Long) = updateGpuDraft(policyName) { copy(maxFreqHz = maxFreqHz) }

  fun updateGpuGovernor(policyName: String, governor: String) = updateGpuDraft(policyName) { copy(governor = governor) }

  fun updateGpuDefaultPowerLevel(policyName: String, defaultPowerLevel: Int) =
    updateGpuDraft(policyName) { copy(defaultPowerLevel = defaultPowerLevel) }

  fun savePolicy(policyName: String) {
    val policy = uiState.value.cpuPolicies.firstOrNull { it.name == policyName }
    if (policy == null) {
      Log.w(tag, "savePolicy() missing CPU policy=$policyName")
      return
    }

    val draft = uiState.value.cpuDrafts[policyName] ?: defaultCpuDraft(policy)
    Log.d(tag, "savePolicy() policy=$policyName draft=$draft")

    viewModelScope.launch {
      mutableUiState.update { it.copy(savingCpuPolicyName = policyName, errorMessage = null) }
      try {
        CpuPolicyApi.applyPolicy(policy, draft.minFreqKhz, draft.maxFreqKhz, draft.governor)
        mutableUiState.update { state -> state.copy(savingCpuPolicyName = null, cpuDrafts = state.cpuDrafts - policyName, errorMessage = null) }
        refreshPolicies()
      } catch (throwable: Throwable) {
        handleStateFailure(throwable) { it.copy(savingCpuPolicyName = null) }
      }
    }
  }

  fun saveGpuPolicy(policyName: String) {
    val policy = uiState.value.gpuPolicies.firstOrNull { it.name == policyName }
    if (policy == null) {
      Log.w(tag, "saveGpuPolicy() missing GPU policy=$policyName")
      return
    }

    val draft = uiState.value.gpuDrafts[policyName] ?: defaultGpuDraft(policy)
    Log.d(tag, "saveGpuPolicy() policy=$policyName draft=$draft")

    viewModelScope.launch {
      mutableUiState.update { it.copy(savingGpuPolicyName = policyName, errorMessage = null) }
      try {
        GpuPolicyApi.applyPolicy(
          policy = policy,
          minFreqHz = draft.minFreqHz,
          maxFreqHz = draft.maxFreqHz,
          governor = draft.governor,
          defaultPowerLevel = draft.defaultPowerLevel,
        )
        mutableUiState.update { state -> state.copy(savingGpuPolicyName = null, gpuDrafts = state.gpuDrafts - policyName, errorMessage = null) }
        refreshPolicies()
      } catch (throwable: Throwable) {
        handleStateFailure(throwable) { it.copy(savingGpuPolicyName = null) }
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
      mutableUiState.update { it.copy(profileActionInFlight = ProfileAction.Applying(profileId), errorMessage = null) }
      try {
        profileApplier.apply(profile)
        mutableUiState.update {
          it.copy(
            profileActionInFlight = null,
            profileDialogState = null,
            isProfilesSheetVisible = false,
            cpuDrafts = emptyMap(),
            gpuDrafts = emptyMap(),
            errorMessage = null,
          )
        }
        refreshPolicies()
        mutableSnackbarMessages.emit("${profile.name} applied")
      } catch (throwable: Throwable) {
        handleProfileFailure(throwable)
      }
    }
  }

  private fun updateCpuDraft(policyName: String, transform: CpuPolicyDraft.() -> CpuPolicyDraft) {
    val policy = uiState.value.cpuPolicies.firstOrNull { it.name == policyName }
    if (policy == null) {
      Log.w(tag, "updateCpuDraft() missing CPU policy=$policyName")
      return
    }

    mutableUiState.update { state ->
      val currentDraft = state.cpuDrafts[policyName] ?: defaultCpuDraft(policy)
      val updatedDraft = currentDraft.transform()
      val cpuDrafts =
        if (updatedDraft == defaultCpuDraft(policy)) {
          state.cpuDrafts - policyName
        } else {
          state.cpuDrafts + (policyName to updatedDraft)
        }

      state.copy(cpuDrafts = cpuDrafts, errorMessage = null)
    }
  }

  private fun updateGpuDraft(policyName: String, transform: GpuPolicyDraft.() -> GpuPolicyDraft) {
    val policy = uiState.value.gpuPolicies.firstOrNull { it.name == policyName }
    if (policy == null) {
      Log.w(tag, "updateGpuDraft() missing GPU policy=$policyName")
      return
    }

    mutableUiState.update { state ->
      val currentDraft = state.gpuDrafts[policyName] ?: defaultGpuDraft(policy)
      val updatedDraft = currentDraft.transform()
      val gpuDrafts =
        if (updatedDraft == defaultGpuDraft(policy)) {
          state.gpuDrafts - policyName
        } else {
          state.gpuDrafts + (policyName to updatedDraft)
        }

      state.copy(gpuDrafts = gpuDrafts, errorMessage = null)
    }
  }

  private suspend fun refreshPolicies() {
    Log.d(tag, "refreshPolicies()")
    val cpuResult = runCatching { CpuPolicyApi.loadPolicies() }
    val gpuResult = runCatching { GpuPolicyApi.loadPolicies() }
    val cpuPolicies = cpuResult.getOrElse { emptyList() }
    val gpuPolicies = gpuResult.getOrElse { emptyList() }
    val cpuSupportMessage = cpuResult.exceptionOrNull()?.let(::toCpuSupportMessage)
    val gpuSupportMessage = gpuResult.exceptionOrNull()?.let(::toGpuSupportMessage)

    mutableUiState.update { state ->
      state.copy(
        isLoading = false,
        cpuPolicies = cpuPolicies,
        cpuSupportMessage = cpuSupportMessage,
        currentCpuFreqsKhz = cpuPolicies.associate { it.name to it.scalingCurFreqKhz },
        cpuDrafts = syncCpuDrafts(state.cpuDrafts, cpuPolicies),
        gpuPolicies = gpuPolicies,
        gpuSupportMessage = gpuSupportMessage,
        currentGpuFreqsHz = gpuPolicies.associate { it.name to it.curFreqHz },
        gpuDrafts = syncGpuDrafts(state.gpuDrafts, gpuPolicies),
        errorMessage = null,
      )
    }
  }

  private suspend fun refreshCurrentFrequencies() {
    val cpuPolicyNames = uiState.value.cpuPolicies.map(CpuPolicy::name)
    val gpuPolicyNames = uiState.value.gpuPolicies.map(GpuPolicy::name)
    if (cpuPolicyNames.isEmpty() && gpuPolicyNames.isEmpty()) return

    val cpuResult =
      runCatching {
        buildMap {
          for (policyName in cpuPolicyNames) put(policyName, CpuPolicyApi.readCurrentFreq(policyName))
        }
      }
    val gpuResult =
      runCatching {
        buildMap {
          for (policyName in gpuPolicyNames) put(policyName, GpuPolicyApi.readCurrentFreq(policyName))
        }
      }

    val errorMessage = listOfNotNull(cpuResult.exceptionOrNull(), gpuResult.exceptionOrNull()).map(::toErrorMessage).distinct().joinToString("\n")

    mutableUiState.update { state ->
      state.copy(
        currentCpuFreqsKhz = state.currentCpuFreqsKhz + cpuResult.getOrElse { emptyMap() },
        currentGpuFreqsHz = state.currentGpuFreqsHz + gpuResult.getOrElse { emptyMap() },
        errorMessage = errorMessage.ifBlank { null },
      )
    }
  }

  private fun syncCpuDrafts(drafts: Map<String, CpuPolicyDraft>, policies: List<CpuPolicy>): Map<String, CpuPolicyDraft> {
    val policiesByName = policies.associateBy(CpuPolicy::name)
    return drafts.filter { (name, draft) ->
      val policy = policiesByName[name] ?: return@filter false
      draft != defaultCpuDraft(policy)
    }
  }

  private fun syncGpuDrafts(drafts: Map<String, GpuPolicyDraft>, policies: List<GpuPolicy>): Map<String, GpuPolicyDraft> {
    val policiesByName = policies.associateBy(GpuPolicy::name)
    return drafts.filter { (name, draft) ->
      val policy = policiesByName[name] ?: return@filter false
      draft != defaultGpuDraft(policy)
    }
  }

  private fun currentProfileSnapshotOrNull(): KernelProfileSnapshot? =
    runCatching {
      buildProfileSnapshot(
        cpuPolicies = uiState.value.cpuPolicies,
        cpuDrafts = uiState.value.cpuDrafts,
        gpuPolicies = uiState.value.gpuPolicies,
        gpuDrafts = uiState.value.gpuDrafts,
      )
    }.onFailure { throwable ->
      val errorMessage = toErrorMessage(throwable)
      Log.e(tag, "currentProfileSnapshotOrNull() failed error=$errorMessage", throwable)
      mutableUiState.update { it.copy(errorMessage = errorMessage) }
    }.getOrNull()

  private fun findProfile(profileId: String): CpuProfile? = uiState.value.profiles.firstOrNull { it.id == profileId }

  private fun handleProfileFailure(throwable: Throwable) {
    val errorMessage = toErrorMessage(throwable)
    Log.e(tag, "handleProfileFailure() error=$errorMessage", throwable)
    mutableUiState.update { it.copy(profileActionInFlight = null, errorMessage = errorMessage) }
  }

  private fun handleStateFailure(throwable: Throwable, transform: (CpuScreenState) -> CpuScreenState) {
    val errorMessage = toErrorMessage(throwable)
    Log.e(tag, "handleStateFailure() error=$errorMessage", throwable)
    mutableUiState.update { state -> transform(state).copy(errorMessage = errorMessage) }
  }

  private fun toCpuSupportMessage(throwable: Throwable) =
    when (val error = (throwable as? CpuException)?.error) {
      is CpuError.RootUnavailable -> "CPU controls require root.\nReason: root shell is unavailable."
      is CpuError.NoPoliciesFound ->
        "KernelMan could not find a supported CPU policy interface.\nDetails: no ${error.rootPath}/policy* directories were found."
      is CpuError.MissingNode -> "KernelMan found CPU policies, but a required kernel node is missing.\nDetails: ${error.path}"
      is CpuError.RootCommandFailed -> "KernelMan could not access the CPU policy interface.\nDetails: ${error.summary}"
      is CpuError.ParseFailure -> "KernelMan found CPU policies, but a required node returned an unexpected value.\nDetails: ${error.path}"
      is CpuError.Validation -> error.summary
      is CpuError.Unknown, null -> toErrorMessage(throwable)
    }

  private fun toGpuSupportMessage(throwable: Throwable) =
    when (val error = (throwable as? GpuException)?.error) {
      is GpuError.RootUnavailable -> "GPU controls require root.\nReason: root shell is unavailable."
      is GpuError.NoPoliciesFound ->
        "KernelMan could not find a supported KGSL/devfreq GPU interface.\nDetails: no ${error.rootPath}/*kgsl-3d0* policy was found."
      is GpuError.MissingNode -> "KernelMan found a GPU policy, but a required kernel node is missing.\nDetails: ${error.path}"
      is GpuError.RootCommandFailed -> "KernelMan could not access the GPU policy interface.\nDetails: ${error.summary}"
      is GpuError.ParseFailure -> "KernelMan found a GPU policy, but a required node returned an unexpected value.\nDetails: ${error.path}"
      is GpuError.Validation -> error.summary
      is GpuError.Unknown, null -> toErrorMessage(throwable)
    }

  private fun toErrorMessage(throwable: Throwable) = throwable.toKernelProfileErrorMessage()

  private fun defaultCpuDraft(policy: CpuPolicy) =
    CpuPolicyDraft(
      minFreqKhz = policy.scalingMinFreqKhz,
      maxFreqKhz = policy.scalingMaxFreqKhz,
      governor = policy.governor,
    )

  private fun defaultGpuDraft(policy: GpuPolicy) =
    GpuPolicyDraft(
      minFreqHz = policy.minFreqHz,
      maxFreqHz = policy.maxFreqHz,
      governor = policy.governor,
      defaultPowerLevel = policy.defaultPowerLevel,
    )
}

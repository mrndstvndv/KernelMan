package com.example.kernelman.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kernelman.profile.CpuProfile
import com.example.kernelman.profile.CpuProfileRepository
import com.example.kernelman.profile.ProfileBootApplyStatus
import com.example.kernelman.profile.ProfileBootMode
import com.example.kernelman.profile.ProfileBootSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsScreenState(
  val profiles: List<CpuProfile> = emptyList(),
  val lastAppliedProfileId: String? = null,
  val bootSettings: ProfileBootSettings = ProfileBootSettings(),
  val bootApplyStatus: ProfileBootApplyStatus = ProfileBootApplyStatus(),
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
  private val profileRepository = CpuProfileRepository(application.applicationContext)
  private val mutableUiState = MutableStateFlow(SettingsScreenState())
  val uiState: StateFlow<SettingsScreenState> = mutableUiState.asStateFlow()

  init {
    viewModelScope.launch {
      profileRepository.state.collect { profileState ->
        mutableUiState.update {
          it.copy(
            profiles = profileState.profiles,
            lastAppliedProfileId = profileState.lastAppliedProfileId,
            bootSettings = profileState.bootSettings,
            bootApplyStatus = profileState.bootApplyStatus,
          )
        }
      }
    }
  }

  fun setBootApplyEnabled(enabled: Boolean) {
    if (enabled && uiState.value.profiles.isEmpty()) return

    viewModelScope.launch {
      val shouldEnsureSpecificProfile = enabled && uiState.value.bootSettings.mode == ProfileBootMode.SPECIFIC_PROFILE
      if (shouldEnsureSpecificProfile) ensureSpecificProfileSelection()
      profileRepository.setBootApplyEnabled(enabled)
    }
  }

  fun setBootDelaySeconds(delaySeconds: Int) {
    viewModelScope.launch {
      profileRepository.setBootApplyDelaySeconds(delaySeconds)
    }
  }

  fun setBootProfileMode(mode: ProfileBootMode) {
    viewModelScope.launch {
      if (mode == ProfileBootMode.SPECIFIC_PROFILE) ensureSpecificProfileSelection()
      profileRepository.setBootProfileMode(mode)
    }
  }

  fun setBootSpecificProfile(profileId: String) {
    viewModelScope.launch {
      profileRepository.setBootSpecificProfile(profileId)
    }
  }

  private suspend fun ensureSpecificProfileSelection() {
    val selectedProfileId = uiState.value.bootSettings.specificProfileId
    val selectedProfileExists = selectedProfileId != null && uiState.value.profiles.any { it.id == selectedProfileId }
    if (selectedProfileExists) return

    val firstProfileId = uiState.value.profiles.firstOrNull()?.id ?: return
    profileRepository.setBootSpecificProfile(firstProfileId)
  }
}

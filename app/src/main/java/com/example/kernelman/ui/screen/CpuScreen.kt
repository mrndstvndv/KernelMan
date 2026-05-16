package com.example.kernelman.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.gpu.GpuPolicy
import com.example.kernelman.profile.CpuProfile
import com.example.kernelman.profile.CpuProfilePolicy
import com.example.kernelman.profile.GpuProfilePolicy
import com.example.kernelman.ui.component.CpuPolicyCard
import com.example.kernelman.ui.component.ErrorCard
import com.example.kernelman.ui.component.GpuPolicyCard
import com.example.kernelman.ui.component.ProfileDialogHost
import com.example.kernelman.ui.component.ProfilesSheet
import com.example.kernelman.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.collect

@Composable
fun CpuScreen(modifier: Modifier = Modifier, viewModel: CpuViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(viewModel) {
    viewModel.snackbarMessages.collect { message -> snackbarHostState.showSnackbar(message) }
  }

  CpuScreen(
    state = state,
    snackbarHostState = snackbarHostState,
    onMinSelected = viewModel::updateMin,
    onMaxSelected = viewModel::updateMax,
    onGovernorSelected = viewModel::updateGovernor,
    onSave = viewModel::savePolicy,
    onGpuMinSelected = viewModel::updateGpuMin,
    onGpuMaxSelected = viewModel::updateGpuMax,
    onGpuGovernorSelected = viewModel::updateGpuGovernor,
    onGpuDefaultPowerLevelSelected = viewModel::updateGpuDefaultPowerLevel,
    onSaveGpuPolicy = viewModel::saveGpuPolicy,
    onShowProfiles = viewModel::showProfilesSheet,
    onDismissProfiles = viewModel::hideProfilesSheet,
    onCreateProfile = viewModel::showCreateProfileDialog,
    onRenameProfile = viewModel::showRenameProfileDialog,
    onUpdateProfile = viewModel::showUpdateProfileDialog,
    onDeleteProfile = viewModel::showDeleteProfileDialog,
    onApplyProfile = viewModel::promptApplyProfile,
    onDismissProfileDialog = viewModel::dismissProfileDialog,
    onProfileNameChanged = viewModel::updateProfileDialogName,
    onConfirmCreateProfile = viewModel::createProfile,
    onConfirmRenameProfile = viewModel::renameProfile,
    onConfirmUpdateProfile = viewModel::updateProfileFromCurrent,
    onConfirmDeleteProfile = viewModel::deleteProfile,
    onConfirmApplyProfile = viewModel::confirmApplyProfile,
    modifier = modifier,
  )
}

@Composable
internal fun CpuScreen(
  state: CpuScreenState,
  snackbarHostState: SnackbarHostState,
  onMinSelected: (String, Long) -> Unit,
  onMaxSelected: (String, Long) -> Unit,
  onGovernorSelected: (String, String) -> Unit,
  onSave: (String) -> Unit,
  onGpuMinSelected: (String, Long) -> Unit,
  onGpuMaxSelected: (String, Long) -> Unit,
  onGpuGovernorSelected: (String, String) -> Unit,
  onGpuDefaultPowerLevelSelected: (String, Int) -> Unit,
  onSaveGpuPolicy: (String) -> Unit,
  onShowProfiles: () -> Unit,
  onDismissProfiles: () -> Unit,
  onCreateProfile: () -> Unit,
  onRenameProfile: (String) -> Unit,
  onUpdateProfile: (String) -> Unit,
  onDeleteProfile: (String) -> Unit,
  onApplyProfile: (String) -> Unit,
  onDismissProfileDialog: () -> Unit,
  onProfileNameChanged: (String) -> Unit,
  onConfirmCreateProfile: () -> Unit,
  onConfirmRenameProfile: () -> Unit,
  onConfirmUpdateProfile: () -> Unit,
  onConfirmDeleteProfile: () -> Unit,
  onConfirmApplyProfile: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val lastAppliedProfile = state.profiles.firstOrNull { it.id == state.lastAppliedProfileId }
  val hasProfilesButton = state.cpuPolicies.isNotEmpty() || state.gpuPolicies.isNotEmpty()
  val hasDrafts = state.cpuDrafts.isNotEmpty() || state.gpuDrafts.isNotEmpty()

  Scaffold(
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    if (state.isLoading && state.cpuPolicies.isEmpty() && state.gpuPolicies.isEmpty()) {
      Box(
        modifier = modifier.fillMaxSize().padding(innerPadding),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
    } else {
      LazyColumn(
        modifier = modifier.fillMaxSize().padding(innerPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.Top,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Kernel controls", style = MaterialTheme.typography.headlineMedium)
                Text(text = "CPU policy and GPU devfreq controls.", style = MaterialTheme.typography.bodyMedium)
                lastAppliedProfile?.let { profile ->
                  Text(text = "Last applied: ${profile.name}", style = MaterialTheme.typography.bodySmall)
                }
              }

              if (hasProfilesButton) {
                OutlinedButton(onClick = onShowProfiles, enabled = state.profileActionInFlight == null) { Text(text = "Profiles") }
              }
            }

            if (hasDrafts) {
              Text(
                text = "Unsaved CPU or GPU edits can be saved as a profile without applying them.",
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
        }

        state.errorMessage?.let { errorMessage ->
          item { ErrorCard(errorMessage) }
        }

        if (state.cpuPolicies.isEmpty() && state.gpuPolicies.isEmpty()) {
          item { Text(text = "No CPU or GPU controls found.") }
        }

        if (state.cpuPolicies.isNotEmpty()) {
          item { SectionHeader(title = "CPU policies", subtitle = "Policy-based CPU frequency and governor controls.") }
          items(items = state.cpuPolicies, key = { it.name }) { policy ->
            val draft = state.cpuDrafts[policy.name] ?: CpuPolicyDraft(policy.scalingMinFreqKhz, policy.scalingMaxFreqKhz, policy.governor)
            CpuPolicyCard(
              policy = policy,
              draft = draft,
              currentFreqKhz = state.currentCpuFreqsKhz[policy.name] ?: policy.scalingCurFreqKhz,
              isSaving = state.savingCpuPolicyName == policy.name,
              onMinSelected = { onMinSelected(policy.name, it) },
              onMaxSelected = { onMaxSelected(policy.name, it) },
              onGovernorSelected = { onGovernorSelected(policy.name, it) },
              onSave = { onSave(policy.name) },
            )
          }
        }

        if (state.gpuPolicies.isNotEmpty()) {
          item { SectionHeader(title = "GPU controls", subtitle = "KGSL/devfreq GPU frequency, governor, and default power-level controls.") }
          items(items = state.gpuPolicies, key = { it.name }) { policy ->
            val draft =
              state.gpuDrafts[policy.name]
                ?: GpuPolicyDraft(
                  minFreqHz = policy.minFreqHz,
                  maxFreqHz = policy.maxFreqHz,
                  governor = policy.governor,
                  defaultPowerLevel = policy.defaultPowerLevel,
                )
            GpuPolicyCard(
              policy = policy,
              draft = draft,
              currentFreqHz = state.currentGpuFreqsHz[policy.name] ?: policy.curFreqHz,
              isSaving = state.savingGpuPolicyName == policy.name,
              onMinSelected = { onGpuMinSelected(policy.name, it) },
              onMaxSelected = { onGpuMaxSelected(policy.name, it) },
              onGovernorSelected = { onGpuGovernorSelected(policy.name, it) },
              onDefaultPowerLevelSelected = { onGpuDefaultPowerLevelSelected(policy.name, it) },
              onSave = { onSaveGpuPolicy(policy.name) },
            )
          }
        }
      }
    }
  }

  if (state.isProfilesSheetVisible) {
    ProfilesSheet(
      profiles = state.profiles,
      cpuPolicies = state.cpuPolicies,
      gpuPolicies = state.gpuPolicies,
      lastAppliedProfileId = state.lastAppliedProfileId,
      profileActionInFlight = state.profileActionInFlight,
      onCreateProfile = onCreateProfile,
      onApplyProfile = onApplyProfile,
      onUpdateProfile = onUpdateProfile,
      onRenameProfile = onRenameProfile,
      onDeleteProfile = onDeleteProfile,
      onDismiss = onDismissProfiles,
    )
  }

  ProfileDialogHost(
    dialogState = state.profileDialogState,
    profiles = state.profiles,
    hasDrafts = hasDrafts,
    profileActionInFlight = state.profileActionInFlight,
    onNameChanged = onProfileNameChanged,
    onDismiss = onDismissProfileDialog,
    onConfirmCreate = onConfirmCreateProfile,
    onConfirmRename = onConfirmRenameProfile,
    onConfirmUpdate = onConfirmUpdateProfile,
    onConfirmDelete = onConfirmDeleteProfile,
    onConfirmApply = onConfirmApplyProfile,
  )
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
  }
}

private val previewCpuPolicies =
  listOf(
    CpuPolicy(
      name = "policy0",
      cpuInfoMinFreqKhz = 300_000,
      cpuInfoMaxFreqKhz = 1_800_000,
      scalingMinFreqKhz = 652_800,
      scalingMaxFreqKhz = 1_267_200,
      scalingCurFreqKhz = 940_800,
      governor = "schedutil",
      availableFreqsKhz = listOf(300_000, 652_800, 940_800, 1_267_200, 1_555_200, 1_800_000),
      availableGovernors = listOf("performance", "powersave", "schedutil"),
    ),
    CpuPolicy(
      name = "policy4",
      cpuInfoMinFreqKhz = 710_400,
      cpuInfoMaxFreqKhz = 2_400_000,
      scalingMinFreqKhz = 1_248_000,
      scalingMaxFreqKhz = 2_208_000,
      scalingCurFreqKhz = 1_555_200,
      governor = "performance",
      availableFreqsKhz = listOf(710_400, 1_248_000, 1_555_200, 1_804_800, 2_208_000, 2_400_000),
      availableGovernors = listOf("performance", "powersave", "schedutil"),
    ),
  )

private val previewGpuPolicies =
  listOf(
    GpuPolicy(
      name = "3d00000.qcom,kgsl-3d0",
      minFreqHz = 315_000_000,
      maxFreqHz = 905_000_000,
      curFreqHz = 585_000_000,
      governor = "msm-adreno-tz",
      availableFreqsHz = listOf(315_000_000, 420_000_000, 585_000_000, 738_000_000, 905_000_000),
      availableGovernors = listOf("msm-adreno-tz", "performance", "powersave", "userspace"),
      minPowerLevel = 4,
      maxPowerLevel = 0,
      defaultPowerLevel = 3,
      numPowerLevels = 5,
    ),
  )

private val previewProfiles =
  listOf(
    CpuProfile(
      id = "gaming",
      name = "Gaming",
      createdAtEpochMs = 0,
      updatedAtEpochMs = 0,
      policies =
        listOf(
          CpuProfilePolicy(policyName = "policy0", minFreqKhz = 652_800, maxFreqKhz = 1_555_200, governor = "schedutil"),
          CpuProfilePolicy(policyName = "policy4", minFreqKhz = 1_248_000, maxFreqKhz = 2_208_000, governor = "performance"),
        ),
      gpuPolicies =
        listOf(
          GpuProfilePolicy(
            policyName = "3d00000.qcom,kgsl-3d0",
            minFreqHz = 585_000_000,
            maxFreqHz = 905_000_000,
            governor = "performance",
            defaultPowerLevel = 1,
          ),
        ),
    ),
  )

@Composable
private fun PreviewCpuScreen(state: CpuScreenState) {
  val snackbarHostState = remember { SnackbarHostState() }

  CpuScreen(
    state = state,
    snackbarHostState = snackbarHostState,
    onMinSelected = { _, _ -> },
    onMaxSelected = { _, _ -> },
    onGovernorSelected = { _, _ -> },
    onSave = {},
    onGpuMinSelected = { _, _ -> },
    onGpuMaxSelected = { _, _ -> },
    onGpuGovernorSelected = { _, _ -> },
    onGpuDefaultPowerLevelSelected = { _, _ -> },
    onSaveGpuPolicy = {},
    onShowProfiles = {},
    onDismissProfiles = {},
    onCreateProfile = {},
    onRenameProfile = { _ -> },
    onUpdateProfile = { _ -> },
    onDeleteProfile = { _ -> },
    onApplyProfile = { _ -> },
    onDismissProfileDialog = {},
    onProfileNameChanged = {},
    onConfirmCreateProfile = {},
    onConfirmRenameProfile = {},
    onConfirmUpdateProfile = {},
    onConfirmDeleteProfile = {},
    onConfirmApplyProfile = {},
    modifier = Modifier.padding(16.dp),
  )
}

@Preview(showBackground = true)
@Composable
private fun CpuScreenPreview() {
  MyApplicationTheme {
    PreviewCpuScreen(
      state =
        CpuScreenState(
          cpuPolicies = previewCpuPolicies,
          cpuDrafts = mapOf("policy0" to CpuPolicyDraft(652_800, 1_555_200, "powersave")),
          gpuPolicies = previewGpuPolicies,
          gpuDrafts =
            mapOf(
              "3d00000.qcom,kgsl-3d0" to
                GpuPolicyDraft(
                  minFreqHz = 585_000_000,
                  maxFreqHz = 905_000_000,
                  governor = "performance",
                  defaultPowerLevel = 1,
                ),
            ),
          profiles = previewProfiles,
          lastAppliedProfileId = "gaming",
        ),
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun CpuScreenErrorPreview() {
  MyApplicationTheme {
    PreviewCpuScreen(
      state = CpuScreenState(isLoading = false, cpuPolicies = previewCpuPolicies, gpuPolicies = previewGpuPolicies, errorMessage = "Preview error"),
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun CpuScreenMissingFrequenciesPreview() {
  MyApplicationTheme {
    PreviewCpuScreen(
      state =
        CpuScreenState(
          isLoading = false,
          cpuPolicies = listOf(previewCpuPolicies.first().copy(availableFreqsKhz = emptyList(), availableGovernors = emptyList())),
          gpuPolicies = listOf(previewGpuPolicies.first().copy(availableFreqsHz = emptyList(), availableGovernors = emptyList())),
        ),
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun CpuScreenLoadingPreview() {
  MyApplicationTheme { PreviewCpuScreen(state = CpuScreenState()) }
}

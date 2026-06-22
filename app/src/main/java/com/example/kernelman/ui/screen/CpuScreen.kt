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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.example.kernelman.ui.component.SupportStatusCard
import com.example.kernelman.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.collect

@Composable
fun CpuScreen(
  profileId: String?,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: CpuViewModel = viewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(viewModel, profileId) {
    viewModel.startEditingProfile(profileId)
  }

  LaunchedEffect(viewModel) {
    viewModel.snackbarMessages.collect { message -> snackbarHostState.showSnackbar(message) }
  }

  LaunchedEffect(viewModel) {
    viewModel.profileSavedEvents.collect {
      onBack()
    }
  }

  CpuScreen(
    profileId = profileId,
    state = state,
    snackbarHostState = snackbarHostState,
    onBack = onBack,
    onMinSelected = viewModel::updateMin,
    onMaxSelected = viewModel::updateMax,
    onGovernorSelected = viewModel::updateGovernor,
    onGpuMinSelected = viewModel::updateGpuMin,
    onGpuMaxSelected = viewModel::updateGpuMax,
    onGpuGovernorSelected = viewModel::updateGpuGovernor,
    onGpuDefaultPowerLevelSelected = viewModel::updateGpuDefaultPowerLevel,
    onSaveProfile = {
      if (profileId != null) {
        viewModel.showUpdateProfileDialog(profileId)
      } else {
        viewModel.showCreateProfileDialog()
      }
    },
    onDismissProfileDialog = viewModel::dismissProfileDialog,
    onProfileNameChanged = viewModel::updateProfileDialogName,
    onConfirmCreateProfile = viewModel::createProfile,
    onConfirmUpdateProfile = viewModel::updateProfileFromCurrent,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CpuScreen(
  profileId: String?,
  state: CpuScreenState,
  snackbarHostState: SnackbarHostState,
  onBack: () -> Unit,
  onMinSelected: (String, Long) -> Unit,
  onMaxSelected: (String, Long) -> Unit,
  onGovernorSelected: (String, String) -> Unit,
  onGpuMinSelected: (String, Long) -> Unit,
  onGpuMaxSelected: (String, Long) -> Unit,
  onGpuGovernorSelected: (String, String) -> Unit,
  onGpuDefaultPowerLevelSelected: (String, Int) -> Unit,
  onSaveProfile: () -> Unit,
  onDismissProfileDialog: () -> Unit,
  onProfileNameChanged: (String) -> Unit,
  onConfirmCreateProfile: () -> Unit,
  onConfirmUpdateProfile: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasDrafts = state.cpuDrafts.isNotEmpty() || state.gpuDrafts.isNotEmpty()

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(text = if (profileId != null) "Edit Profile" else "Configure Profile") },
        navigationIcon = { TextButton(onClick = onBack) { Text(text = "Back") } },
        actions = {
          TextButton(
            onClick = onSaveProfile,
            enabled = state.profileActionInFlight == null
          ) {
            Text(text = "Save")
          }
        }
      )
    },
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    if (state.isLoading && state.cpuPolicies.isEmpty() && state.gpuPolicies.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        state.errorMessage?.let { errorMessage ->
          item { ErrorCard(errorMessage) }
        }

        item { SectionHeader(title = "CPU policies", subtitle = "Policy-based CPU frequency and governor controls.") }
        if (state.cpuPolicies.isNotEmpty()) {
          items(items = state.cpuPolicies, key = { it.name }) { policy ->
            val draft = state.cpuDrafts[policy.name] ?: CpuPolicyDraft(policy.scalingMinFreqKhz, policy.scalingMaxFreqKhz, policy.governor)
            CpuPolicyCard(
              policy = policy,
              draft = draft,
              currentFreqKhz = state.currentCpuFreqsKhz[policy.name] ?: policy.scalingCurFreqKhz,
              isSaving = false,
              onMinSelected = { onMinSelected(policy.name, it) },
              onMaxSelected = { onMaxSelected(policy.name, it) },
              onGovernorSelected = { onGovernorSelected(policy.name, it) },
              onSave = null, // Disable individual saves
            )
          }
        } else {
          item {
            SupportStatusCard(
              title = "Feature not supported on your device",
              message = state.cpuSupportMessage ?: "KernelMan could not find a supported CPU policy interface.",
            )
          }
        }

        item { SectionHeader(title = "GPU controls", subtitle = "KGSL/devfreq GPU frequency, governor, and default power-level controls.") }
        if (state.gpuPolicies.isNotEmpty()) {
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
              isSaving = false,
              onMinSelected = { onGpuMinSelected(policy.name, it) },
              onMaxSelected = { onGpuMaxSelected(policy.name, it) },
              onGovernorSelected = { onGpuGovernorSelected(policy.name, it) },
              onDefaultPowerLevelSelected = { onGpuDefaultPowerLevelSelected(policy.name, it) },
              onSave = null, // Disable individual saves
            )
          }
        } else {
          item {
            SupportStatusCard(
              title = "Feature not supported on your device",
              message = state.gpuSupportMessage ?: "KernelMan could not find a supported KGSL/devfreq GPU interface.",
            )
          }
        }
      }
    }
  }

  ProfileDialogHost(
    dialogState = state.profileDialogState,
    profiles = state.profiles,
    hasDrafts = hasDrafts,
    profileActionInFlight = state.profileActionInFlight,
    onNameChanged = onProfileNameChanged,
    onDismiss = onDismissProfileDialog,
    onConfirmCreate = onConfirmCreateProfile,
    onConfirmRename = {},
    onConfirmUpdate = onConfirmUpdateProfile,
    onConfirmDelete = {},
    onConfirmApply = {},
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
    profileId = null,
    state = state,
    snackbarHostState = snackbarHostState,
    onBack = {},
    onMinSelected = { _, _ -> },
    onMaxSelected = { _, _ -> },
    onGovernorSelected = { _, _ -> },
    onGpuMinSelected = { _, _ -> },
    onGpuMaxSelected = { _, _ -> },
    onGpuGovernorSelected = { _, _ -> },
    onGpuDefaultPowerLevelSelected = { _, _ -> },
    onSaveProfile = {},
    onDismissProfileDialog = {},
    onProfileNameChanged = {},
    onConfirmCreateProfile = {},
    onConfirmUpdateProfile = {},
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

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kernelman.profile.CpuProfile
import com.example.kernelman.ui.component.ErrorCard
import com.example.kernelman.ui.component.ProfileCard
import com.example.kernelman.ui.component.ProfileDialogHost

@Composable
fun ProfilesListScreen(
  onOpenSettings: () -> Unit,
  onAddProfile: () -> Unit,
  onEditProfile: (String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: CpuViewModel = viewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(viewModel) {
    viewModel.snackbarMessages.collect { message ->
      snackbarHostState.showSnackbar(message)
    }
  }

  ProfilesListScreen(
    state = state,
    snackbarHostState = snackbarHostState,
    onOpenSettings = onOpenSettings,
    onAddProfile = onAddProfile,
    onEditProfile = onEditProfile,
    onApplyProfile = viewModel::promptApplyProfile,
    onRenameProfile = viewModel::showRenameProfileDialog,
    onDeleteProfile = viewModel::showDeleteProfileDialog,
    onDismissProfileDialog = viewModel::dismissProfileDialog,
    onProfileNameChanged = viewModel::updateProfileDialogName,
    onConfirmRenameProfile = viewModel::renameProfile,
    onConfirmDeleteProfile = viewModel::deleteProfile,
    onConfirmApplyProfile = viewModel::confirmApplyProfile,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilesListScreen(
  state: CpuScreenState,
  snackbarHostState: SnackbarHostState,
  onOpenSettings: () -> Unit,
  onAddProfile: () -> Unit,
  onEditProfile: (String) -> Unit,
  onApplyProfile: (String) -> Unit,
  onRenameProfile: (String) -> Unit,
  onDeleteProfile: (String) -> Unit,
  onDismissProfileDialog: () -> Unit,
  onProfileNameChanged: (String) -> Unit,
  onConfirmRenameProfile: () -> Unit,
  onConfirmDeleteProfile: () -> Unit,
  onConfirmApplyProfile: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasDrafts = state.cpuDrafts.isNotEmpty() || state.gpuDrafts.isNotEmpty()

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(text = "KernelMan") },
        actions = {
          OutlinedButton(
            onClick = onOpenSettings,
            enabled = state.profileActionInFlight == null,
            modifier = Modifier.padding(end = 8.dp)
          ) {
            Text(text = "Settings")
          }
        }
      )
    },
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    if (state.isLoading && state.profiles.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        state.errorMessage?.let { errorMessage ->
          item { ErrorCard(errorMessage) }
        }

        if (state.profiles.isEmpty()) {
          item {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = "No profiles configured yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        } else {
          items(items = state.profiles, key = { it.id }) { profile ->
            ProfileCard(
              profile = profile,
              isLastApplied = profile.id == state.lastAppliedProfileId,
              compatibilityIssue = null, // Can add check if needed
              isApplying = state.profileActionInFlight is ProfileAction.Applying && state.profileActionInFlight.profileId == profile.id,
              isBusy = state.profileActionInFlight != null,
              onApply = { onApplyProfile(profile.id) },
              onUpdate = { onEditProfile(profile.id) },
              onRename = { onRenameProfile(profile.id) },
              onDelete = { onDeleteProfile(profile.id) },
            )
          }
        }

        item {
          Button(
            onClick = onAddProfile,
            enabled = state.profileActionInFlight == null,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 8.dp)
          ) {
            Text(text = "Add New Profile")
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
    onConfirmCreate = {}, // We create through the Configure Profile screen instead
    onConfirmRename = onConfirmRenameProfile,
    onConfirmUpdate = {}, // We update through the Configure Profile screen instead
    onConfirmDelete = onConfirmDeleteProfile,
    onConfirmApply = onConfirmApplyProfile,
  )
}

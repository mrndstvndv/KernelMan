package com.example.kernelman.ui.component

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.gpu.GpuPolicy
import com.example.kernelman.profile.CpuProfile
import com.example.kernelman.profile.GpuProfilePolicy
import com.example.kernelman.profile.findProfileCompatibilityIssue
import com.example.kernelman.ui.screen.ProfileAction
import com.example.kernelman.ui.screen.ProfileDialogState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSheet(
  profiles: List<CpuProfile>,
  cpuPolicies: List<CpuPolicy>,
  gpuPolicies: List<GpuPolicy>,
  lastAppliedProfileId: String?,
  profileActionInFlight: ProfileAction?,
  onCreateProfile: () -> Unit,
  onApplyProfile: (String) -> Unit,
  onUpdateProfile: (String) -> Unit,
  onRenameProfile: (String) -> Unit,
  onDeleteProfile: (String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isCreating = profileActionInFlight is ProfileAction.Creating
  val canCreate = (cpuPolicies.isNotEmpty() || gpuPolicies.isNotEmpty()) && profileActionInFlight == null

  ModalBottomSheet(
    onDismissRequest = {
      if (profileActionInFlight == null) onDismiss()
    },
    dragHandle = { BottomSheetDefaults.DragHandle() },
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(text = "Profiles", style = MaterialTheme.typography.headlineSmall)
      Text(text = "Save CPU and GPU settings and reapply them later.", style = MaterialTheme.typography.bodyMedium)
      Button(onClick = onCreateProfile, enabled = canCreate) {
        Text(if (isCreating) "Creating..." else "Create from current")
      }

      if (profiles.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "No profiles yet", style = MaterialTheme.typography.titleMedium)
            Text(text = "Save your current CPU and GPU settings as reusable profiles.", style = MaterialTheme.typography.bodyMedium)
          }
        }
      } else {
        androidx.compose.foundation.lazy.LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(items = profiles, key = { it.id }) { profile ->
            val compatibilityIssue = findProfileCompatibilityIssue(profile, cpuPolicies, gpuPolicies)
            ProfileCard(
              profile = profile,
              isLastApplied = profile.id == lastAppliedProfileId,
              compatibilityIssue = compatibilityIssue,
              isApplying = profileActionInFlight is ProfileAction.Applying && profileActionInFlight.profileId == profile.id,
              isBusy = profileActionInFlight != null,
              onApply = { onApplyProfile(profile.id) },
              onUpdate = { onUpdateProfile(profile.id) },
              onRename = { onRenameProfile(profile.id) },
              onDelete = { onDeleteProfile(profile.id) },
            )
          }
        }
      }
    }
  }
}

@Composable
fun ProfileCard(
  profile: CpuProfile,
  isLastApplied: Boolean,
  compatibilityIssue: String?,
  isApplying: Boolean,
  isBusy: Boolean,
  onApply: () -> Unit,
  onUpdate: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var moreExpanded by remember { mutableStateOf(false) }
  val cpuCount = profile.policies.size
  val gpuCount = profile.gpuPolicies.size

  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = profile.name, style = MaterialTheme.typography.titleLarge)
        if (isLastApplied) {
          Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
            Text(
              text = "Last applied",
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }
      }

      Text(
        text = "Updated ${formatRelativeTime(profile.updatedAtEpochMs)} • $cpuCount CPU • $gpuCount GPU",
        style = MaterialTheme.typography.bodySmall,
      )

      if (compatibilityIssue != null) {
        Text(text = "Needs review", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
        Text(text = compatibilityIssue, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }

      profile.policies.forEach { policy ->
        Text(
          text = "CPU ${policy.policyName} · ${formatRange(policy.minFreqKhz, policy.maxFreqKhz)} · ${policy.governor}",
          style = MaterialTheme.typography.bodySmall,
        )
      }

      profile.gpuPolicies.forEach { policy ->
        Text(text = formatGpuProfileSummary(policy), style = MaterialTheme.typography.bodySmall)
      }

      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onApply, enabled = compatibilityIssue == null && !isBusy) {
          Text(if (isApplying) "Applying..." else "Apply")
        }
        Box {
          TextButton(onClick = { moreExpanded = true }, enabled = !isBusy) { Text(text = "More") }
          DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
            DropdownMenuItem(
              text = { Text(text = "Edit") },
              onClick = {
                moreExpanded = false
                onUpdate()
              },
            )
            DropdownMenuItem(
              text = { Text(text = "Rename") },
              onClick = {
                moreExpanded = false
                onRename()
              },
            )
            DropdownMenuItem(
              text = { Text(text = "Delete") },
              onClick = {
                moreExpanded = false
                onDelete()
              },
            )
          }
        }
      }
    }
  }
}

@Composable
fun ProfileDialogHost(
  dialogState: ProfileDialogState?,
  profiles: List<CpuProfile>,
  hasDrafts: Boolean,
  profileActionInFlight: ProfileAction?,
  onNameChanged: (String) -> Unit,
  onDismiss: () -> Unit,
  onConfirmCreate: () -> Unit,
  onConfirmRename: () -> Unit,
  onConfirmUpdate: () -> Unit,
  onConfirmDelete: () -> Unit,
  onConfirmApply: () -> Unit,
) {
  when (dialogState) {
    is ProfileDialogState.Create -> {
      val nameError = profileNameError(profiles = profiles, name = dialogState.name)
      AlertDialog(
        onDismissRequest = { if (profileActionInFlight == null) onDismiss() },
        title = { Text(text = "Create profile") },
        text = {
          OutlinedTextField(
            value = dialogState.name,
            onValueChange = onNameChanged,
            label = { Text(text = "Profile name") },
            isError = nameError != null,
            supportingText = {
              Text(
                text =
                  nameError
                    ?: if (hasDrafts) {
                      "Saves the current screen selections, including unsaved CPU and GPU edits."
                    } else {
                      "Saves the currently applied CPU and GPU values."
                    },
              )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        },
        confirmButton = {
          TextButton(onClick = onConfirmCreate, enabled = nameError == null && profileActionInFlight == null) {
            Text(if (profileActionInFlight is ProfileAction.Creating) "Creating..." else "Create")
          }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = profileActionInFlight == null) { Text(text = "Cancel") } },
      )
    }

    is ProfileDialogState.Rename -> {
      val nameError = profileNameError(profiles = profiles, name = dialogState.name, excludedProfileId = dialogState.profileId)
      AlertDialog(
        onDismissRequest = { if (profileActionInFlight == null) onDismiss() },
        title = { Text(text = "Rename profile") },
        text = {
          OutlinedTextField(
            value = dialogState.name,
            onValueChange = onNameChanged,
            label = { Text(text = "Profile name") },
            isError = nameError != null,
            supportingText = { Text(text = nameError ?: "Choose a unique profile name.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        },
        confirmButton = {
          TextButton(onClick = onConfirmRename, enabled = nameError == null && profileActionInFlight == null) {
            Text(if (profileActionInFlight is ProfileAction.Renaming) "Renaming..." else "Rename")
          }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = profileActionInFlight == null) { Text(text = "Cancel") } },
      )
    }

    is ProfileDialogState.Update -> {
      val profileName = profiles.firstOrNull { it.id == dialogState.profileId }?.name.orEmpty()
      AlertDialog(
        onDismissRequest = { if (profileActionInFlight == null) onDismiss() },
        title = { Text(text = "Update profile?") },
        text = { Text(text = "Replace \"$profileName\" with the current CPU and GPU selections?") },
        confirmButton = {
          TextButton(onClick = onConfirmUpdate, enabled = profileActionInFlight == null) {
            Text(if (profileActionInFlight is ProfileAction.Updating) "Updating..." else "Update")
          }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = profileActionInFlight == null) { Text(text = "Cancel") } },
      )
    }

    is ProfileDialogState.Delete -> {
      val profileName = profiles.firstOrNull { it.id == dialogState.profileId }?.name.orEmpty()
      AlertDialog(
        onDismissRequest = { if (profileActionInFlight == null) onDismiss() },
        title = { Text(text = "Delete profile?") },
        text = { Text(text = "Delete \"$profileName\"? This does not change the current CPU or GPU values.") },
        confirmButton = {
          TextButton(onClick = onConfirmDelete, enabled = profileActionInFlight == null) {
            Text(if (profileActionInFlight is ProfileAction.Deleting) "Deleting..." else "Delete")
          }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = profileActionInFlight == null) { Text(text = "Cancel") } },
      )
    }

    is ProfileDialogState.Apply -> {
      AlertDialog(
        onDismissRequest = { if (profileActionInFlight == null) onDismiss() },
        title = { Text(text = "Apply profile?") },
        text = { Text(text = "This will discard your unsaved CPU and GPU edits and apply the saved values.") },
        confirmButton = {
          TextButton(onClick = onConfirmApply, enabled = profileActionInFlight == null) {
            Text(if (profileActionInFlight is ProfileAction.Applying) "Applying..." else "Apply profile")
          }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = profileActionInFlight == null) { Text(text = "Cancel") } },
      )
    }

    null -> Unit
  }
}

private fun profileNameError(profiles: List<CpuProfile>, name: String, excludedProfileId: String? = null): String? {
  val trimmedName = name.trim()
  if (trimmedName.isBlank()) return "Profile name is required"
  if (trimmedName.length > 40) return "Profile name must be 40 characters or less"

  val duplicateProfile = profiles.firstOrNull { profile -> profile.id != excludedProfileId && profile.name.equals(trimmedName, ignoreCase = true) }
  if (duplicateProfile != null) return "Profile name already exists"

  return null
}

private fun formatRelativeTime(updatedAtEpochMs: Long): String =
  DateUtils.getRelativeTimeSpanString(updatedAtEpochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun formatGpuProfileSummary(policy: GpuProfilePolicy): String {
  val parts = buildList {
    add("GPU ${policy.policyName}")
    add(formatRangeHz(policy.minFreqHz, policy.maxFreqHz))
    policy.governor?.takeIf(String::isNotBlank)?.let(::add)
    policy.defaultPowerLevel?.let { add("default ${formatPowerLevel(it)}") }
  }

  return parts.joinToString(" · ")
}

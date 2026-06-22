package com.example.kernelman.ui.screen

import android.os.Build
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kernelman.profile.CpuProfile
import com.example.kernelman.profile.DefaultProfileBootDelaySeconds
import com.example.kernelman.profile.MaxProfileBootDelaySeconds
import com.example.kernelman.profile.ProfileBootApplyResult
import com.example.kernelman.profile.ProfileBootApplyStatus
import com.example.kernelman.profile.ProfileBootMode
import com.example.kernelman.profile.ProfileBootSettings
import com.example.kernelman.ui.theme.MyApplicationTheme

@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  SettingsScreen(
    state = state,
    onBack = onBack,
    onBootApplyEnabledChanged = viewModel::setBootApplyEnabled,
    onBootDelayChanged = viewModel::setBootDelaySeconds,
    onBootModeChanged = viewModel::setBootProfileMode,
    onBootSpecificProfileChanged = viewModel::setBootSpecificProfile,
    modifier = modifier,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
  state: SettingsScreenState,
  onBack: () -> Unit,
  onBootApplyEnabledChanged: (Boolean) -> Unit,
  onBootDelayChanged: (Int) -> Unit,
  onBootModeChanged: (ProfileBootMode) -> Unit,
  onBootSpecificProfileChanged: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var isNotificationPermissionGranted by remember {
    mutableStateOf(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
      } else {
        true
      }
    )
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    isNotificationPermissionGranted = isGranted
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        isNotificationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
          true
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val bootSettings = state.bootSettings
  val selectedSpecificProfile = state.profiles.firstOrNull { it.id == bootSettings.specificProfileId }
  val lastAppliedProfile = state.profiles.firstOrNull { it.id == state.lastAppliedProfileId }
  val canEnableBootApply = state.profiles.isNotEmpty()

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(text = "Settings") },
        navigationIcon = { TextButton(onClick = onBack) { Text(text = "Back") } },
      )
    },
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(text = "Boot profile", style = MaterialTheme.typography.headlineMedium)
          Text(
            text = "Choose whether KernelMan should reapply a profile after reboot.",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }

      item {
        BootApplyCard(
          enabled = bootSettings.enabled,
          canEnable = canEnableBootApply,
          onEnabledChanged = { enabled ->
            onBootApplyEnabledChanged(enabled)
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted) {
              permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
          },
          isNotificationPermissionGranted = isNotificationPermissionGranted,
          onRequestNotificationPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
          }
        )
      }

      item {
        DelayCard(
          delaySeconds = bootSettings.delaySeconds,
          enabled = bootSettings.enabled,
          onDelayChanged = onBootDelayChanged,
        )
      }

      item {
        BootTargetCard(
          profiles = state.profiles,
          mode = bootSettings.mode,
          enabled = bootSettings.enabled,
          lastAppliedProfile = lastAppliedProfile,
          selectedSpecificProfile = selectedSpecificProfile,
          onModeChanged = onBootModeChanged,
          onSpecificProfileChanged = onBootSpecificProfileChanged,
        )
      }

      state.bootApplyStatus.lastResult?.let { result ->
        item {
          BootStatusCard(
            status = state.bootApplyStatus.copy(lastResult = result),
          )
        }
      }
    }
  }
}

@Composable
private fun BootApplyCard(
  enabled: Boolean,
  canEnable: Boolean,
  onEnabledChanged: (Boolean) -> Unit,
  isNotificationPermissionGranted: Boolean,
  onRequestNotificationPermission: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(text = "Apply profile after reboot", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "Requires persistent root permission. KernelMan schedules the apply after Android finishes booting.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChanged.takeIf { canEnable }, enabled = canEnable)
      }

      if (!canEnable) {
        Text(
          text = "Create at least one profile before enabling boot apply.",
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }

      if (enabled && !isNotificationPermissionGranted) {
        Spacer(modifier = Modifier.height(4.dp))
        Card(
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
          ),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                text = "Notifications disabled",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold
              )
              Text(
                text = "KernelMan cannot notify you if the profile fails to apply at boot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
              )
            }
            Button(
              onClick = onRequestNotificationPermission,
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
              )
            ) {
              Text(text = "Fix", style = MaterialTheme.typography.labelMedium)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DelayCard(delaySeconds: Int, enabled: Boolean, onDelayChanged: (Int) -> Unit, modifier: Modifier = Modifier) {
  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(text = "Delay before applying", style = MaterialTheme.typography.titleMedium)
      DelaySecondsField(delaySeconds = delaySeconds, enabled = enabled, onDelayChanged = onDelayChanged)
      Text(
        text = "Longer delays help on devices where root or sysfs nodes are not ready immediately after boot.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun DelaySecondsField(delaySeconds: Int, enabled: Boolean, onDelayChanged: (Int) -> Unit, modifier: Modifier = Modifier) {
  var delayInput by rememberSaveable(delaySeconds) { mutableStateOf(delaySeconds.toString()) }
  val delayError = validateDelayInput(delayInput)

  OutlinedTextField(
    value = delayInput,
    onValueChange = { newValue ->
      val digitsOnly = newValue.filter(Char::isDigit)
      delayInput = digitsOnly

      val parsedDelaySeconds = digitsOnly.toIntOrNull() ?: return@OutlinedTextField
      val clampedDelaySeconds = parsedDelaySeconds.coerceIn(0, MaxProfileBootDelaySeconds)
      delayInput = clampedDelaySeconds.toString()
      onDelayChanged(clampedDelaySeconds)
    },
    enabled = enabled,
    singleLine = true,
    label = { Text(text = "Seconds") },
    supportingText = { Text(text = delayError ?: "Use ${0} to $MaxProfileBootDelaySeconds seconds.") },
    isError = delayError != null,
    modifier = modifier.fillMaxWidth(),
  )
}

@Composable
private fun BootTargetCard(
  profiles: List<CpuProfile>,
  mode: ProfileBootMode,
  enabled: Boolean,
  lastAppliedProfile: CpuProfile?,
  selectedSpecificProfile: CpuProfile?,
  onModeChanged: (ProfileBootMode) -> Unit,
  onSpecificProfileChanged: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val lastAppliedSummary =
    lastAppliedProfile?.let { "Uses ${it.name} at boot." } ?: "No profile has been applied yet. Boot apply will skip until one exists."
  val specificSummary =
    when {
      profiles.isEmpty() -> "No saved profiles available."
      selectedSpecificProfile == null -> "Choose which saved profile should be applied on boot."
      else -> "Uses ${selectedSpecificProfile.name} at boot."
    }

  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(text = "Boot profile target", style = MaterialTheme.typography.titleMedium)
      TargetOption(
        title = "Last applied profile",
        subtitle = lastAppliedSummary,
        selected = mode == ProfileBootMode.LAST_APPLIED,
        enabled = enabled,
        onClick = { onModeChanged(ProfileBootMode.LAST_APPLIED) },
      )
      TargetOption(
        title = "Specific profile",
        subtitle = specificSummary,
        selected = mode == ProfileBootMode.SPECIFIC_PROFILE,
        enabled = enabled && profiles.isNotEmpty(),
        onClick = { onModeChanged(ProfileBootMode.SPECIFIC_PROFILE) },
      )

      if (mode == ProfileBootMode.SPECIFIC_PROFILE) {
        ProfileSelector(
          profiles = profiles,
          selectedProfile = selectedSpecificProfile,
          enabled = enabled && profiles.isNotEmpty(),
          onSelected = onSpecificProfileChanged,
        )
      }
    }
  }
}

@Composable
private fun TargetOption(
  title: String,
  subtitle: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(8.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    RadioButton(selected = selected, onClick = null, enabled = enabled)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(text = title, style = MaterialTheme.typography.titleSmall)
      Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun ProfileSelector(
  profiles: List<CpuProfile>,
  selectedProfile: CpuProfile?,
  enabled: Boolean,
  onSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val selectionText = selectedProfile?.name ?: "Select profile"

  Box(modifier = modifier.fillMaxWidth()) {
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = "Specific profile", style = MaterialTheme.typography.labelSmall)
        Text(text = selectionText, style = MaterialTheme.typography.bodyMedium)
      }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      profiles.forEach { profile ->
        DropdownMenuItem(
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(text = profile.name)
              Text(
                text = "${profile.policies.size} CPU • ${profile.gpuPolicies.size} GPU",
                style = MaterialTheme.typography.bodySmall,
              )
            }
          },
          onClick = {
            expanded = false
            onSelected(profile.id)
          },
        )
      }
    }
  }
}

@Composable
private fun BootStatusCard(status: ProfileBootApplyStatus, modifier: Modifier = Modifier) {
  val result = status.lastResult ?: return
  val resultColor =
    when (result) {
      ProfileBootApplyResult.SUCCESS -> MaterialTheme.colorScheme.primary
      ProfileBootApplyResult.FAILED -> MaterialTheme.colorScheme.error
      ProfileBootApplyResult.SKIPPED -> MaterialTheme.colorScheme.secondary
    }

  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(text = "Last boot apply", style = MaterialTheme.typography.titleMedium)
      Text(
        text = result.toLabel(),
        color = resultColor,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
      )
      status.lastAttemptAtEpochMs?.let { attemptTime ->
        Text(text = formatRelativeTime(attemptTime), style = MaterialTheme.typography.bodySmall)
      }
      status.lastMessage?.let { message ->
        Text(text = message, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

private fun validateDelayInput(delayInput: String): String? {
  if (delayInput.isBlank()) return "Enter a delay in seconds."
  val delaySeconds = delayInput.toIntOrNull() ?: return "Use digits only."
  if (delaySeconds > MaxProfileBootDelaySeconds) return "Use 0 to $MaxProfileBootDelaySeconds seconds."
  return null
}

private fun ProfileBootApplyResult.toLabel() =
  when (this) {
    ProfileBootApplyResult.SUCCESS -> "Success"
    ProfileBootApplyResult.FAILED -> "Failed"
    ProfileBootApplyResult.SKIPPED -> "Skipped"
  }

private fun formatRelativeTime(updatedAtEpochMs: Long): String =
  DateUtils.getRelativeTimeSpanString(updatedAtEpochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private val previewProfiles =
  listOf(
    CpuProfile(
      id = "gaming",
      name = "Gaming",
      createdAtEpochMs = 0,
      updatedAtEpochMs = 0,
    ),
    CpuProfile(
      id = "battery",
      name = "Battery",
      createdAtEpochMs = 0,
      updatedAtEpochMs = 0,
    ),
  )

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
  MyApplicationTheme {
    SettingsScreen(
      state =
        SettingsScreenState(
          profiles = previewProfiles,
          lastAppliedProfileId = "gaming",
          bootSettings =
            ProfileBootSettings(
              enabled = true,
              delaySeconds = DefaultProfileBootDelaySeconds,
              mode = ProfileBootMode.SPECIFIC_PROFILE,
              specificProfileId = "battery",
            ),
          bootApplyStatus =
            ProfileBootApplyStatus(
              lastAttemptAtEpochMs = System.currentTimeMillis(),
              lastResult = ProfileBootApplyResult.SUCCESS,
              lastMessage = "Battery applied after boot.",
            ),
        ),
      onBack = {},
      onBootApplyEnabledChanged = {},
      onBootDelayChanged = {},
      onBootModeChanged = {},
      onBootSpecificProfileChanged = {},
      modifier = Modifier.padding(16.dp),
    )
  }
}

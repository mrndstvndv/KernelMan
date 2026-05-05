package com.example.kernelman.cpu

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kernelman.theme.MyApplicationTheme
import java.util.Locale

@Composable
fun CpuScreen(modifier: Modifier = Modifier, viewModel: CpuViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  CpuScreen(
    state = state,
    onMinSelected = viewModel::updateMin,
    onMaxSelected = viewModel::updateMax,
    onSave = viewModel::savePolicy,
    modifier = modifier,
  )
}

@Composable
internal fun CpuScreen(
  state: CpuScreenState,
  onMinSelected: (String, Long) -> Unit,
  onMaxSelected: (String, Long) -> Unit,
  onSave: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (state.isLoading && state.policies.isEmpty()) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    return
  }

  LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "CPU policies", style = MaterialTheme.typography.headlineMedium)
        Text(text = "Policy-based CPU frequency controls.", style = MaterialTheme.typography.bodyMedium)
      }
    }

    state.error?.let { error ->
      item { ErrorCard(error) }
    }

    if (state.policies.isEmpty()) {
      item { Text(text = "No CPU policies found.") }
    }

    items(items = state.policies, key = { it.name }) { policy ->
      val draft = state.drafts[policy.name] ?: CpuPolicyDraft(policy.scalingMinFreqKhz, policy.scalingMaxFreqKhz)
      CpuPolicyCard(
        policy = policy,
        draft = draft,
        isSaving = state.savingPolicyName == policy.name,
        onMinSelected = { onMinSelected(policy.name, it) },
        onMaxSelected = { onMaxSelected(policy.name, it) },
        onSave = { onSave(policy.name) },
      )
    }
  }
}

@Composable
private fun ErrorCard(error: CpuError, modifier: Modifier = Modifier) {
  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(text = "Error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
      Text(text = error.summary, color = MaterialTheme.colorScheme.error)
    }
  }
}

@Composable
private fun CpuPolicyCard(
  policy: CpuPolicy,
  draft: CpuPolicyDraft,
  isSaving: Boolean,
  onMinSelected: (Long) -> Unit,
  onMaxSelected: (Long) -> Unit,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasSelectableFrequencies = policy.availableFreqsKhz.isNotEmpty()
  val hasChanges = draft.minFreqKhz != policy.scalingMinFreqKhz || draft.maxFreqKhz != policy.scalingMaxFreqKhz
  val isValid = draft.minFreqKhz <= draft.maxFreqKhz

  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(text = policy.name, style = MaterialTheme.typography.titleLarge)
      InfoRow(label = "Supported", value = formatRange(policy.cpuInfoMinFreqKhz, policy.cpuInfoMaxFreqKhz))
      InfoRow(label = "Applied", value = formatRange(policy.scalingMinFreqKhz, policy.scalingMaxFreqKhz))
      InfoRow(label = "Current", value = formatKhz(policy.scalingCurFreqKhz))

      if (hasSelectableFrequencies) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
          FrequencySelector(
            label = "Min",
            selectedKhz = draft.minFreqKhz,
            options = policy.availableFreqsKhz,
            onSelected = onMinSelected,
            modifier = Modifier.weight(1f),
          )
          FrequencySelector(
            label = "Max",
            selectedKhz = draft.maxFreqKhz,
            options = policy.availableFreqsKhz,
            onSelected = onMaxSelected,
            modifier = Modifier.weight(1f),
          )
        }
      } else {
        Text(text = "Kernel did not expose selectable frequencies for this policy.", style = MaterialTheme.typography.bodySmall)
      }

      if (!isValid) {
        Text(text = "Min must be less than or equal to max.", color = MaterialTheme.colorScheme.error)
      }

      Button(
        onClick = onSave,
        enabled = hasSelectableFrequencies && hasChanges && isValid && !isSaving,
        modifier = Modifier.align(Alignment.End),
      ) {
        Text(if (isSaving) "Saving..." else "Save")
      }
    }
  }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(text = label, style = MaterialTheme.typography.labelMedium)
    Text(text = value, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun FrequencySelector(
  label: String,
  selectedKhz: Long,
  options: List<Long>,
  onSelected: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  Box(modifier = modifier) {
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = formatKhz(selectedKhz), style = MaterialTheme.typography.bodyMedium)
      }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { freqKhz ->
        DropdownMenuItem(
          text = { Text(text = formatKhz(freqKhz)) },
          onClick = {
            expanded = false
            onSelected(freqKhz)
          },
        )
      }
    }
  }
}

private fun formatRange(minFreqKhz: Long, maxFreqKhz: Long) = "${formatKhz(minFreqKhz)} → ${formatKhz(maxFreqKhz)}"

private fun formatKhz(freqKhz: Long?): String {
  if (freqKhz == null) return "Unavailable"
  if (freqKhz >= 1_000_000L) return String.format(Locale.US, "%.2f GHz", freqKhz / 1_000_000f)
  return String.format(Locale.US, "%.0f MHz", freqKhz / 1_000f)
}

private val previewPolicies =
  listOf(
    CpuPolicy(
      name = "policy0",
      cpuInfoMinFreqKhz = 300_000,
      cpuInfoMaxFreqKhz = 1_800_000,
      scalingMinFreqKhz = 652_800,
      scalingMaxFreqKhz = 1_267_200,
      scalingCurFreqKhz = 940_800,
      availableFreqsKhz = listOf(300_000, 652_800, 940_800, 1_267_200, 1_555_200, 1_800_000),
    ),
    CpuPolicy(
      name = "policy4",
      cpuInfoMinFreqKhz = 710_400,
      cpuInfoMaxFreqKhz = 2_400_000,
      scalingMinFreqKhz = 1_248_000,
      scalingMaxFreqKhz = 2_208_000,
      scalingCurFreqKhz = 1_555_200,
      availableFreqsKhz = listOf(710_400, 1_248_000, 1_555_200, 1_804_800, 2_208_000, 2_400_000),
    ),
  )

@Preview(showBackground = true)
@Composable
private fun CpuScreenPreview() {
  MyApplicationTheme {
    CpuScreen(
      state = CpuScreenState(policies = previewPolicies, drafts = mapOf("policy0" to CpuPolicyDraft(652_800, 1_555_200))),
      onMinSelected = { _, _ -> },
      onMaxSelected = { _, _ -> },
      onSave = {},
      modifier = Modifier.padding(16.dp),
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun CpuScreenErrorPreview() {
  MyApplicationTheme {
    CpuScreen(
      state = CpuScreenState(isLoading = false, policies = previewPolicies, error = CpuError.Validation("Preview validation error")),
      onMinSelected = { _, _ -> },
      onMaxSelected = { _, _ -> },
      onSave = {},
      modifier = Modifier.padding(16.dp),
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun CpuScreenMissingFrequenciesPreview() {
  MyApplicationTheme {
    CpuScreen(
      state =
        CpuScreenState(
          isLoading = false,
          policies = listOf(previewPolicies.first().copy(availableFreqsKhz = emptyList())),
        ),
      onMinSelected = { _, _ -> },
      onMaxSelected = { _, _ -> },
      onSave = {},
      modifier = Modifier.padding(16.dp),
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun CpuScreenLoadingPreview() {
  MyApplicationTheme {
    CpuScreen(
      state = CpuScreenState(),
      onMinSelected = { _, _ -> },
      onMaxSelected = { _, _ -> },
      onSave = {},
      modifier = Modifier.padding(16.dp),
    )
  }
}

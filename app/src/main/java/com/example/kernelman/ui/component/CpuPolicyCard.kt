package com.example.kernelman.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.ui.screen.CpuPolicyDraft

@Composable
fun CpuPolicyCard(
  policy: CpuPolicy,
  draft: CpuPolicyDraft,
  currentFreqKhz: Long?,
  isSaving: Boolean,
  onMinSelected: (Long) -> Unit,
  onMaxSelected: (Long) -> Unit,
  onGovernorSelected: (String) -> Unit,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasSelectableFrequencies = policy.availableFreqsKhz.isNotEmpty()
  val hasSelectableGovernors = policy.availableGovernors.isNotEmpty()
  val hasChanges =
    draft.minFreqKhz != policy.scalingMinFreqKhz || draft.maxFreqKhz != policy.scalingMaxFreqKhz || draft.governor != policy.governor
  val isValid = draft.minFreqKhz <= draft.maxFreqKhz

  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(text = policy.name, style = MaterialTheme.typography.titleLarge)
      InfoRow(label = "Supported", value = formatRange(policy.cpuInfoMinFreqKhz, policy.cpuInfoMaxFreqKhz))
      InfoRow(label = "Applied", value = formatRange(policy.scalingMinFreqKhz, policy.scalingMaxFreqKhz))
      InfoRow(label = "Current", value = formatKhz(currentFreqKhz))
      InfoRow(label = "Applied governor", value = policy.governor)

      if (hasSelectableGovernors) {
        GovernorSelector(selectedGovernor = draft.governor, options = policy.availableGovernors, onSelected = onGovernorSelected)
      } else {
        Text(text = "Kernel did not expose selectable governors for this policy.", style = MaterialTheme.typography.bodySmall)
      }

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
        enabled = hasChanges && isValid && !isSaving,
        modifier = Modifier.align(Alignment.End),
      ) {
        Text(if (isSaving) "Saving..." else "Save")
      }
    }
  }
}

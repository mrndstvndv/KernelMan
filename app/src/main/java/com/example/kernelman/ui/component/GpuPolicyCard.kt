package com.example.kernelman.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import com.example.kernelman.gpu.GpuPolicy
import com.example.kernelman.ui.screen.GpuPolicyDraft

@Composable
fun GpuPolicyCard(
  policy: GpuPolicy,
  draft: GpuPolicyDraft,
  currentFreqHz: Long?,
  isSaving: Boolean,
  onMinSelected: (Long) -> Unit,
  onMaxSelected: (Long) -> Unit,
  onGovernorSelected: (String) -> Unit,
  onDefaultPowerLevelSelected: (Int) -> Unit,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasSelectableFrequencies = policy.availableFreqsHz.isNotEmpty()
  val hasSelectableGovernors = !policy.governor.isNullOrBlank() && policy.availableGovernors.isNotEmpty()
  val powerLevelOptions = selectableDefaultPowerLevels(policy)
  val hasSelectableDefaultPowerLevel = policy.defaultPowerLevel != null && powerLevelOptions.isNotEmpty()
  val hasChanges =
    draft.minFreqHz != policy.minFreqHz ||
      draft.maxFreqHz != policy.maxFreqHz ||
      draft.governor != policy.governor ||
      draft.defaultPowerLevel != policy.defaultPowerLevel
  val isFrequencyValid = draft.minFreqHz <= draft.maxFreqHz
  val isDefaultPowerLevelValid = isDefaultPowerLevelSelectionValid(policy, draft, powerLevelOptions)
  val isValid = isFrequencyValid && isDefaultPowerLevelValid
  val selectableFrequencyRange = policy.availableFreqsHz.firstOrNull()?.let { minSelectableFreq -> minSelectableFreq to policy.availableFreqsHz.last() }

  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(text = policy.name, style = MaterialTheme.typography.titleLarge)
      selectableFrequencyRange?.let { (minSelectableFreq, maxSelectableFreq) ->
        InfoRow(label = "Selectable freq range", value = formatRangeHz(minSelectableFreq, maxSelectableFreq))
      }
      InfoRow(label = "Applied freq limits", value = formatRangeHz(policy.minFreqHz, policy.maxFreqHz))
      InfoRow(label = "Current freq", value = formatHz(currentFreqHz))
      InfoRow(label = "Applied governor", value = policy.governor ?: "Unavailable")

      if (policy.maxPowerLevel != null || policy.minPowerLevel != null) {
        InfoRow(label = "Kernel power window", value = formatPowerLevelWindow(policy.maxPowerLevel, policy.minPowerLevel))
      }
      policy.defaultPowerLevel?.let { defaultPowerLevel ->
        InfoRow(label = "Applied default pwrlevel", value = formatPowerLevel(defaultPowerLevel))
      }
      if (policy.defaultPowerLevel != null || policy.maxPowerLevel != null || policy.minPowerLevel != null) {
        Text(
          text = "Lower power level numbers are usually faster on KGSL kernels. Min/max pwrlevels are read-only for now.",
          style = MaterialTheme.typography.bodySmall,
        )
      }

      if (hasSelectableGovernors) {
        GovernorSelector(selectedGovernor = draft.governor.orEmpty(), options = policy.availableGovernors, onSelected = onGovernorSelected)
      } else if (!policy.governor.isNullOrBlank()) {
        Text(
          text = "GPU governor control unavailable. Reason: kernel did not expose selectable GPU governors for this policy.",
          style = MaterialTheme.typography.bodySmall,
        )
      }

      if (hasSelectableFrequencies) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
          GpuFrequencySelector(
            label = "Min",
            selectedHz = draft.minFreqHz,
            options = policy.availableFreqsHz,
            onSelected = onMinSelected,
            modifier = Modifier.weight(1f),
          )
          GpuFrequencySelector(
            label = "Max",
            selectedHz = draft.maxFreqHz,
            options = policy.availableFreqsHz,
            onSelected = onMaxSelected,
            modifier = Modifier.weight(1f),
          )
        }
      } else {
        Text(
          text = "Read-only in KernelMan. Reason: kernel did not expose selectable GPU frequencies for this policy.",
          style = MaterialTheme.typography.bodySmall,
        )
      }

      if (hasSelectableDefaultPowerLevel) {
        PowerLevelSelector(
          label = "Default pwrlevel",
          selectedLevel = draft.defaultPowerLevel ?: policy.defaultPowerLevel,
          options = powerLevelOptions,
          onSelected = onDefaultPowerLevelSelected,
          modifier = Modifier.fillMaxWidth(),
        )
      }

      if (!isFrequencyValid) {
        Text(text = "GPU min frequency must be less than or equal to max.", color = MaterialTheme.colorScheme.error)
      }
      if (!isDefaultPowerLevelValid) {
        Text(text = "GPU default pwrlevel must stay within the kernel pwrlevel window.", color = MaterialTheme.colorScheme.error)
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

private fun selectableDefaultPowerLevels(policy: GpuPolicy): List<Int> {
  val lowerBound = policy.maxPowerLevel ?: 0
  val upperBound =
    when {
      policy.minPowerLevel != null -> policy.minPowerLevel
      policy.numPowerLevels != null -> policy.numPowerLevels - 1
      else -> return emptyList()
    }

  if (lowerBound < 0 || upperBound < lowerBound) return emptyList()
  if (policy.numPowerLevels != null && upperBound >= policy.numPowerLevels) return emptyList()
  return (lowerBound..upperBound).toList()
}

private fun isDefaultPowerLevelSelectionValid(policy: GpuPolicy, draft: GpuPolicyDraft, powerLevelOptions: List<Int>): Boolean {
  val defaultPowerLevel = draft.defaultPowerLevel ?: return true
  if (defaultPowerLevel == policy.defaultPowerLevel) return true
  if (powerLevelOptions.isNotEmpty()) return defaultPowerLevel in powerLevelOptions
  if (policy.numPowerLevels != null && defaultPowerLevel !in 0 until policy.numPowerLevels) return false
  if (policy.maxPowerLevel != null && defaultPowerLevel < policy.maxPowerLevel) return false
  if (policy.minPowerLevel != null && defaultPowerLevel > policy.minPowerLevel) return false
  return true
}

@Composable
private fun GpuFrequencySelector(
  label: String,
  selectedHz: Long,
  options: List<Long>,
  onSelected: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  Box(modifier = modifier) {
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = formatHz(selectedHz), style = MaterialTheme.typography.bodyMedium)
      }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { freqHz ->
        DropdownMenuItem(
          text = { Text(text = formatHz(freqHz)) },
          onClick = {
            expanded = false
            onSelected(freqHz)
          },
        )
      }
    }
  }
}

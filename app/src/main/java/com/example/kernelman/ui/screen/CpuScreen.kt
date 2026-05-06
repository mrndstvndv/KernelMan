package com.example.kernelman.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kernelman.cpu.CpuError
import com.example.kernelman.cpu.CpuPolicy
import com.example.kernelman.ui.component.CpuPolicyCard
import com.example.kernelman.ui.component.ErrorCard
import com.example.kernelman.ui.theme.MyApplicationTheme

@Composable
fun CpuScreen(modifier: Modifier = Modifier, viewModel: CpuViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  CpuScreen(
    state = state,
    onMinSelected = viewModel::updateMin,
    onMaxSelected = viewModel::updateMax,
    onGovernorSelected = viewModel::updateGovernor,
    onSave = viewModel::savePolicy,
    modifier = modifier,
  )
}

@Composable
internal fun CpuScreen(
  state: CpuScreenState,
  onMinSelected: (String, Long) -> Unit,
  onMaxSelected: (String, Long) -> Unit,
  onGovernorSelected: (String, String) -> Unit,
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
        Text(text = "Policy-based CPU frequency and governor controls.", style = MaterialTheme.typography.bodyMedium)
      }
    }

    state.error?.let { error ->
      item { ErrorCard(error) }
    }

    if (state.policies.isEmpty()) {
      item { Text(text = "No CPU policies found.") }
    }

    items(items = state.policies, key = { it.name }) { policy ->
      val draft = state.drafts[policy.name] ?: CpuPolicyDraft(policy.scalingMinFreqKhz, policy.scalingMaxFreqKhz, policy.governor)
      CpuPolicyCard(
        policy = policy,
        draft = draft,
        currentFreqKhz = state.currentFreqsKhz[policy.name] ?: policy.scalingCurFreqKhz,
        isSaving = state.savingPolicyName == policy.name,
        onMinSelected = { onMinSelected(policy.name, it) },
        onMaxSelected = { onMaxSelected(policy.name, it) },
        onGovernorSelected = { onGovernorSelected(policy.name, it) },
        onSave = { onSave(policy.name) },
      )
    }
  }
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

@Preview(showBackground = true)
@Composable
private fun CpuScreenPreview() {
  MyApplicationTheme {
    CpuScreen(
      state =
        CpuScreenState(
          policies = previewPolicies,
          drafts = mapOf("policy0" to CpuPolicyDraft(652_800, 1_555_200, "powersave")),
        ),
      onMinSelected = { _, _ -> },
      onMaxSelected = { _, _ -> },
      onGovernorSelected = { _, _ -> },
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
      onGovernorSelected = { _, _ -> },
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
          policies = listOf(previewPolicies.first().copy(availableFreqsKhz = emptyList(), availableGovernors = emptyList())),
        ),
      onMinSelected = { _, _ -> },
      onMaxSelected = { _, _ -> },
      onGovernorSelected = { _, _ -> },
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
      onGovernorSelected = { _, _ -> },
      onSave = {},
      modifier = Modifier.padding(16.dp),
    )
  }
}

package com.example.kernelman.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kernelman.cpu.CpuError
import java.util.Locale

@Composable
fun ErrorCard(error: CpuError, modifier: Modifier = Modifier) {
  Card(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(text = "Error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
      Text(text = error.summary, color = MaterialTheme.colorScheme.error)
    }
  }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(text = label, style = MaterialTheme.typography.labelMedium)
    Text(text = value, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
fun GovernorSelector(selectedGovernor: String, options: List<String>, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
  var expanded by remember { mutableStateOf(false) }

  Box(modifier = modifier.fillMaxWidth()) {
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = "Governor", style = MaterialTheme.typography.labelSmall)
        Text(text = selectedGovernor, style = MaterialTheme.typography.bodyMedium)
      }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { governor ->
        DropdownMenuItem(
          text = { Text(text = governor) },
          onClick = {
            expanded = false
            onSelected(governor)
          },
        )
      }
    }
  }
}

@Composable
fun FrequencySelector(
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

fun formatRange(minFreqKhz: Long, maxFreqKhz: Long) = "${formatKhz(minFreqKhz)} → ${formatKhz(maxFreqKhz)}"

fun formatKhz(freqKhz: Long?): String {
  if (freqKhz == null) return "Unavailable"
  if (freqKhz >= 1_000_000L) return String.format(Locale.US, "%.2f GHz", freqKhz / 1_000_000f)
  return String.format(Locale.US, "%.0f MHz", freqKhz / 1_000f)
}

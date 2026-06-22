package com.example.kernelman.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.kernelman.ui.screen.CpuScreen
import com.example.kernelman.ui.screen.ProfilesListScreen
import com.example.kernelman.ui.screen.SettingsScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          ProfilesListScreen(
            onOpenSettings = { backStack.add(Settings) },
            onAddProfile = { backStack.add(ConfigureProfile(profileId = null)) },
            onEditProfile = { profileId -> backStack.add(ConfigureProfile(profileId = profileId)) },
            modifier = Modifier.fillMaxSize(),
          )
        }
        entry<ConfigureProfile> { key ->
          CpuScreen(
            profileId = key.profileId,
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.fillMaxSize(),
          )
        }
        entry<Settings> {
          SettingsScreen(
            onBack = { backStack.removeLastOrNull() },
            modifier = Modifier.fillMaxSize(),
          )
        }
      },
  )
}

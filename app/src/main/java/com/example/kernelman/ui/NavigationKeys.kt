package com.example.kernelman.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

@Serializable data object Settings : NavKey

@Serializable data class ConfigureProfile(val profileId: String? = null) : NavKey


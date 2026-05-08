package com.example.kernelman.profile

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

private const val DataStoreName = "cpu_profiles"
private val Context.cpuProfileDataStore by preferencesDataStore(name = DataStoreName)

class CpuProfileRepository(private val context: Context) {
  private companion object {
    const val tag = "CpuProfileRepository"
    val profilesJsonKey = stringPreferencesKey("cpu_profiles_json")
    val lastAppliedProfileIdKey = stringPreferencesKey("last_applied_profile_id")
    val lastAppliedAtEpochMsKey = longPreferencesKey("last_applied_at_epoch_ms")
  }

  private val json = Json { ignoreUnknownKeys = true }

  val state: Flow<CpuProfileState> =
    context.cpuProfileDataStore.data
      .catch { throwable ->
        if (throwable is IOException) {
          Log.e(tag, "state read failed", throwable)
          emit(emptyPreferences())
          return@catch
        }
        throw throwable
      }.map(::toState)

  suspend fun createProfile(name: String, snapshot: List<CpuProfilePolicy>): CpuProfile {
    require(snapshot.isNotEmpty()) { "Profile must contain at least one CPU policy." }

    var createdProfile: CpuProfile? = null
    context.cpuProfileDataStore.edit { preferences ->
      val store = readStore(preferences)
      val validatedName = validateName(store.profiles, name)
      val now = System.currentTimeMillis()
      val profile =
        CpuProfile(
          id = UUID.randomUUID().toString(),
          name = validatedName,
          createdAtEpochMs = now,
          updatedAtEpochMs = now,
          policies = snapshot,
        )

      writeStore(preferences, store.copy(profiles = store.profiles + profile))
      createdProfile = profile
    }

    return createdProfile ?: error("Failed to create CPU profile")
  }

  suspend fun updateProfile(profileId: String, snapshot: List<CpuProfilePolicy>) {
    require(snapshot.isNotEmpty()) { "Profile must contain at least one CPU policy." }

    context.cpuProfileDataStore.edit { preferences ->
      val store = readStore(preferences)
      val index = store.profiles.indexOfFirst { it.id == profileId }
      if (index == -1) throw IllegalArgumentException("Profile not found")

      val currentProfile = store.profiles[index]
      val updatedProfile =
        currentProfile.copy(
          updatedAtEpochMs = System.currentTimeMillis(),
          policies = snapshot,
        )

      writeStore(preferences, store.copy(profiles = store.profiles.toMutableList().apply { set(index, updatedProfile) }))
    }
  }

  suspend fun renameProfile(profileId: String, name: String) {
    context.cpuProfileDataStore.edit { preferences ->
      val store = readStore(preferences)
      val index = store.profiles.indexOfFirst { it.id == profileId }
      if (index == -1) throw IllegalArgumentException("Profile not found")

      val validatedName = validateName(store.profiles, name, profileId)
      val currentProfile = store.profiles[index]
      val renamedProfile = currentProfile.copy(name = validatedName, updatedAtEpochMs = System.currentTimeMillis())

      writeStore(preferences, store.copy(profiles = store.profiles.toMutableList().apply { set(index, renamedProfile) }))
    }
  }

  suspend fun deleteProfile(profileId: String) {
    context.cpuProfileDataStore.edit { preferences ->
      val store = readStore(preferences)
      val updatedProfiles = store.profiles.filterNot { it.id == profileId }
      if (updatedProfiles.size == store.profiles.size) throw IllegalArgumentException("Profile not found")

      writeStore(preferences, store.copy(profiles = updatedProfiles))
      if (preferences[lastAppliedProfileIdKey] == profileId) {
        preferences.remove(lastAppliedProfileIdKey)
        preferences.remove(lastAppliedAtEpochMsKey)
      }
    }
  }

  suspend fun setLastApplied(profileId: String, appliedAtEpochMs: Long) {
    context.cpuProfileDataStore.edit { preferences ->
      val store = readStore(preferences)
      if (store.profiles.none { it.id == profileId }) throw IllegalArgumentException("Profile not found")

      preferences[lastAppliedProfileIdKey] = profileId
      preferences[lastAppliedAtEpochMsKey] = appliedAtEpochMs
    }
  }

  private fun toState(preferences: Preferences): CpuProfileState {
    val store = readStore(preferences)
    return CpuProfileState(
      profiles = store.profiles.sortedByDescending(CpuProfile::updatedAtEpochMs),
      lastAppliedProfileId = preferences[lastAppliedProfileIdKey],
      lastAppliedAtEpochMs = preferences[lastAppliedAtEpochMsKey],
    )
  }

  private fun readStore(preferences: Preferences): CpuProfilesStore {
    val rawStore = preferences[profilesJsonKey].orEmpty()
    if (rawStore.isBlank()) return CpuProfilesStore()

    return runCatching { json.decodeFromString<CpuProfilesStore>(rawStore) }
      .onFailure { throwable -> Log.e(tag, "Failed to decode stored profiles", throwable) }
      .getOrDefault(CpuProfilesStore())
  }

  private fun writeStore(preferences: MutablePreferences, store: CpuProfilesStore) {
    preferences[profilesJsonKey] = json.encodeToString(store)
  }

  private fun validateName(profiles: List<CpuProfile>, name: String, excludedProfileId: String? = null): String {
    val trimmedName = name.trim()
    if (trimmedName.isBlank()) throw IllegalArgumentException("Profile name is required")
    if (trimmedName.length > 40) throw IllegalArgumentException("Profile name must be 40 characters or less")

    val duplicateProfile =
      profiles.firstOrNull { profile ->
        profile.id != excludedProfileId && profile.name.equals(trimmedName, ignoreCase = true)
      }
    if (duplicateProfile != null) throw IllegalArgumentException("Profile name already exists")

    return trimmedName
  }
}

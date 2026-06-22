package com.example.kernelman.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBootSettingsTest {
  @Test
  fun `clampProfileBootDelaySeconds clamps into supported range`() {
    assertEquals(0, clampProfileBootDelaySeconds(-5))
    assertEquals(15, clampProfileBootDelaySeconds(15))
    assertEquals(300, clampProfileBootDelaySeconds(999))
  }

  @Test
  fun `resolveBootProfile returns last applied profile`() {
    val gamingProfile = testProfile(id = "gaming", name = "Gaming")
    val state =
      CpuProfileState(
        profiles = listOf(gamingProfile),
        lastAppliedProfileId = gamingProfile.id,
        bootSettings = ProfileBootSettings(enabled = true, mode = ProfileBootMode.LAST_APPLIED),
      )

    val resolution = resolveBootProfile(state)

    assertTrue(resolution is ResolvedBootProfile.Profile)
    val resolvedProfile = (resolution as ResolvedBootProfile.Profile).profile
    assertEquals(gamingProfile.id, resolvedProfile.id)
  }

  @Test
  fun `resolveBootProfile skips when last applied profile is missing`() {
    val state =
      CpuProfileState(
        profiles = emptyList(),
        lastAppliedProfileId = "missing",
        bootSettings = ProfileBootSettings(enabled = true, mode = ProfileBootMode.LAST_APPLIED),
      )

    val resolution = resolveBootProfile(state)

    assertTrue(resolution is ResolvedBootProfile.Skipped)
    assertEquals("The last applied profile no longer exists.", (resolution as ResolvedBootProfile.Skipped).message)
  }

  @Test
  fun `resolveBootProfile returns specific profile`() {
    val batteryProfile = testProfile(id = "battery", name = "Battery")
    val state =
      CpuProfileState(
        profiles = listOf(batteryProfile),
        bootSettings =
          ProfileBootSettings(
            enabled = true,
            mode = ProfileBootMode.SPECIFIC_PROFILE,
            specificProfileId = batteryProfile.id,
          ),
      )

    val resolution = resolveBootProfile(state)

    assertTrue(resolution is ResolvedBootProfile.Profile)
    val resolvedProfile = (resolution as ResolvedBootProfile.Profile).profile
    assertEquals(batteryProfile.id, resolvedProfile.id)
  }

  @Test
  fun `resolveBootProfile skips when boot apply is disabled`() {
    val state = CpuProfileState(bootSettings = ProfileBootSettings(enabled = false))

    val resolution = resolveBootProfile(state)

    assertTrue(resolution is ResolvedBootProfile.Skipped)
    assertEquals("Boot apply is disabled.", (resolution as ResolvedBootProfile.Skipped).message)
  }

  private fun testProfile(id: String, name: String) =
    CpuProfile(
      id = id,
      name = name,
      createdAtEpochMs = 0,
      updatedAtEpochMs = 0,
    )
}

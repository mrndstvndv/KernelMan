# Plan: Apply profile on boot

## Goal

Add opt-in support for applying a saved CPU/GPU profile after reboot. Users can choose either a fixed saved profile or the dynamic **Last applied profile** target, and configure how long KernelMan should wait after boot before applying it.

Compose note: this repo currently uses standard `androidx.compose.material3`, not Wear Compose Material3. Keep this feature on the existing UI stack unless the app is intentionally migrated to Wear UI.

## Current state

- Profiles are persisted by `CpuProfileRepository` in Preferences DataStore.
- Last applied profile is already tracked with `lastAppliedProfileId` / `lastAppliedAtEpochMs`.
- Profile application logic currently lives inside `CpuViewModel.applyProfile(...)`, so boot code cannot reuse it yet.
- Navigation3 is already present with one route: `Main` -> `CpuScreen`.

## UX

Add a Settings screen reachable from the main screen header.

Settings content:

1. **Apply profile after reboot** switch
   - Default: off.
   - Show helper text: requires persistent root permission and may run shortly after Android finishes booting.
2. **Delay before applying** field, enabled only when the switch is on.
   - Default: 15 seconds.
   - Use a custom seconds field backed by validation and clamping.
   - MVP range: `0..300` seconds.
   - Helper text: longer delays help on devices where root or sysfs nodes are not ready immediately after boot.
3. **Boot profile target** selector, enabled only when the switch is on.
   - **Last applied profile**
     - Stores a mode, not a profile id.
     - At boot, resolves to whatever `lastAppliedProfileId` is at that time.
     - If no profile has been applied yet, show a warning and boot apply no-ops.
   - **Specific profile**
     - User chooses from saved profiles.
     - Show the selected profile name and summary.
4. **Status / diagnostics**
   - Optional but useful: show last boot apply result, timestamp, and error message if a previous boot apply failed.

Main screen changes:

- Add a `Settings` button next to `Profiles` in the header.
- Keep profile management in the existing sheet.

## Data model

Extend profile persistence with boot settings, preferably in the existing `cpu_profiles` DataStore.

Proposed models:

- `ProfileBootMode`
  - `LastApplied`
  - `SpecificProfile`
- `ProfileBootSettings`
  - `enabled: Boolean = false`
  - `delaySeconds: Int = 15`
  - `mode: ProfileBootMode = LastApplied`
  - `specificProfileId: String? = null`
- Optional `ProfileBootApplyStatus`
  - `lastAttemptAtEpochMs: Long?`
  - `lastResult: Success | Failed | Skipped | null`
  - `lastMessage: String?`

Repository changes:

- Add boot settings keys:
  - `boot_apply_enabled`
  - `boot_apply_delay_seconds`
  - `boot_profile_mode`
  - `boot_profile_id`
  - optional last-result keys
- Add boot settings to `CpuProfileState` so both UI and worker can read one state stream.
- Add update methods:
  - `setBootApplyEnabled(enabled: Boolean)`
  - `setBootApplyDelaySeconds(seconds: Int)`
  - `setBootProfileMode(mode: ProfileBootMode)`
  - `setBootSpecificProfile(profileId: String)`
  - optional `setBootApplyStatus(...)`
- Validate and clamp delay to a small safe range, e.g. `0..300` seconds.
- On profile delete:
  - If deleting the selected specific boot profile, disable boot apply and clear `boot_profile_id` to avoid applying an unintended profile.
  - Existing last-applied cleanup can remain as-is.

## Shared profile applier

Extract profile application from `CpuViewModel` into a reusable class, for example `KernelProfileApplier`.

Responsibilities:

1. Load current CPU policies only if the profile has CPU settings.
2. Load current GPU policies only if the profile has GPU settings.
3. Run `findProfileCompatibilityIssue(...)`.
4. Apply CPU policies through `CpuPolicyApi.applyPolicy(...)`.
5. Apply GPU policies through `GpuPolicyApi.applyPolicy(...)`.
6. Record `setLastApplied(profile.id, now)` on success.

Then update `CpuViewModel` to call this shared applier instead of keeping duplicate logic.

## Boot pipeline

Use a receiver + WorkManager so boot work is not performed directly inside a `BroadcastReceiver`.

1. Add dependency:
   - `androidx.work:work-runtime-ktx`
2. Add manifest permission:
   - `android.permission.RECEIVE_BOOT_COMPLETED`
3. Add `BootCompletedReceiver`:
   - listens for `android.intent.action.BOOT_COMPLETED`
   - uses `goAsync()` + coroutine to read boot settings once
   - if boot apply is disabled: exit without enqueuing work
   - enqueues unique work, e.g. `apply-profile-on-boot`
   - sets `setInitialDelay(bootDelaySeconds, TimeUnit.SECONDS)` from settings
   - uses `ExistingWorkPolicy.REPLACE`; repeated boot/package events collapse to one latest request
4. Add `ApplyBootProfileWorker : CoroutineWorker`:
   - read `CpuProfileRepository.state.first()`
   - if disabled: return success / skipped
   - resolve target:
     - `LastApplied` -> `lastAppliedProfileId`
     - `SpecificProfile` -> `specificProfileId`
   - if no resolvable profile: record skipped and return success
   - call `KernelProfileApplier.apply(profile)`
   - retry transient boot-time failures a limited number of times

Retry guidance:

- Retry likely transient failures such as root unavailable immediately after boot or missing sysfs nodes during early boot.
- Do not retry validation failures, missing profiles, or compatibility issues.
- Cap retries with `runAttemptCount` to avoid indefinite background attempts.

Platform notes:

- `BOOT_COMPLETED` generally runs after user unlock for credential-encrypted app data. Avoid `LOCKED_BOOT_COMPLETED` for the MVP unless the DataStore is moved to device-protected storage.
- If the user force-stops the app, Android will not deliver boot broadcasts until the app is launched again.
- Boot apply requires the root manager to have granted KernelMan persistent/background `su` access before reboot.

## Settings screen implementation

Files to add or update:

- `ui/NavigationKeys.kt`
  - add `@Serializable data object Settings : NavKey`
- `ui/Navigation.kt`
  - add `entry<Settings> { SettingsScreen(...) }`
  - pass `onOpenSettings` into `CpuScreen`
  - pass `onBack` into `SettingsScreen`
- `ui/screen/SettingsScreen.kt`
  - Material3 `Scaffold`
  - `LazyColumn` with switch row, delay row, target rows, selected profile picker, status card
- `ui/screen/SettingsViewModel.kt`
  - observes `CpuProfileRepository.state`
  - exposes profiles, last applied profile, boot settings, status, and delay validation state
  - updates repository on user changes
- `ui/screen/CpuScreen.kt`
  - add Settings button callback

Selection behavior:

- Turning on boot apply with no profiles should either be disabled or show a clear warning.
- Changing the delay should persist immediately; turning boot apply off should keep the chosen delay for later reuse.
- Choosing **Specific profile** with no selected profile should auto-select the first available profile.
- Choosing **Last applied profile** should not require a currently applied profile, but should show that boot apply will no-op until one exists.

## Validation

Automated checks:

- Repository tests:
  - defaults are disabled / `15s` / last-applied mode
  - enabling, changing delay, and changing target persist
  - delay is clamped to the supported range
  - deleting selected specific profile disables boot apply
  - deleting last-applied profile clears last-applied id
- Resolver tests:
  - disabled -> skipped
  - last applied missing -> skipped
  - specific profile missing -> skipped or disables setting
  - compatible profile -> calls applier
  - compatibility issue -> failure without retry
- Worker tests for disabled, skipped, success, retryable failure, terminal failure.
- Compose previews for Settings screen states.

Manual checks:

1. Create profile A and profile B.
2. Enable boot apply -> set delay -> Last applied.
3. Apply profile A, reboot, confirm KernelMan waits roughly the configured delay, then restores profile A values.
4. Apply profile B, reboot, confirm profile B values are restored.
5. Switch to Specific profile A, reboot, confirm A is restored even if B was last applied.
6. Change delay to another value, reboot, confirm the new timing is used.
7. Delete selected specific profile, confirm boot apply is disabled.
8. Reboot without root/background `su` grant, confirm failure is recorded clearly.

## Suggested implementation order

1. Add boot settings models and repository methods.
2. Extract `KernelProfileApplier` and update `CpuViewModel` to use it.
3. Add Worker dependency, receiver, worker, and manifest entries.
4. Add Settings NavKey, Settings screen, and Settings ViewModel.
5. Add main-screen Settings button.
6. Add tests and manual reboot verification.

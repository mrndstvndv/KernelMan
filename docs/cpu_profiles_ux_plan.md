# KernelMan CPU profiles UX plan

## Goal
Let users save named CPU setups, then reapply them later in one action.

This is a UX plan only. No implementation decisions here are final until reviewed.

## What a profile is
A profile is a local snapshot of all CPU policies currently shown on the CPU screen.

Each saved profile should capture:
- policy name
- selected min frequency
- selected max frequency
- selected governor
- profile name
- created/updated time

V1 assumptions:
- creating a profile does **not** write anything to sysfs
- applying a profile writes all saved policies for that profile
- profiles are **full snapshots**, not partial overrides
- no apply-on-boot yet
- no automatic switching yet

## UX principles
- Keep the CPU screen primary.
- Profile actions should be one hop away.
- Make `save profile` and `apply to device` feel clearly different.
- Do not infer a true active profile from live CPU reads.
- Persist app-owned profile state instead.
- Fail clearly when a saved profile no longer matches the current kernel options.
- Let users save current drafts without forcing an immediate apply.

## State language
Use these terms consistently:
- **Current CPU values**: values currently read from the kernel
- **Draft edits**: unsaved selections on the CPU screen
- **Saved profile**: named snapshot stored by the app
- **Last applied profile**: the last profile this app applied and persisted locally

Recommendation:
- do not use `Active profile` in v1
- we cannot reliably prove exact live CPU equivalence from the current kernel behavior
- persist `lastAppliedProfileId` instead
- label it as `Last applied`, not `Active`
- treat it as app state, not a kernel guarantee

## Persistence recommendation
Based on current Android guidance, use **Jetpack DataStore** for new small local state instead of `SharedPreferences`.

Best v1 fit:
- **Preferences DataStore**
- key-value style API
- small diff
- easy way to persist a few profile-related values

Suggested stored values:
- `profiles_json`
- `last_applied_profile_id`
- `last_applied_profile_name` (optional convenience)
- `last_applied_at`

Why this fits:
- profile count should stay small
- we only need simple load/save, not database-style queries
- DataStore is asynchronous and transactional
- the official Android docs recommend DataStore over SharedPreferences for new work

If we want stronger typing later:
- move the profile blob to Proto DataStore or custom-serialized DataStore

If profiles become query-heavy or much larger:
- move to Room

## Recommended v1 shape
Keep the existing CPU screen.
Add one `Profiles` action to the header.
Open profile management in a modal bottom sheet.

Why a sheet:
- keeps the user anchored to the CPU screen
- easy mental model: profiles are a side task, not a separate mode
- simpler entry point than adding a whole new management screen
- easier to create a profile from current draft state

## CPU screen changes
Current header is effectively:

```text
CPU policies
Policy-based CPU frequency and governor controls.
```

Proposed header:

```text
CPU policies                          [Profiles]
Policy-based CPU frequency and governor controls.
Last applied: Gaming
```

Behavior:
- `Profiles` is visible once policies have loaded
- opening the profiles sheet does not discard current drafts
- if drafts exist, the sheet should make it clear that `Create from current` includes those drafts
- if a profile was applied from the app before, show `Last applied: {name}` below the supporting text
- `Last applied` comes from persisted app state, not live kernel verification

Helper text when drafts exist:

```text
Unsaved CPU edits can be saved as a profile without applying them.
```

## Profiles sheet
Open from the CPU screen header.

### Sheet header
- title: `Profiles`
- supporting text: `Save CPU settings and reapply them later.`
- primary action button: `Create from current`

### Empty state
If no profiles exist:

```text
No profiles yet
Save your current CPU settings as reusable profiles.
[Create from current]
```

### List state
Sort profiles by `updatedAt desc` so the newest or recently edited ones stay near the top.

Each row should show:
- profile name
- optional `Last applied` badge if `profile.id == lastAppliedProfileId`
- secondary text: `Updated ...` and policy count
- compact per-policy summary
- `Apply` action
- overflow menu with `Update from current`, `Rename`, and `Delete`

Example row:

```text
Gaming   [Last applied]
Updated 2m ago • 2 policies
policy0 · 652.8 MHz–1.56 GHz · schedutil
policy4 · 1.25 GHz–2.21 GHz · performance
[Apply]   [⋮]
```

Why show compact per-policy lines:
- users need a quick way to distinguish similar profiles
- `2 policies` alone is not enough context
- this avoids forcing a separate detail screen in v1

## Create profile flow
Triggered by `Create from current` in the sheet.

Use a simple dialog.

### Dialog fields
- title: `Create profile`
- one text field: `Profile name`
- helper text:
  - if drafts exist: `Saves the current screen selections, including unsaved edits.`
  - if no drafts exist: `Saves the currently applied CPU values.`

### Validation
- required
- trim outer whitespace
- unique, case-insensitive
- max length: 40 characters
- disable `Create` until valid

### Success behavior
Recommendation:
- close the dialog
- keep the sheet open
- insert the new profile at the top
- show transient confirmation: `Profile saved`

Reason:
- user is already in profile management context
- lets them immediately review, rename, or apply if they want

### Important behavior
Creating a profile should snapshot the **effective current screen state**:
- use draft values where the user changed them
- use applied values for untouched policies

That lets users build a profile before pushing those values live.

## Update existing profile flow
From row overflow:
- action: `Update from current`
- available when at least one policy is loaded
- snapshots the same effective current screen state used by create

Confirmation dialog:

```text
Update profile?
Replace "Gaming" with the current screen selections?
[Cancel] [Update]
```

Behavior:
- keeps the same profile id
- updates only the stored snapshot and `updatedAt`
- does **not** apply values to the kernel
- does **not** change `lastAppliedProfileId`
- success message: `Profile updated`

## Apply profile flow
From a profile row, tap `Apply`.

### Before apply
If the CPU screen has unsaved drafts, show confirmation:

```text
Apply profile?
This will discard your unsaved CPU edits and apply the saved profile values.
[Cancel] [Apply profile]
```

If there are no drafts, apply immediately.

### During apply
- show inline progress on the selected profile row
- disable other profile actions while apply is running
- keep the rest of the CPU screen unchanged until apply finishes

### Success
- persist `lastAppliedProfileId`, and optionally name/timestamp
- close the sheet
- refresh live CPU values from the kernel
- clear drafts on the CPU screen
- show snackbar: `Gaming applied`

### Failure
- keep the sheet open
- show a clear error message
- refresh CPU values so the UI reflects real kernel state

Recommendation:
- do not silently continue after a per-policy failure
- fail fast and show the first clear reason

## Rename flow
From row overflow:
- action: `Rename`
- dialog with prefilled name
- same validation rules as create
- success message: `Profile renamed`

## Delete flow
From row overflow:
- action: `Delete`
- confirmation dialog
- destructive copy should include the profile name

Example:

```text
Delete profile?
Delete "Gaming"? This does not change the current CPU values.
[Cancel] [Delete]
```

Success message:
- `Profile deleted`

Important:
- deleting a profile never changes current live CPU state

## Compatibility and invalid profiles
Saved profiles can become invalid if the kernel changes, a governor disappears, or frequency lists differ.

V1 should surface that in the list instead of failing only after tap.

### Invalid row state
If a profile no longer matches the current device state:
- show warning icon/state
- disable `Apply`
- show short reason inline

Example:

```text
Battery saver
Needs review
Governor "powersave" is no longer available for policy4.
[Apply disabled]   [⋮]
```

Reason priority:
1. missing policy
2. missing governor
3. missing frequency value

This keeps the user from guessing why apply failed.

## Copy recommendations
Use direct language.

Suggested strings:
- `Profiles`
- `Create from current`
- `Profile saved`
- `Apply profile?`
- `Last applied`
- `This will discard your unsaved CPU edits and apply the saved profile values.`
- `Delete "{name}"? This does not change the current CPU values.`
- `Needs review`
- `No profiles yet`

## Wireframe sketches

### CPU screen header

```text
CPU policies                          [Profiles]
Policy-based CPU frequency and governor controls.
Last applied: Gaming
```

### Profiles sheet

```text
┌──────────────────────────────────────┐
│ Profiles                             │
│ Save CPU settings and reapply later. │
│ [Create from current]                │
│                                      │
│ Gaming   [Last applied] [Apply] [⋮] │
│ Updated 2m ago • 2 policies          │
│ policy0 · 652.8–1555.2 MHz · schedutil│
│ policy4 · 1.25–2.21 GHz · performance│
│                                      │
│ Battery saver           [Apply]  [⋮] │
│ Updated yesterday • 2 policies       │
│ policy0 · 300–940.8 MHz · powersave  │
│ policy4 · 710.4–1.55 GHz · powersave │
└──────────────────────────────────────┘
```

### Create dialog

```text
Create profile
[ Profile name                         ]
Saves the current screen selections, including unsaved edits.

[Cancel] [Create]
```

## Technical implementation notes

### Persistence layer
Use a singleton **Preferences DataStore**.

Recommended keys:
- `stringPreferencesKey("cpu_profiles_json")`
- `stringPreferencesKey("last_applied_profile_id")`
- `longPreferencesKey("last_applied_at_epoch_ms")`

V1 persistence strategy:
- store the profile collection as one JSON blob
- store `lastAppliedProfileId` separately
- store `lastAppliedAt` separately
- use `kotlinx.serialization` for encode/decode
- do not use `SharedPreferences` for new profile storage

Suggested serialized models:

```kotlin
@Serializable
data class CpuProfilesStore(
  val profiles: List<CpuProfile> = emptyList(),
)

@Serializable
data class CpuProfile(
  val id: String,
  val name: String,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val policies: List<CpuProfilePolicy>,
)

@Serializable
data class CpuProfilePolicy(
  val policyName: String,
  val minFreqKhz: Long,
  val maxFreqKhz: Long,
  val governor: String,
)
```

ID recommendation:
- use a stable generated string id, e.g. UUID
- never use the profile name as the primary identifier

### Repository responsibilities
Keep profile persistence behind one small repository.

Suggested shape:

```kotlin
interface CpuProfileRepository {
  val profiles: Flow<List<CpuProfile>>
  val lastAppliedProfileId: Flow<String?>

  suspend fun createProfile(name: String, snapshot: List<CpuProfilePolicy>): CpuProfile
  suspend fun updateProfile(profileId: String, snapshot: List<CpuProfilePolicy>)
  suspend fun renameProfile(profileId: String, name: String)
  suspend fun deleteProfile(profileId: String)
  suspend fun setLastApplied(profileId: String, appliedAtEpochMs: Long)
}
```

Rules:
- validate names case-insensitively before writes
- update `updatedAtEpochMs` on rename and update
- keep writes atomic through one DataStore edit block
- expose flows so the screen stays reactive

### Snapshot builder
Use one pure function to build a profile snapshot from the current screen state.

Suggested shape:

```kotlin
fun buildProfileSnapshot(
  policies: List<CpuPolicy>,
  drafts: Map<String, CpuPolicyDraft>,
): List<CpuProfilePolicy>
```

Rules:
- if a policy has a draft, use it
- otherwise use the currently applied policy values
- include every visible policy
- sort by policy index order for stable serialization and UI output

### Apply algorithm
Apply should reuse the existing CPU API, not create a second write path.

Suggested order:
1. load current policies from `CpuPolicyApi.loadPolicies()`
2. validate the saved profile against current policy names, governors, and frequencies
3. for each saved policy, find the matching current `CpuPolicy`
4. call `CpuPolicyApi.applyPolicy(currentPolicy, saved.minFreqKhz, saved.maxFreqKhz, saved.governor)`
5. if all policies succeed, persist `lastAppliedProfileId` and `lastAppliedAt`
6. refresh policies and clear drafts
7. if any policy fails, stop immediately and do **not** update `lastAppliedProfileId`

### Update/overwrite algorithm
Updating an existing profile should reuse the same snapshot builder as create.

Suggested order:
1. build the current snapshot from `policies + drafts`
2. write the new snapshot into the existing profile id
3. update `updatedAtEpochMs`
4. keep `createdAtEpochMs` unchanged
5. do not touch live kernel state
6. do not change `lastAppliedProfileId`

### Screen state additions
Keep one `CpuViewModel` for v1. Add only the profile state needed for the sheet.

Suggested additions:
- `profiles: List<CpuProfile>`
- `lastAppliedProfileId: String?`
- `isProfilesSheetVisible: Boolean`
- `profileDialogState: ProfileDialogState?`
- `profileActionInFlight: ProfileAction?`

Avoid many unrelated booleans. Prefer one sealed state for dialogs and one sealed state for in-flight profile work.

### Suggested files
Keep the diff small:
- `app/src/main/java/com/example/kernelman/profile/CpuProfileModels.kt`
- `app/src/main/java/com/example/kernelman/profile/CpuProfileRepository.kt`
- edit `app/src/main/java/com/example/kernelman/ui/screen/CpuViewModel.kt`
- edit `app/src/main/java/com/example/kernelman/ui/screen/CpuScreen.kt`
- optionally extract a small `ProfilesSheet.kt` only if `CpuScreen.kt` gets too large

## V1 decisions locked for implementation
- `Create from current` includes unsaved draft edits.
- Use a modal bottom sheet for profile management.
- Persist and display `Last applied`, not `Active`.
- Use Jetpack **Preferences DataStore** for v1 persistence.
- Include `Update from current` for existing profiles in v1.

## Not in v1
Deliberately exclude these for the first profile pass:
- apply-on-boot
- trigger-based auto switching
- import/export
- sharing profiles
- partial profiles
- folders/tags
- duplicate profile

## Recommended v1 implementation scope after UX sign-off
Smallest useful feature set:
1. create profile from current screen state
2. update an existing profile from current screen state
3. list saved profiles in a sheet
4. apply a profile
5. rename a profile
6. delete a profile
7. persist `lastAppliedProfileId` with Preferences DataStore
8. invalid-profile warning state

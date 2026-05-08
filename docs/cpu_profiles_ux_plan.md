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
- overflow menu with `Rename` and `Delete`

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

## Not in v1
Deliberately exclude these for the first profile pass:
- apply-on-boot
- trigger-based auto switching
- import/export
- sharing profiles
- partial profiles
- folders/tags
- duplicate profile
- overwrite existing profile from current values

## Recommended v1 implementation scope after UX sign-off
Smallest useful feature set:
1. create profile from current screen state
2. list saved profiles in a sheet
3. apply a profile
4. rename a profile
5. delete a profile
6. invalid-profile warning state

## Open questions for review
1. Should `Create from current` include unsaved draft edits? Recommendation: yes.
2. Do you want the persisted label to say `Last applied` or `Selected profile`?
3. Is a bottom sheet the right fit, or do you want a dedicated full screen for profile management?
4. Do you want `overwrite existing profile from current values` in v1, or can that wait until after basic create/apply/rename/delete lands?

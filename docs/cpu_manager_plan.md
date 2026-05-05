# KernelMan CPU manager plan

Based on:
- `docs/android_cpu_freq_notes.md`
- `docs/smartpack_cpu_freq_comparison.md`

## Goal
Build the first CPU page with the smallest reviewable code surface.

Primary target:
- Jetpack Compose UI
- policy-based cpufreq only: `/sys/devices/system/cpu/cpufreq/policy*`
- root writes to `scaling_min_freq` / `scaling_max_freq`

Deliberately not in scope yet:
- apply-on-boot
- governor editing
- per-core fallback paths
- SmartPack-style vendor compatibility nodes
- charts/history
- tabs for other kernel sections

## Why this shape
The notes recommend starting with policy directories as the main abstraction. That is the cleanest path and should keep the first version small.

The SmartPack comparison is still useful, but we should only borrow what we need right now:
- validate min/max before writing
- keep policy as the UI unit
- defer older compatibility fallbacks until we prove we need them

## Keep code small
Rules for the first implementation:
- one screen
- one ViewModel
- one small CPU policy API file
- one small root helper
- one card per policy
- one save button per policy
- store raw values as `Long` in kHz
- format only at the UI edge

That keeps the code easy to review and avoids over-architecture.

## UI shape
Use a simple `LazyColumn` of policy cards.

Each card shows:
- policy name: `policy0`, `policy4`, etc.
- supported range: `cpuinfo_min_freq` → `cpuinfo_max_freq`
- current applied range: `scaling_min_freq` → `scaling_max_freq`
- current live clock: `scaling_cur_freq`
- min dropdown: values from `scaling_available_frequencies`
- max dropdown: values from `scaling_available_frequencies`
- `Save` button

Behavior:
- `Save` is enabled only when the draft differs from the applied values
- `Save` is disabled when `draftMin > draftMax`
- if a policy does not expose `scaling_available_frequencies`, show it as read-only for now
- live clock can refresh every 1 second while the screen is visible

## State model
Use separate applied state and draft state so the 1 second refresh does not wipe user selections.

Suggested models:

```kotlin
data class CpuPolicy(
  val name: String,
  val cpuInfoMinFreqKhz: Long,
  val cpuInfoMaxFreqKhz: Long,
  val scalingMinFreqKhz: Long,
  val scalingMaxFreqKhz: Long,
  val scalingCurFreqKhz: Long?,
  val availableFreqsKhz: List<Long>,
)

data class CpuPolicyDraft(
  val minFreqKhz: Long,
  val maxFreqKhz: Long,
)
```

Screen state can be:
- loading
- error
- success with `policies + drafts + savingPolicyName?`

## Phase 1 — design the CPU interface only
Goal: finish the Compose screen without touching real sysfs logic.

### Work
1. Replace the placeholder content with a CPU screen.
2. Add a separate `CpuPolicyApi.kt` file as the small API for CPU policy work.
3. Keep `CpuPolicyApi.kt` focused on things like:
   - policy directory/path builders
   - listing policies
   - reading one policy snapshot
   - parsing `scaling_available_frequencies`
   - applying min/max for a policy
4. Keep phase 1 backed by fake/sample policy data. No real root/sysfs execution yet, but the API surface should be decided now so phase 2 is mostly wiring.
5. Make the card layout final enough that we can review spacing and information hierarchy.
6. Add dropdown UI and a disabled or no-op `Save` button.
7. Add previews for:
   - normal state
   - loading state
   - error state
   - missing frequency list state

### Minimal implementation notes
- keep one `CpuScreen.kt` file for most UI
- keep `CpuPolicyApi.kt` separate and focused on CPU policy helpers/API only
- use Material 3 only
- no custom components unless repeated at least twice
- use one formatter function for kHz → MHz/GHz text
- keep navigation unchanged except swapping the current main content to the CPU screen

### Done when
- app opens to a CPU screen instead of placeholder text
- UI clearly shows per-policy supported range, current range, live clock area, min dropdown, max dropdown, save button
- fake data is enough to review the layout before wiring root access

## Phase 2 — wire real CPU data and apply changes
Goal: make the screen functional on rooted devices, still keeping the implementation small.

### Read path
Use policy directories first:
- enumerate `/sys/devices/system/cpu/cpufreq/policy*`
- for each policy read:
  - `cpuinfo_min_freq`
  - `cpuinfo_max_freq`
  - `scaling_min_freq`
  - `scaling_max_freq`
  - `scaling_cur_freq` if present
  - `scaling_available_frequencies` if present

### Refresh strategy
Keep it simple:
- read full policy snapshots every 1 second while the screen is visible
- only refresh applied/live values
- keep drafts separate so open dropdown choices do not reset

This is acceptable because policy count is small.

### Write path
Per policy, on `Save`:
1. validate `draftMin <= draftMax`
2. validate both values are within `cpuinfo_*` range
3. if `availableFreqsKhz` is not empty, require both values to exist in that list
4. write in safe order:
   - if new min is above current max, write max first
   - else if new max is below current min, write min first
   - else write min then max
5. refresh the policy after success

Write targets:
- `scaling_min_freq`
- `scaling_max_freq`

### Error handling
Keep it basic:
- show one inline error message or snackbar for read/write failure
- keep the last good screen state when a refresh fails
- disable `Save` while that policy is being written

### Missing kernel nodes
To keep phase 2 small:
- if `scaling_available_frequencies` is missing, do not invent a fallback yet
- show the policy as read-only with a note like `Kernel did not expose selectable frequencies`

That matches the notes and avoids SmartPack-level compatibility code in v1.

### Done when
- rooted device shows real policies from `/sys/devices/system/cpu/cpufreq/policy*`
- each policy shows supported min/max, applied min/max, current clock
- current clock updates about once per second
- each writable policy exposes min/max dropdowns from kernel frequencies
- tapping `Save` applies the selected values for that policy

## Suggested file layout
Keep the file count low:

- `app/src/main/java/com/example/kernelman/cpu/CpuScreen.kt`
- `app/src/main/java/com/example/kernelman/cpu/CpuViewModel.kt`
- `app/src/main/java/com/example/kernelman/cpu/CpuPolicyApi.kt`
- `app/src/main/java/com/example/kernelman/cpu/RootShell.kt`

Likely edits:
- `app/src/main/java/com/example/kernelman/Navigation.kt`
- `app/src/main/java/com/example/kernelman/MainActivity.kt` only if needed

We can delete the sample `DataRepository` path once the real CPU screen is wired.

## Future phase, not now
After phase 2 is stable, then consider:
- `cpu*/cpufreq` fallback for older kernels
- `time_in_state` / `opp_table` fallback frequency discovery
- governor controls
- apply-on-boot
- vendor-specific nodes from the SmartPack notes
- cluster labels beyond raw policy names

## Recommended order
1. land phase 1 UI with fake data
2. review the layout
3. wire phase 2 sysfs reads
4. wire phase 2 writes
5. test on the rooted device

That should give us a very small first diff and a clean path to iterate.
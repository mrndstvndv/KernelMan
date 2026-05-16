# KernelMan GPU manager plan

## Goal
Add GPU controls beside the existing CPU page, then store CPU + GPU together inside the same saved profile item.

Primary target:
- Qualcomm KGSL + devfreq devices first
- rooted writes through sysfs
- one screen with CPU and GPU sections
- one profile item stores all visible CPU and GPU settings

Deliberately not in scope yet:
- GPU bus/bandwidth controls
- GPU apply-on-boot
- vendor-specific GPU fallbacks beyond KGSL/devfreq
- separate GPU-only profiles
- charts/history

## Why this shape
The current docs and implementation already have a clear pattern for CPU:
- discover kernel nodes
- read current values
- validate drafts
- write with root
- save drafts into profiles

GPU can follow the same pattern, but it needs its own API because GPU sysfs layout is more device-specific.

For this device family, the most likely nodes are:
- `/sys/class/devfreq/*kgsl-3d0*/min_freq`
- `/sys/class/devfreq/*kgsl-3d0*/max_freq`
- `/sys/class/devfreq/*kgsl-3d0*/cur_freq`
- `/sys/class/devfreq/*kgsl-3d0*/available_frequencies`
- `/sys/class/devfreq/*kgsl-3d0*/governor`
- `/sys/class/devfreq/*kgsl-3d0*/available_governors`
- `/sys/class/kgsl/kgsl-3d0/min_pwrlevel`
- `/sys/class/kgsl/kgsl-3d0/max_pwrlevel`
- `/sys/class/kgsl/kgsl-3d0/default_pwrlevel`
- `/sys/class/kgsl/kgsl-3d0/num_pwrlevels`

## UX shape
Keep one page.

Header:
- `Kernel controls`
- supporting text mentions CPU + GPU
- `Profiles` button
- `Last applied: ...` if present
- helper text when there are unsaved CPU or GPU drafts

Sections:
1. CPU policies
2. GPU controls

GPU card contents:
- GPU policy name
- selectable frequency range from `available_frequencies` if present
- applied min/max frequency
- current live frequency
- current governor
- min/max frequency selectors
- governor selector if exposed
- power level selectors if exposed
- one `Save` button per GPU policy

Power level copy must be explicit because KGSL numbering is inverted on many kernels:
- lower number usually means higher performance
- validate using raw node semantics, not user guesses

## Profile model change
Profiles stay single-item snapshots.

Each saved profile should now contain:
- all saved CPU policy values already supported
- all visible GPU settings from the GPU section

That means:
- no GPU-only profile type
- no separate profile storage
- `Create from current` snapshots CPU drafts + GPU drafts together
- `Update from current` overwrites CPU drafts + GPU drafts together
- `Apply` writes CPU first, then GPU

## Data model
Keep the existing profile repository, but extend profile payload with GPU entries.

Suggested additions:

```kotlin
@Serializable
data class GpuProfilePolicy(
  val policyName: String,
  val minFreqHz: Long,
  val maxFreqHz: Long,
  val governor: String? = null,
  val minPowerLevel: Int? = null,
  val maxPowerLevel: Int? = null,
  val defaultPowerLevel: Int? = null,
)
```

And extend the existing profile item with:

```kotlin
val gpuPolicies: List<GpuProfilePolicy> = emptyList()
```

## API plan
Add a dedicated GPU API file.

Suggested file:
- `app/src/main/java/com/example/kernelman/gpu/GpuPolicyApi.kt`

Responsibilities:
- detect GPU devfreq policies
- read one GPU snapshot
- read current GPU frequency for 1 second refreshes
- validate frequency/governor/power-level writes
- apply values in a safe order

Suggested read order:
1. enumerate `/sys/class/devfreq/*`
2. keep entries that look like the GPU core, e.g. `*kgsl-3d0*`
3. read optional KGSL power-level nodes from `/sys/class/kgsl/kgsl-3d0`

Validation rules:
- `min_freq <= max_freq`
- if `available_frequencies` exists, selected min/max must exist in that list
- if `available_governors` exists, selected governor must exist in that list
- if `num_pwrlevels` exists, all selected pwrlevels must be within `0 until num_pwrlevels`
- if both power bounds exist, enforce raw KGSL rule: `max_pwrlevel <= min_pwrlevel`
- if default power level exists with both bounds, require `max_pwrlevel <= default_pwrlevel <= min_pwrlevel`

Write order:
- frequencies: same idea as CPU, adjust the opposing bound first when needed
- power levels: respect KGSL bound relationship, adjusting the opposing bound first when needed
- governor only if changed
- default power level after bounds

## ViewModel plan
Keep one ViewModel.

Add GPU state:
- loaded GPU policies
- live GPU current frequencies
- GPU drafts
- saving state for one GPU policy at a time
- unified error message text

Refresh loop:
- every 1 second while visible
- refresh CPU live clocks
- refresh GPU live clocks
- do not wipe drafts during refresh

## UI files
New files:
- `app/src/main/java/com/example/kernelman/gpu/GpuPolicyApi.kt`
- `app/src/main/java/com/example/kernelman/ui/component/GpuPolicyCard.kt`

Edited files:
- `app/src/main/java/com/example/kernelman/profile/CpuProfileModels.kt`
- `app/src/main/java/com/example/kernelman/profile/CpuProfileRepository.kt`
- `app/src/main/java/com/example/kernelman/ui/screen/CpuViewModel.kt`
- `app/src/main/java/com/example/kernelman/ui/screen/CpuScreen.kt`
- `app/src/main/java/com/example/kernelman/ui/component/ProfileComponents.kt`
- `app/src/main/java/com/example/kernelman/ui/component/CpuComponents.kt`

## Apply flow
When applying a saved profile:
1. load current CPU policies if the profile contains CPU data
2. load current GPU policies if the profile contains GPU data
3. validate compatibility against live kernel state
4. apply CPU entries
5. apply GPU entries
6. persist `lastAppliedProfileId`
7. refresh live state
8. clear CPU + GPU drafts

## Done when
- app shows GPU controls on the main page
- GPU min/max frequency can be edited if kernel nodes exist
- GPU governor can be edited if exposed
- GPU power levels can be edited if exposed
- `Create from current` stores CPU + GPU together in one profile item
- `Update from current` stores CPU + GPU together in one profile item
- `Apply` restores CPU + GPU from that single profile item
- no separate GPU profile concept exists anywhere in the UI

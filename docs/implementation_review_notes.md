# KernelMan implementation review notes

This document summarizes the CPU/GPU/profile/support-status changes that were implemented so another agent can review the code quickly.

## Scope of work

Implemented all of the following:
- GPU controls on the main screen
- unified profiles so one profile item stores CPU + GPU together
- GPU control plan doc
- support / unsupported-state docs
- in-app support-status UX with explicit reasons
- simplified GPU pwrlevel UX
- build verification

## Docs added

### `docs/gpu_manager_plan.md`
Added implementation plan for:
- GPU devfreq + KGSL support
- single-page CPU + GPU UI
- unified profiles
- validation and write-order rules

### `docs/kernel_support_and_guards.md`
Added support documentation for:
- likely supported CPU devices/kernels
- likely supported GPU devices/kernels
- partial support cases
- unsupported cases
- runtime guard behavior
- suggested unsupported/read-only UX copy
- note about other kernel managers rewriting sysfs values

## Main code changes

### 1. Added GPU API

#### New file
- `app/src/main/java/com/example/kernelman/gpu/GpuPolicyApi.kt`

#### What it does
- detects GPU devfreq policies under `/sys/class/devfreq/*`
- filters for likely KGSL GPU entries like `*kgsl-3d0*`
- reads:
  - `min_freq`
  - `max_freq`
  - `cur_freq`
  - `available_frequencies`
  - `governor`
  - `available_governors`
- reads optional KGSL info from `/sys/class/kgsl/kgsl-3d0/`:
  - `default_pwrlevel`
  - `min_pwrlevel`
  - `max_pwrlevel`
  - `num_pwrlevels`
- applies GPU min/max freq
- applies GPU governor when exposed
- applies GPU `default_pwrlevel` when exposed

#### Important implementation details
- frequency writes now only write the node that actually changed when possible
- this was added after a real-world issue where rewriting both GPU min/max in one save could interfere with behavior on some kernels
- GPU `min_pwrlevel` / `max_pwrlevel` are now treated as read-only info, not editable user controls

### 2. Unified profiles: CPU + GPU in one profile item

#### Files changed
- `app/src/main/java/com/example/kernelman/profile/CpuProfileModels.kt`
- `app/src/main/java/com/example/kernelman/profile/CpuProfileRepository.kt`

#### Model changes
Extended the existing profile item to also store GPU settings:
- existing CPU `policies` retained
- added `gpuPolicies`

Current saved GPU fields are:
- `policyName`
- `minFreqHz`
- `maxFreqHz`
- `governor`
- `defaultPowerLevel`

#### Snapshot behavior
`Create from current` and `Update from current` now snapshot:
- CPU drafts + current CPU values
- GPU drafts + current GPU values

No separate GPU-only profile type exists.

#### Apply behavior
Applying a profile now:
1. loads current CPU policies if the profile contains CPU data
2. loads current GPU policies if the profile contains GPU data
3. validates compatibility
4. applies CPU settings
5. applies GPU settings
6. persists `lastAppliedProfileId`
7. refreshes state
8. clears CPU + GPU drafts

### 3. ViewModel now handles CPU + GPU + support UX

#### File changed
- `app/src/main/java/com/example/kernelman/ui/screen/CpuViewModel.kt`

#### State changes
Added GPU state to `CpuScreenState`:
- `gpuPolicies`
- `currentGpuFreqsHz`
- `gpuDrafts`
- `savingGpuPolicyName`

Added support-status fields:
- `cpuSupportMessage`
- `gpuSupportMessage`

#### Support detection behavior
During policy refresh:
- CPU load failures are converted into CPU-specific support messages
- GPU load failures are converted into GPU-specific support messages
- section-level unsupported state is stored separately from transient save/read errors

This means the UI can now say exactly why CPU or GPU is unsupported instead of showing only one vague generic error.

#### Draft behavior
CPU drafts and GPU drafts are kept separate from live refreshes.

Live refresh still runs roughly every 1 second for current clocks.

### 4. Main screen now has CPU + GPU sections

#### File changed
- `app/src/main/java/com/example/kernelman/ui/screen/CpuScreen.kt`

#### UI changes
Main screen header now covers kernel controls generally.

The screen now always renders:
- CPU section
- GPU section

Each section either shows:
- working controls, or
- a support-status card explaining why the feature is unsupported

#### Profile-related UX
The existing profile button and dialogs now work against combined CPU + GPU state.

Unsaved-draft copy now refers to CPU or GPU edits together.

### 5. Added GPU card UI

#### New file
- `app/src/main/java/com/example/kernelman/ui/component/GpuPolicyCard.kt`

#### Current GPU UI shows
- selectable frequency range
- applied min/max frequency
- current frequency
- current governor
- kernel pwrlevel window as read-only info
- current default pwrlevel
- editable:
  - min frequency
  - max frequency
  - governor, when exposed
  - default pwrlevel, when exposed

#### Intentionally not editable now
- `min_pwrlevel`
- `max_pwrlevel`

Reason:
- KGSL pwrlevel numbering is inverted and confusing
- these are kernel constraint bounds, not a simple user-facing perf knob
- exposing them directly made the UX too confusing

### 6. Improved shared UI messaging

#### Files changed
- `app/src/main/java/com/example/kernelman/ui/component/CpuComponents.kt`
- `app/src/main/java/com/example/kernelman/ui/component/CpuPolicyCard.kt`
- `app/src/main/java/com/example/kernelman/ui/component/GpuPolicyCard.kt`
- `app/src/main/java/com/example/kernelman/ui/component/ProfileComponents.kt`

#### Added
- `SupportStatusCard(...)`

#### Messaging changes
Made unsupported/read-only messaging more explicit, for example:
- feature unsupported on device
- governor control unavailable
- read-only in KernelMan
- reason text explains what kernel node/list is missing

#### Profile sheet copy
Updated profile sheet/dialog copy to refer to CPU + GPU settings instead of CPU only.

Profile rows now summarize both CPU and GPU content.

## Important GPU design decisions

### Editable GPU settings kept
- min freq
- max freq
- governor, if exposed
- default pwrlevel, if exposed

### GPU settings intentionally not editable now
- min pwrlevel
- max pwrlevel

### Why
- raw KGSL min/max pwrlevel are not intuitive
- lower number often means higher performance
- exposing them directly risks confusion and bad configurations
- current UX is safer if they remain read-only diagnostic info

## Support UX behavior in app

### CPU section unsupported examples
If CPU policy loading fails, the screen now shows a CPU support card like:
- root unavailable
- no `cpufreq/policy*` interface found
- required CPU node missing

### GPU section unsupported examples
If GPU policy loading fails, the screen now shows a GPU support card like:
- root unavailable
- no KGSL/devfreq GPU policy found
- required GPU node missing

### Per-card read-only examples
If a policy exists but lacks editable lists, cards now say things like:
- `Read-only in KernelMan. Reason: kernel did not expose selectable frequencies for this policy.`
- `GPU governor control unavailable. Reason: kernel did not expose selectable GPU governors for this policy.`

## Known assumptions / review focus

A reviewer should focus on these areas:

### 1. GPU detection assumptions
Current GPU detection assumes Qualcomm KGSL/devfreq style paths.

Review whether this filter is too narrow or too broad:
- looks under `/sys/class/devfreq/*`
- keeps entries matching KGSL-like names
- excludes obvious bus-monitor nodes

### 2. GPU write-path assumptions
Current GPU freq writes use generic devfreq nodes:
- `min_freq`
- `max_freq`

Review whether fallback GPU-specific writable nodes should be added later for kernels that ignore generic devfreq writes.

### 3. Support-status mapping
Review whether the mapping from low-level CPU/GPU errors into UI support messages is precise enough and whether the copy is too technical or not technical enough.

### 4. Profile compatibility rules
Review whether GPU profile compatibility checks are strict enough, especially for:
- governor list changes
- missing `default_pwrlevel`
- pwrlevel window validation

### 5. UX simplification around pwrlevels
Review whether keeping only `default_pwrlevel` editable is the right call for v1, or whether GPU pwrlevel controls should be hidden completely for now.

## Build verification

Verified with:

```text
./gradlew :app:compileDebugKotlin
```

Build completed successfully after the latest support-status UX changes.

## File summary

### New files
- `docs/gpu_manager_plan.md`
- `docs/kernel_support_and_guards.md`
- `app/src/main/java/com/example/kernelman/gpu/GpuPolicyApi.kt`
- `app/src/main/java/com/example/kernelman/ui/component/GpuPolicyCard.kt`

### Updated files
- `app/src/main/java/com/example/kernelman/profile/CpuProfileModels.kt`
- `app/src/main/java/com/example/kernelman/profile/CpuProfileRepository.kt`
- `app/src/main/java/com/example/kernelman/ui/component/CpuComponents.kt`
- `app/src/main/java/com/example/kernelman/ui/component/CpuPolicyCard.kt`
- `app/src/main/java/com/example/kernelman/ui/component/ProfileComponents.kt`
- `app/src/main/java/com/example/kernelman/ui/screen/CpuScreen.kt`
- `app/src/main/java/com/example/kernelman/ui/screen/CpuViewModel.kt`

## Short review prompt suggestion

If you want to hand this to another agent, a good prompt is:

```text
Please review the recent CPU/GPU/profile/support-status changes in KernelMan.
Focus on:
- GPU sysfs detection and write-path correctness
- unified CPU+GPU profile model correctness
- support-status UX and error mapping
- whether the current GPU pwrlevel UX is appropriately scoped
- any edge cases around refresh, save, and apply-profile flow
Use docs/implementation_review_notes.md as the change summary.
```

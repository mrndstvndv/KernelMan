# Partial-support behavior plan

## Problem

Current load behavior is section-wide and brittle:
- one bad CPU policy can hide the whole CPU section
- one bad GPU policy can hide the whole GPU section
- the UI can say "unsupported" even when some policies are usable

That is not a good match for real kernels.

Android kernel interfaces are often mixed:
- one policy works, another is missing a node
- one policy is readable, another returns junk
- frequency lists may be missing on one policy only
- GPU core path may exist, but one optional node may be broken

We should degrade gracefully.

## Target behavior

### Section-level behavior
Show a section-wide unsupported card only when the whole section is unavailable, for example:
- root unavailable
- policy enumeration failed
- no policy directories found

### Per-policy behavior
If enumeration works and at least one policy loads:
- show working policies normally
- keep read-only policies as normal cards when we have enough data to render them safely
- show a per-policy support card for policies that failed to load fully
- do not hide the whole section because one policy is bad

### Error behavior
Keep support state separate from transient runtime failures:
- support issues stay attached to the section or policy
- refresh/save/apply failures still use the existing transient error surface

## Why we want this

1. **More truthful UX**
   - "unsupported device" is wrong when only one policy is broken.
   - users should see what actually works.

2. **Better real-device coverage**
   - vendor kernels are inconsistent.
   - partial support is common.
   - graceful degradation lets KernelMan still be useful on messy kernels.

3. **Safer behavior**
   - we only expose controls for policies we could validate.
   - broken policies stay blocked with explicit reasons.

4. **Less user confusion**
   - a single missing node should not make CPU/GPU controls disappear.
   - exact policy-level reasons are easier to debug.

5. **Better profile behavior**
   - profiles can still apply to the policies that remain compatible.
   - when a saved policy cannot be applied, the app can explain which one failed and why.

## Plan

### 1. Add explicit load-result types

Add small result models for CPU and GPU policy loading.

Suggested shape:

```kotlin
data class PolicyLoadIssue(
  val policyName: String,
  val message: String,
)

data class PolicySectionLoadResult<T>(
  val policies: List<T>,
  val issues: List<PolicyLoadIssue> = emptyList(),
  val supportMessage: String? = null,
)
```

Rules:
- `supportMessage != null` only for section-wide unsupported state
- `issues` only for per-policy failures
- `policies` contains only successfully loaded policies

## 2. Change API loading flow

### CPU
In `app/src/main/java/com/example/kernelman/cpu/CpuPolicyApi.kt`:
- keep `listPolicyNames()` as the section gate
- if enumeration fails, return section unsupported
- if enumeration succeeds, read each policy independently
- collect successes + failures instead of throwing on first broken policy

### GPU
In `app/src/main/java/com/example/kernelman/gpu/GpuPolicyApi.kt`:
- same pattern
- list matching KGSL policies first
- read each policy independently
- collect per-policy failures

Important rule:
- root unavailable / enumeration failure / no policies found = section unsupported
- individual `MissingNode` / `ParseFailure` during `readPolicy()` = per-policy issue

## 3. Update `CpuScreenState`

In `app/src/main/java/com/example/kernelman/ui/screen/CpuViewModel.kt`, add explicit per-policy issue state.

Suggested fields:

```kotlin
val cpuPolicyIssues: List<PolicyLoadIssue> = emptyList()
val gpuPolicyIssues: List<PolicyLoadIssue> = emptyList()
```

Keep:
- `cpuSupportMessage`
- `gpuSupportMessage`

Meaning:
- support message = whole section unavailable
- policy issues = partial support inside a working section

## 4. Update refresh behavior

In `refreshPolicies()`:
- load CPU and GPU through the new detailed loaders
- keep successful policies
- attach per-policy issues to state
- keep `errorMessage = null` for support-state refreshes

Draft syncing rules:
- keep drafts only for successfully loaded policies
- drop drafts for policies that disappeared or became unreadable

Current-frequency refresh rules:
- refresh only successfully loaded policies
- do not turn a transient current-frequency read failure into a section unsupported state

## 5. Update UI rendering

In `app/src/main/java/com/example/kernelman/ui/screen/CpuScreen.kt`:

### CPU section
- if `cpuSupportMessage != null` and `cpuPolicies.isEmpty()`, show section support card
- otherwise show loaded CPU cards
- after the loaded cards, render one support card per entry in `cpuPolicyIssues`

### GPU section
- same pattern

Suggested card copy:
- title: `policy4 unavailable`
- message: exact support reason

Optional small banner when partial:
- `Some CPU policies could not be loaded. Working policies are still available below.`
- same for GPU

## 6. Keep read-only separate from unsupported

Do not turn these into policy-load failures:
- missing selectable frequency lists
- missing selectable governor lists
- missing editable GPU governor lists

Those are already modeled as read-only cards and should stay that way.

A policy should only become a policy-load issue when we cannot safely build the base card.

## 7. Profile behavior

In `app/src/main/java/com/example/kernelman/profile/CpuProfileModels.kt` and `CpuViewModel.kt`:
- compatibility checks should still fail if a profile needs a policy that is missing or broken
- when possible, report the exact policy-level reason
- creating or updating a profile should snapshot only successfully loaded policies
- if a section is partial, consider warning the user that unavailable policies were not included

Suggested future UX copy:
- `Some CPU policies were unavailable and were not saved into this profile.`

## 8. Testing plan

Add tests for:
- one CPU policy loads, one fails -> section still visible
- one GPU policy loads, one fails -> section still visible
- root unavailable -> section support card, no policy cards
- no policies found -> section support card, no policy cards
- read-only policy -> normal card, not policy issue
- profile compatibility failure for one missing policy -> exact reason shown
- refresh-current-freq failure -> transient error only

## 9. Rollout order

Recommended order:
1. add load-result models
2. change CPU loader
3. change GPU loader
4. wire new state into `CpuViewModel`
5. update UI rendering
6. adjust profile compatibility and save copy
7. add tests

## Non-goals for this pass

Do not mix these into the same change:
- vendor-specific GPU fallback nodes
- new kernel write strategies
- new profile format changes
- apply-on-boot logic

Keep this pass focused on load behavior and UI truthfulness.

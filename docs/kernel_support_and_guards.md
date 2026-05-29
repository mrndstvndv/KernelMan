# KernelMan support matrix and guard behavior

This document explains which devices are likely to work with the current CPU and GPU implementations, why they work, how KernelMan decides whether a feature is supported, and what kind of unsupported-state messaging we should show in the UI.

It also documents one practical gotcha we just hit during testing:

> Another kernel manager or boot-time tuner can silently overwrite the same sysfs nodes.
>
> That can make a feature look broken even when our write path is correct.

So support is not only about kernel nodes existing. It is also about whether something else is immediately rewriting them.

---

## Support philosophy

KernelMan should treat support as **runtime-detected**, not brand-detected.

Do not hardcode support by:
- OEM name
- model name
- SoC marketing name alone

Do detect support by checking whether the kernel exposes the required sysfs interface and whether the nodes behave as expected.

Why:
- two phones with the same SoC can ship different kernels
- the same phone can behave differently across stock kernel, custom kernel, and ROM updates
- some kernels expose read-only nodes, some writable nodes, some no nodes at all

So the real question is never:
- `Is this a Snapdragon 8s Gen 3 device?`

It is:
- `Does this kernel expose the required writable nodes for this feature?`

---

# CPU support

## Current CPU method

Current CPU support is built around Linux cpufreq **policy directories**:

```text
/sys/devices/system/cpu/cpufreq/policy*/
```

Required nodes per policy:

```text
cpuinfo_min_freq
cpuinfo_max_freq
scaling_min_freq
scaling_max_freq
```

Optional but useful nodes:

```text
scaling_cur_freq
scaling_available_frequencies
scaling_governor
scaling_available_governors
```

## Likely supported devices

The current CPU method is most likely to work on rooted Android devices whose kernels expose standard cpufreq policy nodes.

That commonly includes many devices from:
- Qualcomm / Snapdragon
- MediaTek
- Exynos
- Tensor
- custom kernels that keep standard cpufreq policy sysfs

Examples of likely-good cases:
- modern rooted phones with `policy0`, `policy4`, etc.
- kernels where `scaling_min_freq` and `scaling_max_freq` are writable
- kernels exposing `scaling_available_frequencies`

## Possible partial support

A device may be **partially supported** when:
- policy directories exist
- current/applied values can be read
- but `scaling_available_frequencies` is missing

In that case the screen can still show current values, but editable dropdowns are less safe.

Current behavior for that case should be:
- show the policy
- show current values
- disable frequency editing for that policy
- explain exactly why

Suggested copy:

```text
This CPU policy is read-only in KernelMan.
Reason: kernel did not expose selectable frequencies for this policy.
```

## Likely unsupported CPU cases

### 1. No policy directories

If this path is empty or missing:

```text
/sys/devices/system/cpu/cpufreq/policy*/
```

then the current CPU method is unsupported.

This may happen on:
- older kernels using only per-core nodes
- unusual vendor kernels
- devices hiding cpufreq sysfs

Suggested copy:

```text
CPU controls are not supported on this device.
Reason: kernel did not expose cpufreq policy directories.
```

### 2. Required nodes missing

If a policy exists but one of these is missing:
- `cpuinfo_min_freq`
- `cpuinfo_max_freq`
- `scaling_min_freq`
- `scaling_max_freq`

then that policy is unsupported for editing.

Suggested copy:

```text
CPU policy policy4 is not supported.
Reason: missing kernel node scaling_max_freq.
```

### 3. Root unavailable

If `su` fails, the device is effectively unsupported for writes.

Suggested copy:

```text
CPU controls require root.
Reason: root shell is unavailable.
```

### 4. Nodes exist but writes do not stick

The kernel may expose nodes but another component may rewrite them immediately.

Common causes:
- another kernel manager app
- Magisk boot scripts
- vendor performance daemon
- thermal daemon
- apply-on-boot service from another app

Suggested copy:

```text
CPU values were written but did not stick.
Possible reason: another kernel or thermal service rewrote the values.
```

---

## CPU guards currently in place

KernelMan should guard CPU writes like this:

1. Require root shell.
2. Enumerate policy directories.
3. Require the core min/max nodes.
4. Validate:
   - `min <= max`
   - selected min is not below `cpuinfo_min_freq`
   - selected max is not above `cpuinfo_max_freq`
   - if `scaling_available_frequencies` exists, selected values must come from that list
   - if `scaling_available_governors` exists, selected governor must come from that list
5. Write in safe order when bounds would temporarily cross.
6. Refresh from kernel after save.

That means support is decided by the kernel interface, not by a hand-maintained device whitelist.

---

# GPU support

## Current GPU method

Current GPU support targets **Qualcomm Adreno / KGSL + devfreq** kernels.

Primary read/write path:

```text
/sys/class/devfreq/*kgsl-3d0*/
```

Expected devfreq nodes:

```text
min_freq
max_freq
cur_freq
available_frequencies        # optional but strongly preferred
governor                     # optional
available_governors          # optional
```

Optional KGSL info path:

```text
/sys/class/kgsl/kgsl-3d0/
```

Optional KGSL nodes used for info / extra control:

```text
default_pwrlevel
min_pwrlevel
max_pwrlevel
num_pwrlevels
```

## Likely supported devices

The current GPU method is most likely to work on rooted Qualcomm devices using KGSL.

That commonly includes many phones with:
- Snapdragon SoCs
- Adreno GPU
- KGSL driver
- devfreq GPU interface exposed in sysfs

Typical examples:
- Pixel devices on Qualcomm generations that expose KGSL/devfreq
- Xiaomi / Redmi / POCO Qualcomm devices with writable GPU nodes
- OnePlus Qualcomm devices with writable KGSL/devfreq nodes
- custom kernels that keep standard KGSL/devfreq sysfs

This is a **kernel-interface guess**, not a guaranteed OEM list.

If a rooted device exposes:

```text
/sys/class/devfreq/3d00000.qcom,kgsl-3d0
```

or something similar, it is a strong sign the current GPU method can work.

## Possible partial support

A device may be partially supported when:
- the devfreq GPU directory exists
- `min_freq`, `max_freq`, and `cur_freq` exist
- but governor nodes or pwrlevel nodes are missing

Examples:
- frequency editing works
- governor editing is unavailable
- default pwrlevel editing is unavailable

Suggested copy:

```text
GPU frequency control is supported.
GPU governor control is not supported on this kernel.
Reason: kernel did not expose available_governors.
```

## Likely unsupported GPU cases

### 1. No KGSL/devfreq GPU policy found

If no matching GPU devfreq policy is found under:

```text
/sys/class/devfreq/*
```

then the current GPU method is unsupported.

This is likely on:
- Mali devices
- PowerVR devices
- vendor-specific GPU interfaces not using KGSL/devfreq
- Qualcomm kernels that hide or rename the exposed nodes beyond our detection

Suggested copy:

```text
GPU controls are not supported on this device.
Reason: no KGSL/devfreq GPU policy was found.
```

### 2. Required devfreq nodes missing

If the GPU policy exists but one of these is missing:
- `min_freq`
- `max_freq`

then GPU frequency editing should be unsupported.

Suggested copy:

```text
GPU frequency control is not supported.
Reason: missing kernel node max_freq.
```

### 3. No selectable frequencies exposed

If `available_frequencies` is missing, the safest v1 behavior is to avoid editable GPU frequency dropdowns.

Suggested copy:

```text
GPU frequency control is read-only in KernelMan.
Reason: kernel did not expose selectable GPU frequencies.
```

### 4. Nodes exist but writes do not stick

This can happen even on a supported kernel.

Common causes:
- another kernel manager app
- apply-on-boot scripts
- vendor thermal / performance services
- GPU governor behavior
- alternative writable node is the real source of truth, not the generic devfreq node

This is exactly why support messaging must distinguish between:
- `node missing`
- `node present but read-only`
- `write accepted but overridden`

Suggested copy:

```text
GPU value did not stick after save.
Possible reason: another service rewrote the GPU limit, or this kernel uses a different writable GPU node.
```

---

## Why min/max GPU pwrlevel should be treated carefully

On KGSL kernels, `min_pwrlevel` and `max_pwrlevel` are **kernel constraint bounds**, not simple everyday performance toggles.

Important nuance:
- lower pwrlevel number usually means **higher performance**
- higher pwrlevel number usually means **lower performance**

That makes the raw interface confusing.

Because of that, current UX should prefer:
- show `min_pwrlevel` / `max_pwrlevel` as info
- optionally allow `default_pwrlevel`
- avoid exposing raw min/max pwrlevel editing unless there is a clear UX reason

This keeps the UI understandable and reduces accidental invalid configurations.

---

## GPU guards currently in place

KernelMan should guard GPU writes like this:

1. Require root shell.
2. Detect a KGSL/devfreq GPU policy directory.
3. Require core frequency nodes for editing.
4. If `available_frequencies` exists, only allow values from that list.
5. If `available_governors` exists, only allow governors from that list.
6. Only write the node that actually changed when possible.
7. If `default_pwrlevel` is editable, keep it inside the kernel pwrlevel window.
8. Refresh from kernel after save.

Optional UI gating:
- show GPU governor selector only if governor nodes exist
- show default pwrlevel selector only if `default_pwrlevel` and `num_pwrlevels` exist
- show raw min/max pwrlevels as read-only info

---

# Device categories summary

## Likely fully supported right now

### CPU
- rooted Android devices with standard `cpufreq/policy*` directories
- writable `scaling_min_freq` / `scaling_max_freq`
- exposed selectable frequencies

### GPU
- rooted Qualcomm / Adreno / KGSL devices
- writable GPU devfreq `min_freq` / `max_freq`
- exposed GPU `available_frequencies`
- optional governor and default pwrlevel nodes

## Likely partially supported right now

### CPU
- policy directories exist
- readable current values
- missing selectable frequency list

### GPU
- devfreq GPU path exists
- current freq + min/max are readable
- governor list missing, or default pwrlevel missing

## Likely unsupported right now

### CPU
- no `policy*` cpufreq interface
- per-core-only kernels without current fallback implementation
- no root

### GPU
- non-KGSL GPUs
- Qualcomm kernels with only vendor-specific GPU nodes not yet detected
- no root

---

# Recommended unsupported-state UX

## Screen-level unsupported state

Use for total failure, e.g. no policies found.

Example:

```text
Feature not supported on this device
KernelMan could not find a supported CPU policy interface.
Details: no /sys/devices/system/cpu/cpufreq/policy* directories were found.
```

GPU example:

```text
Feature not supported on this device
KernelMan could not find a supported KGSL/devfreq GPU interface.
Details: no /sys/class/devfreq/*kgsl-3d0* policy was found.
```

## Per-policy unsupported state

Use when the section exists but one item cannot be edited.

Example:

```text
policy4
Read-only in KernelMan
Reason: kernel did not expose selectable frequencies for this policy.
```

GPU example:

```text
3d00000.qcom,kgsl-3d0
Governor control unavailable
Reason: kernel did not expose available_governors.
```

## Save failure state

Use when the interface exists but the save is rejected or overridden.

Example:

```text
Save failed
Details: GPU value did not stick after write.
Possible reason: another kernel manager or thermal service rewrote the node.
```

---

# Notes for future implementation

## Good future improvements
- explicit `SupportStatus` model per feature and per policy
- distinguish `unsupported`, `read-only`, `supported`, `overridden`
- optional manual diagnostics view showing detected node paths
- optional fallback GPU nodes such as KGSL-specific clock limit nodes if a kernel ignores generic devfreq writes
- optional CPU fallback for older per-core cpufreq kernels

## Important testing rule
When a user says a feature does not work, always ask whether they have:
- another kernel manager installed
- an apply-on-boot script active
- a Magisk module that tweaks CPU/GPU
- a thermal or gaming tool rewriting limits

That is not edge-case noise. It is a common real-world source of false-negative support reports.

---

# Bottom line

Support should be explained as:

- **supported** when the kernel exposes the required writable nodes
- **partially supported** when values can be read but not safely edited
- **unsupported** when required nodes are missing
- **overridden** when writes succeed but another service rewrites them

That gives users a much more honest answer than a vague message like:

```text
Not supported on your device
```

We should always tell them:
- what interface we expected
- what we actually found
- why the feature is disabled or read-only
- whether another service may be overriding it

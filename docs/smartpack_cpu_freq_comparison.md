# CPU frequency notes vs SmartPack Kernel Manager

This compares `docs/android_cpu_freq_notes.md` with SmartPack Kernel Manager's CPU frequency implementation.

Source inspected: `SmartPack/SmartPack-Kernel-Manager`, mainly:

- `app/src/main/java/com/smartpack/kernelmanager/utils/kernel/cpu/CPUFreq.java`
- `app/src/main/java/com/smartpack/kernelmanager/fragments/kernel/CPUFragment.java`
- `app/src/main/java/com/smartpack/kernelmanager/utils/root/Control.java`
- `app/src/main/java/com/smartpack/kernelmanager/utils/kernel/cpu/MSMPerformance.java`

## High-level match

The notes are correct for modern Android kernels that expose cpufreq policies under:

```text
/sys/devices/system/cpu/cpufreq/policy*/
```

SmartPack uses the same Linux cpufreq sysfs concept, but its main implementation is older / broader compatibility code. Instead of primarily enumerating `policy*`, it mostly targets per-core paths:

```text
/sys/devices/system/cpu/cpu%d/cpufreq/scaling_min_freq
/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq
/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq
/sys/devices/system/cpu/cpu%d/cpufreq/scaling_available_frequencies
/sys/devices/system/cpu/cpu%d/cpufreq/scaling_governor
```

It only references policy paths in limited device-detection logic:

```text
/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq
/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq
```

## Key differences

| Topic | Notes | SmartPack approach |
|---|---|---|
| Primary abstraction | `policy*/` directories | CPU core ranges / clusters: big, mid, LITTLE |
| Main writable nodes | `policy*/scaling_min_freq`, `policy*/scaling_max_freq` | `cpu%d/cpufreq/scaling_min_freq`, `cpu%d/cpufreq/scaling_max_freq` |
| Policy discovery | Enumerate `/sys/devices/system/cpu/cpufreq/policy*/` | Determine CPU count from `/sys/devices/system/cpu/present`, then infer clusters |
| Frequency choices | `scaling_available_frequencies` if present | Prefer `opp_table` or `time_in_state`, fallback to `scaling_available_frequencies` |
| Min/max validation | Explicitly validate against cpuinfo range and available list | UI selects values from discovered frequency lists; setters also adjust the opposing limit if needed |
| Offline cores | Notes do not cover this | Temporarily online an offline CPU before reading/writing, then restore offline state |
| Permissions | `su -c 'echo ... > node'` | Runs root shell commands, also `chmod 644` before writes and `chmod 444` after |
| Persistence | Reapply on boot | Stores commands/settings under apply-on-boot categories |
| Vendor-specific support | Mentioned generally | Has explicit Qualcomm/MSM helpers and custom kernel nodes |

## How SmartPack sets min/max frequency

SmartPack exposes dropdowns for each detected cluster in `CPUFragment.java`:

- Big cluster max/min
- Optional mid cluster max/min
- LITTLE cluster max/min

When a user selects a frequency, the fragment calls:

```java
mCPUFreq.setMaxFreq(freq, firstCore, lastCore, context)
mCPUFreq.setMinFreq(freq, firstCore, lastCore, context)
```

`CPUFreq.setMinFreq()`:

1. Reads current max for the target range.
2. If the new min is above current max, raises max first.
3. Writes Qualcomm/MSM performance min nodes if supported.
4. Writes `scaling_min_freq` for every CPU in the target range.
5. Writes `/sys/kernel/cpufreq_hardlimit/scaling_min_freq` if present.

`CPUFreq.setMaxFreq()`:

1. Writes `/sys/kernel/msm_cpufreq_limit/cpufreq_limit` if present and needed.
2. Reads current min for the target range.
3. If the new max is below current min, lowers min first.
4. Enables overclock node `enable_oc` if present.
5. Writes Qualcomm/MSM performance max nodes if supported.
6. Writes either `scaling_max_freq_kt` if present, else `scaling_max_freq`.
7. Writes `/sys/kernel/cpufreq_hardlimit/scaling_max_freq` if present.

This confirms your note that `scaling_min_freq` and `scaling_max_freq` are the runtime limits, but SmartPack adds many compatibility layers around those writes.

## SmartPack write sequence

For each CPU in a selected range, `applyCpu()` does roughly:

```sh
chmod 644 /sys/devices/system/cpu/cpuN/cpufreq/scaling_max_freq
echo '<freq>' > /sys/devices/system/cpu/cpuN/cpufreq/scaling_max_freq
chmod 444 /sys/devices/system/cpu/cpuN/cpufreq/scaling_max_freq
```

Before writing, it may:

- unlock `/sys/kernel/cpufreq_hardlimit/userspace_dvfs_lock`
- disable `mpdecision`
- temporarily online an offline core

After writing, it may:

- restore the core offline state
- re-enable `mpdecision`
- re-lock `userspace_dvfs_lock`

## Frequency list discovery

Your notes suggest reading:

```text
scaling_available_frequencies
```

SmartPack does that, but only after trying alternatives:

1. `/sys/devices/system/cpu/cpu%d/opp_table`
2. `/sys/devices/system/cpu/cpufreq/stats/cpu%d/time_in_state`
3. `/sys/devices/system/cpu/cpu%d/cpufreq/stats/time_in_state`
4. `/sys/devices/system/cpu/cpu%d/cpufreq/scaling_available_frequencies`

Why this matters: on some kernels `scaling_available_frequencies` is missing, incomplete, or unavailable while a core is offline. `time_in_state` often still gives a useful list of real frequencies.

## Cluster detection

Your notes treat `policy0`, `policy1`, etc. as the cluster abstraction. SmartPack instead infers clusters using:

- CPU count from `/sys/devices/system/cpu/present`
- board names like `msm8996`, `marlin`, `sailfish`, `mt6*`, `msm8929`
- max frequency comparisons between cores
- limited policy max checks for `policy0` and `policy6`

It supports layouts like:

- single cluster
- big.LITTLE
- 4 LITTLE + 2 mid + 2 big
- 4 LITTLE + 3 mid + 1 big
- 6 LITTLE + 2 big

## Persistence comparison

Your notes say persistence requires reapplying settings at boot. SmartPack does exactly that.

Every write can be saved with an apply-on-boot category like:

```java
ApplyOnBootFragment.CPU
```

`Control.runSetting()` saves the command/id/category into app settings when a `Context` is provided, then executes the root command. The apply-on-boot UI enables/disables whether those saved settings are replayed.

## Practical findings for your app

Your notes are a good minimal implementation, especially for modern policy-based kernels. SmartPack suggests these improvements:

1. Support both path styles:

```text
/sys/devices/system/cpu/cpufreq/policy*/...
/sys/devices/system/cpu/cpu*/cpufreq/...
```

2. Build UI around frequency domains/clusters, not only raw cores.

3. Use these sources for frequency choices, in order:

```text
opp_table
time_in_state
scaling_available_frequencies
cpuinfo_min_freq/cpuinfo_max_freq fallback range
```

4. Handle `min > max` and `max < min` automatically by adjusting the opposite limit first.

5. Handle offline CPUs if using per-core nodes.

6. Expect vendor/custom nodes:

```text
/sys/module/msm_performance/parameters/cpu_min_freq
/sys/module/msm_performance/parameters/cpu_max_freq
/sys/module/msm_performance/parameters/max_cpu_freq
/sys/kernel/msm_cpufreq_limit/cpufreq_limit
/sys/kernel/cpufreq_hardlimit/scaling_min_freq
/sys/kernel/cpufreq_hardlimit/scaling_max_freq
/sys/kernel/cpufreq_hardlimit/userspace_dvfs_lock
/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq_kt
/sys/devices/system/cpu/cpu%d/cpufreq/enable_oc
```

7. Persist by storing intended settings and reapplying at boot, not by assuming sysfs writes survive reboot.

## Recommendation

For a new Android app, start with the cleaner policy-based design from your notes. Add SmartPack-style fallbacks only where needed.

Suggested detection order:

1. Enumerate `/sys/devices/system/cpu/cpufreq/policy*`.
2. If policies exist, treat each policy as one frequency domain.
3. If no policies exist, fall back to `/sys/devices/system/cpu/cpu*/cpufreq`.
4. For each domain, discover available frequencies from `scaling_available_frequencies`, then `time_in_state`, then `opp_table`.
5. Validate user choices and write `scaling_min_freq` / `scaling_max_freq` through root.
6. Save desired settings and reapply on boot if enabled.

# Android CPU Frequency Notes (Rooted Devices)

This document summarizes how CPU clock limits are exposed on Android Linux kernels and how they can be read or changed from a rooted shell. It is intended as reference material for building an Android kernel/CPU manager app.

## What we verified

On this device, the following file exists:

```sh
su -c 'cat /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq'
```

That means the kernel exposes CPU frequency policy files under:

```text
/sys/devices/system/cpu/cpufreq/policy*/
```

## Important concepts

### Policy directories
Android kernels often expose frequency controls per **policy** rather than per physical CPU.

Examples:
- `policy0`
- `policy1`
- `policy2`

A policy usually maps to a CPU cluster or a group of cores that share the same frequency domain.

### Min/max frequency values
Frequency values are usually in **kHz**.

Examples:
- `1200000` = 1.2 GHz
- `2400000` = 2.4 GHz

## Files to read

For a given policy, these are the most useful files:

```text
cpuinfo_min_freq
cpuinfo_max_freq
scaling_min_freq
scaling_max_freq
scaling_available_frequencies   # may not exist on all kernels
scaling_governor
```

### Meaning
- `cpuinfo_min_freq`: hardware/kernel minimum supported frequency for that policy
- `cpuinfo_max_freq`: hardware/kernel maximum supported frequency for that policy
- `scaling_min_freq`: currently enforced lower bound
- `scaling_max_freq`: currently enforced upper bound
- `scaling_available_frequencies`: discrete frequencies the kernel may allow, if exposed
- `scaling_governor`: current CPU governor

## Example commands

### Read min/max for policy0
```sh
su -c 'cat /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq'
su -c 'cat /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq'
```

### Read current scaling limits
```sh
su -c 'cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq'
su -c 'cat /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'
```

### Try to read allowed discrete frequencies
```sh
su -c 'cat /sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies'
```

Note: this file may not exist. Some kernels expose only a range, not a list.

## Setting min/max temporarily

Temporary means the change lasts until reboot, and it may also be overridden earlier by other system components.

Example:

```sh
su -c 'echo 1200000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq'
su -c 'echo 2400000 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'
```

## Why a change may not stick
Even on rooted Android, frequency settings can be overwritten by:

- reboot
- thermal throttling / thermal daemon
- vendor performance services
- kernel policy refresh
- CPU governor behavior

## What “temporary” means

A temporary change:
- usually lasts until reboot
- may disappear sooner if another service rewrites the values

A persistent change requires reapplying the settings on boot, such as via:
- an init script
- a Magisk service script
- a rooted app that runs at boot
- a vendor-specific persistence mechanism

## Practical app design notes

For an Android kernel manager app, the app should:

1. Detect available policies:
   - enumerate `/sys/devices/system/cpu/cpufreq/policy*/`
2. Read per-policy files:
   - `cpuinfo_min_freq`
   - `cpuinfo_max_freq`
   - `scaling_min_freq`
   - `scaling_max_freq`
   - `scaling_governor`
3. Optionally read `scaling_available_frequencies` if present
4. Validate any user-selected value before writing:
   - must be within `cpuinfo_min_freq` and `cpuinfo_max_freq`
   - ideally should also match an allowed frequency if the kernel exposes a list
5. Apply settings using root
6. Reapply on boot if the user wants persistence

## Suggested validation logic

When setting a frequency:
- reject values below `cpuinfo_min_freq`
- reject values above `cpuinfo_max_freq`
- if `scaling_available_frequencies` exists, prefer values from that list
- ensure `scaling_min_freq <= scaling_max_freq`

## Helpful shell patterns

### List policies
```sh
su -c 'ls /sys/devices/system/cpu/cpufreq/'
```

### Show all policy min/max values
```sh
for p in /sys/devices/system/cpu/cpufreq/policy*/; do
  echo "== $p =="
  cat "$p/cpuinfo_min_freq"
  cat "$p/cpuinfo_max_freq"
  cat "$p/scaling_min_freq"
  cat "$p/scaling_max_freq"
  echo
 done
```

### Set the same limits on all policies
```sh
for p in /sys/devices/system/cpu/cpufreq/policy*/; do
  echo 1200000 > "$p/scaling_min_freq"
  echo 2400000 > "$p/scaling_max_freq"
done
```

## Cautions

- Android device kernels vary a lot
- some files may be read-only depending on kernel/vendor implementation
- thermal management can override user settings
- changing CPU frequency limits can affect stability, heat, battery life, and performance

## Summary

- Android rooted devices often expose CPU frequency controls under `/sys/devices/system/cpu/cpufreq/policy*/`
- `cpuinfo_min_freq` / `cpuinfo_max_freq` tell you the supported range
- `scaling_min_freq` / `scaling_max_freq` are the writable runtime limits
- values are in kHz
- temporary changes reset on reboot and may be overridden by system services

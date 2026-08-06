package com.example.kernelman.profile

import com.example.kernelman.cpu.CpuError
import com.example.kernelman.cpu.CpuException
import com.example.kernelman.gpu.GpuError
import com.example.kernelman.gpu.GpuException
import com.example.kernelman.swap.SwapError
import com.example.kernelman.swap.SwapException

fun Throwable.toKernelProfileErrorMessage(): String =
  when (this) {
    is CpuException -> error.summary
    is GpuException -> error.summary
    is SwapException -> error.summary
    is IllegalArgumentException -> message ?: "Invalid input"
    else -> message ?: "Unknown error"
  }

fun Throwable.isRetryableBootApplyFailure(): Boolean =
  when (this) {
    is CpuException -> error.isRetryableBootApplyFailure()
    is GpuException -> error.isRetryableBootApplyFailure()
    is SwapException -> error.isRetryableBootApplyFailure()
    else -> false
  }

private fun CpuError.isRetryableBootApplyFailure() =
  when (this) {
    CpuError.RootUnavailable,
    is CpuError.NoPoliciesFound,
    is CpuError.MissingNode,
    is CpuError.RootCommandFailed -> true
    is CpuError.ParseFailure,
    is CpuError.Validation,
    is CpuError.Unknown -> false
  }

private fun GpuError.isRetryableBootApplyFailure() =
  when (this) {
    GpuError.RootUnavailable,
    is GpuError.NoPoliciesFound,
    is GpuError.MissingNode,
    is GpuError.RootCommandFailed -> true
    is GpuError.ParseFailure,
    is GpuError.Validation,
    is GpuError.Unknown -> false
  }

private fun SwapError.isRetryableBootApplyFailure() =
  when (this) {
    SwapError.RootUnavailable,
    is SwapError.RootCommandFailed,
    is SwapError.VerificationFailed -> true
    is SwapError.ParseFailure,
    SwapError.NoConfiguredZram,
    is SwapError.Unknown -> false
  }

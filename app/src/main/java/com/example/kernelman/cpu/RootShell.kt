package com.example.kernelman.cpu

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ShellResult(
  val command: String,
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
)

object RootShell {
  private const val tag = "RootShell"

  suspend fun run(command: String): ShellResult =
    withContext(Dispatchers.IO) {
      Log.d(tag, "run() command=$command")

      val process =
        try {
          ProcessBuilder("su", "-c", command).start()
        } catch (exception: IOException) {
          Log.e(tag, "run() su unavailable", exception)
          throw CpuException(CpuError.RootUnavailable, exception)
        }

      val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
      val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
      val exitCode = process.waitFor()

      Log.d(tag, "run() exitCode=$exitCode stdout=$stdout stderr=$stderr")
      ShellResult(command = command, exitCode = exitCode, stdout = stdout, stderr = stderr)
    }
}

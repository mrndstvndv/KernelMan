package com.example.kernelman.swap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapApiTest {
  @Test
  fun `parseSwapTable reads active swap entries`() {
    val snapshot =
      SwapSnapshot(
        SwapApi.parseSwapTable(
          """
          Filename                                Type            Size            Used            Priority
          /dev/block/zram0                         partition       5771132         3065756        -2
          """.trimIndent(),
        ),
      )

    assertEquals(1, snapshot.devices.size)
    assertEquals("/dev/block/zram0", snapshot.devices.single().path)
    assertEquals(5_771_132L, snapshot.totalSizeKb)
    assertEquals(3_065_756L, snapshot.totalUsedKb)
    assertTrue(snapshot.devices.single().isZram)
  }

  @Test
  fun `parseSwapTable returns empty list for header only`() {
    val devices = SwapApi.parseSwapTable("Filename Type Size Used Priority")

    assertTrue(devices.isEmpty())
  }

  @Test(expected = SwapException::class)
  fun `parseSwapTable rejects malformed rows`() {
    SwapApi.parseSwapTable(
      """
      Filename Type Size Used Priority
      /dev/block/zram0 partition not-a-number 0 -2
      """.trimIndent(),
    )
  }
}

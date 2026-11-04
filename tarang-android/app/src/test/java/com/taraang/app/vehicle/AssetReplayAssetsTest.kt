package com.taraang.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetReplayAssetsTest {
    @Test
    fun `expected-result oracles and non-json files are not listed as replays`() {
        val files = arrayOf(
            "demo_l2_warning_brake.json", "demo_l1_signal_miss.expected.json", "demo_l1_signal_miss.json",
            "demo_l2_warning_brake.expected.json", "README.md",
        )
        assertEquals(listOf("demo_l1_signal_miss.json", "demo_l2_warning_brake.json"), AssetReplayAssets.replayFileNames(files))
        assertEquals(emptyList<String>(), AssetReplayAssets.replayFileNames(null))
    }
}

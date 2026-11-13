package com.maichimod.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun unknownSavedThemeKeepsUsableDefault() {
        assertEquals(ThemeColor.LIME, ThemeColor.fromKey(null))
        assertEquals(ThemeColor.LIME, ThemeColor.fromKey("removed-theme"))
    }
    @Test fun medioraKeysRoundTripWithoutDependingOnLabels() {
        val keys = listOf("lime", "emerald", "charcoal", "blue", "antd-blue", "violet", "rose", "amber", "coral")
        assertEquals(keys, ThemeColor.entries.map { it.key })
        for (key in keys) assertEquals(key, ThemeColor.fromKey(key).key)
        assertTrue(ThemeColor.entries.map { it.overlay }.distinct().size == keys.size)
    }
}

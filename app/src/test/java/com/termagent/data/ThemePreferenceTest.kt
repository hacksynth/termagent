package com.termagent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme enums are persisted as strings, so their storage keys are part of the on-disk
 * contract: renaming one silently resets every existing user's preference. These tests pin
 * the keys and the decode/fallback behaviour.
 */
class ThemePreferenceTest {

    @Test
    fun `theme mode keys are stable`() {
        assertEquals("dark", ThemeMode.Dark.storageKey)
        assertEquals("light", ThemeMode.Light.storageKey)
        assertEquals("system", ThemeMode.System.storageKey)
        assertEquals("monet_dark", ThemeMode.MonetDark.storageKey)
        assertEquals("monet_light", ThemeMode.MonetLight.storageKey)
    }

    @Test
    fun `theme style keys are stable`() {
        assertEquals("default", ThemeStyle.Default.storageKey)
        assertEquals("deepseek", ThemeStyle.DeepSeek.storageKey)
    }

    @Test
    fun `every theme mode survives a storage round trip`() {
        for (mode in ThemeMode.entries) {
            assertEquals(mode, ThemeMode.fromKey(mode.storageKey))
        }
    }

    @Test
    fun `every theme style survives a storage round trip`() {
        for (style in ThemeStyle.entries) {
            assertEquals(style, ThemeStyle.fromKey(style.storageKey))
        }
    }

    @Test
    fun `missing or unknown keys fall back to defaults`() {
        // A fresh install, and a pref written by a future version we do not understand.
        assertEquals(ThemeMode.Dark, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.Dark, ThemeMode.fromKey("solarized"))
        assertEquals(ThemeStyle.Default, ThemeStyle.fromKey(null))
        assertEquals(ThemeStyle.Default, ThemeStyle.fromKey("nord"))
    }

    @Test
    fun `storage keys are unique within each enum`() {
        // Duplicates would make fromKey() ambiguous and shadow an entry forever.
        assertEquals(ThemeMode.entries.size, ThemeMode.entries.map { it.storageKey }.toSet().size)
        assertEquals(ThemeStyle.entries.size, ThemeStyle.entries.map { it.storageKey }.toSet().size)
    }

    @Test
    fun `monet modes are detected`() {
        assertTrue(ThemeMode.MonetDark.isMonet)
        assertTrue(ThemeMode.MonetLight.isMonet)
        assertFalse(ThemeMode.Dark.isMonet)
        assertFalse(ThemeMode.Light.isMonet)
        assertFalse(ThemeMode.System.isMonet)
    }

    @Test
    fun `monet modes demote to their plain counterpart`() {
        // Used to preview a brand style: a wallpaper seed cannot carry a brand palette.
        assertEquals(ThemeMode.Dark, ThemeMode.MonetDark.withoutMonet())
        assertEquals(ThemeMode.Light, ThemeMode.MonetLight.withoutMonet())
        assertEquals(ThemeMode.System, ThemeMode.System.withoutMonet())
        assertEquals(ThemeMode.Dark, ThemeMode.Dark.withoutMonet())
        assertEquals(ThemeMode.Light, ThemeMode.Light.withoutMonet())
    }
}

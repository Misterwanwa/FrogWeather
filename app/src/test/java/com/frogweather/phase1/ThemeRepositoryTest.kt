package com.frogweather.phase1

import com.frogweather.data.AppThemeId
import com.frogweather.data.ThemeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRepositoryTest {

    @Test
    fun defaultTheme_isAeroClassicAndNotPlaceholder() {
        val repo = ThemeRepository(InMemoryKeyValueStore())
        val selected = repo.getSelectedTheme()
        assertEquals(AppThemeId.AERO_CLASSIC, selected)
        assertFalse(selected.isPlaceholder)
    }

    @Test
    fun availableThemes_containsAeroClassicPlusFourPlaceholders() {
        val repo = ThemeRepository(InMemoryKeyValueStore())
        val themes = repo.getAvailableThemes()
        assertEquals(5, themes.size)
        assertEquals(1, themes.count { !it.isPlaceholder })
        assertEquals(4, themes.count { it.isPlaceholder })
        assertTrue(themes.contains(AppThemeId.AERO_CLASSIC))
        assertTrue(themes.contains(AppThemeId.FROST_GLASS))
        assertTrue(themes.contains(AppThemeId.SOLAR_WARM))
        assertTrue(themes.contains(AppThemeId.FOREST_CANOPY))
        assertTrue(themes.contains(AppThemeId.NIGHT_AURORA))
    }

    @Test
    fun setSelectedTheme_persistsAcrossInstancesForAllThemes() {
        val store = InMemoryKeyValueStore()
        val writer = ThemeRepository(store)
        val reader = ThemeRepository(store)

        AppThemeId.entries.forEach { theme ->
            writer.setSelectedTheme(theme)
            assertEquals(theme, reader.getSelectedTheme())
        }
    }

    @Test
    fun unknownStoredThemeId_fallsBackToAeroClassic() {
        val store = InMemoryKeyValueStore()
        store.putString("selected_app_theme", "nonexistent_theme_xyz")
        val repo = ThemeRepository(store)

        assertEquals(AppThemeId.AERO_CLASSIC, repo.getSelectedTheme())
    }

    @Test
    fun fromId_handlesCaseVariantsAndWhitespacePadding() {
        assertEquals(AppThemeId.FROST_GLASS, AppThemeId.fromId("Frost_Glass"))
        assertEquals(AppThemeId.FROST_GLASS, AppThemeId.fromId(" frost_glass "))
        assertEquals(AppThemeId.NIGHT_AURORA, AppThemeId.fromId("NIGHT_AURORA"))
        assertEquals(AppThemeId.NIGHT_AURORA, AppThemeId.fromId("  night_aurora\n"))
        assertEquals(AppThemeId.AERO_CLASSIC, AppThemeId.fromId("   "))
        assertEquals(AppThemeId.AERO_CLASSIC, AppThemeId.fromId(null))

        val store = InMemoryKeyValueStore()
        store.putString("selected_app_theme", "  Frost_Glass ")
        assertEquals(AppThemeId.FROST_GLASS, ThemeRepository(store).getSelectedTheme())
    }
}

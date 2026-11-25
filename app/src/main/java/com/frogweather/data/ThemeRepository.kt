package com.frogweather.data

enum class AppThemeId(
    val id: String,
    val displayName: String,
    val description: String,
    val isPlaceholder: Boolean
) {
    AERO_CLASSIC(
        id = "aero_classic",
        displayName = "Aero Classic",
        description = "Klassisches FrogWeather Blau-Glas-Design",
        isPlaceholder = false
    ),
    FROST_GLASS(
        id = "frost_glass",
        displayName = "Frost Glass",
        description = "Kühles Eis- & Polar-Farbschema (Platzhalter)",
        isPlaceholder = true
    ),
    SOLAR_WARM(
        id = "solar_warm",
        displayName = "Solar Warm",
        description = "Warme Sonnenuntergangs-Akzente (Platzhalter)",
        isPlaceholder = true
    ),
    FOREST_CANOPY(
        id = "forest_canopy",
        displayName = "Forest Canopy",
        description = "Natürliches Waldgrün-Ambiente (Platzhalter)",
        isPlaceholder = true
    ),
    NIGHT_AURORA(
        id = "night_aurora",
        displayName = "Night Aurora",
        description = "Tiefes Nacht- & Polarlicht-Design (Platzhalter)",
        isPlaceholder = true
    );

    companion object {
        fun fromId(raw: String?): AppThemeId {
            val normalized = raw?.trim() ?: return AERO_CLASSIC
            if (normalized.isEmpty()) return AERO_CLASSIC
            return entries.firstOrNull {
                it.id.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: AERO_CLASSIC
        }
    }
}

class ThemeRepository(private val storage: KeyValueStore) {
    companion object {
        private const val KEY_SELECTED_THEME = "selected_app_theme"
    }

    fun getSelectedTheme(): AppThemeId =
        AppThemeId.fromId(storage.getString(KEY_SELECTED_THEME, null))

    fun setSelectedTheme(themeId: AppThemeId) {
        storage.putString(KEY_SELECTED_THEME, themeId.id)
    }

    fun getAvailableThemes(): List<AppThemeId> = AppThemeId.entries
}

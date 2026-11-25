package com.frogweather.e2e

import com.frogweather.WeatherType
import com.frogweather.data.AppThemeId
import com.frogweather.data.CityImagePromptGenerator
import com.frogweather.data.FavoriteLocation
import com.frogweather.data.FavoritesRepository
import com.frogweather.data.ThemeRepository
import com.frogweather.data.WeatherScenario
import com.frogweather.ui.LiveEffectMode
import com.frogweather.ui.resolveLiveEffectMode
import com.frogweather.ui.theme.paletteForTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage A opaque-box E2E tests for Phase 1 (Features 1-4):
 * - F1: Favorite Locations (`FavoritesRepository`, `FavoriteLocation`) — 5 T1 + 6 T2 = 11 tests
 * - F2: Selectable App-Theme Architecture (`ThemeRepository`, `AppThemeId`, `paletteForTheme`) — 5 T1 + 5 T2 = 10 tests
 * - F3: City Diorama AI Prompt Generator (`CityImagePromptGenerator`) — 6 T1 + 6 T2 = 12 tests
 * - F4: Live Weather UI Overlay (`resolveLiveEffectMode`, `LiveEffectMode`) — 5 T1 + 5 T2 = 10 tests
 *
 * Total: 21 Tier 1 + 22 Tier 2 = 43 tests.
 *
 * Aligned against M1 public APIs in `data/KeyValueStore.kt`, `data/PromptGenerator.kt`,
 * `ui/theme/Theme.kt`, and `ui/LiveWeatherOverlay.kt`.
 */
class E2EPhase1FeatureTests {

    // =========================================================================
    // FEATURE 1: Favorite Locations (FavoritesRepository) — Tier 1 (5 tests)
    // =========================================================================

    @Test
    fun f1_t1_addSingleFavoriteAndRetrieve() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)
        val berlin = E2ETestFixtures.berlinFavorite()

        val afterAdd = repo.addFavorite(berlin)
        val fetched = repo.getFavorites()

        assertEquals(1, afterAdd.size)
        assertEquals(1, fetched.size)
        assertEquals(berlin.id, fetched[0].id)
        assertEquals("Berlin", fetched[0].name)
        assertEquals("Deutschland", fetched[0].country)
        assertEquals(52.5200, fetched[0].latitude, 1e-6)
        assertEquals(13.4050, fetched[0].longitude, 1e-6)
    }

    @Test
    fun f1_t1_addMultipleFavoritesPreservesCollection() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)
        val berlin = E2ETestFixtures.berlinFavorite()
        val munich = E2ETestFixtures.munichFavorite()
        val tokyo = E2ETestFixtures.tokyoFavorite()

        repo.addFavorite(berlin)
        repo.addFavorite(munich)
        val all = repo.addFavorite(tokyo)

        assertEquals(3, all.size)
        assertEquals(setOf("berlin_de", "munich_de", "tokyo_jp"), all.map { it.id }.toSet())
        assertEquals(all, repo.getFavorites())
    }

    @Test
    fun f1_t1_switchActiveFavoriteId() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)
        val berlin = E2ETestFixtures.berlinFavorite()
        val munich = E2ETestFixtures.munichFavorite()
        repo.addFavorite(berlin)
        repo.addFavorite(munich)

        repo.setActiveFavoriteId(munich.id)
        assertEquals(munich.id, repo.getActiveFavoriteId())

        repo.setActiveFavoriteId(berlin.id)
        assertEquals(berlin.id, repo.getActiveFavoriteId())
    }

    @Test
    fun f1_t1_removeFavoriteDeletesEntry() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)
        val berlin = E2ETestFixtures.berlinFavorite()
        val munich = E2ETestFixtures.munichFavorite()
        repo.addFavorite(berlin)
        repo.addFavorite(munich)

        val remaining = repo.removeFavorite(berlin.id)

        assertEquals(1, remaining.size)
        assertEquals(munich.id, remaining[0].id)
        assertEquals(listOf(munich), repo.getFavorites())
    }

    @Test
    fun f1_t1_persistsAcrossRepositoryReinstantiation() {
        val sharedStore = E2ETestFixtures.InMemoryKeyValueStore()
        val repo1 = FavoritesRepository(sharedStore)
        val berlin = E2ETestFixtures.berlinFavorite()
        val tokyo = E2ETestFixtures.tokyoFavorite()

        repo1.addFavorite(berlin)
        repo1.addFavorite(tokyo)
        repo1.setActiveFavoriteId(tokyo.id)

        val repo2 = FavoritesRepository(sharedStore)
        val restoredFavorites = repo2.getFavorites()

        assertEquals(2, restoredFavorites.size)
        assertTrue(restoredFavorites.any { it.id == berlin.id && it.name == "Berlin" })
        assertTrue(restoredFavorites.any { it.id == tokyo.id && it.name == "Tokyo" })
        assertEquals(tokyo.id, repo2.getActiveFavoriteId())
    }

    // =========================================================================
    // FEATURE 1: Favorite Locations (FavoritesRepository) — Tier 2 (6 tests)
    // =========================================================================

    @Test
    fun f1_t2_coordinateDeduplicationWithinTolerance() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)

        val berlin = E2ETestFixtures.berlinFavorite() // 52.5200, 13.4050
        // Within <= 0.01 deg on both axes (deltaLat = 0.005, deltaLon = 0.008)
        val nearbyDifferentId = FavoriteLocation(
            id = "berlin_mitte",
            name = "Berlin Mitte",
            country = "Deutschland",
            latitude = 52.5250,
            longitude = 13.4130,
            addedAtMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + 5_000L
        )
        // Exactly at 0.01 deg boundary
        val exactBoundary = FavoriteLocation(
            id = "berlin_boundary",
            name = "Berlin Boundary",
            country = "Deutschland",
            latitude = 52.5300,
            longitude = 13.4050,
            addedAtMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + 6_000L
        )

        repo.addFavorite(berlin)
        repo.addFavorite(nearbyDifferentId)
        assertEquals(
            "Coordinate within <= 0.01° tolerance must deduplicate",
            1,
            repo.getFavorites().size
        )

        repo.addFavorite(exactBoundary)
        assertEquals(
            "Coordinate at exact 0.01° tolerance boundary must deduplicate",
            1,
            repo.getFavorites().size
        )
    }

    @Test
    fun f1_t2_distinctCitiesOutsideToleranceNotDeduplicated() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)

        val center = E2ETestFixtures.sampleFavorite(
            id = "loc_a",
            name = "City A",
            latitude = 52.5200,
            longitude = 13.4050
        )
        // deltaLat = 0.015 > 0.01
        val distinctNeighbor = E2ETestFixtures.sampleFavorite(
            id = "loc_b",
            name = "City B",
            latitude = 52.5350,
            longitude = 13.4050
        )

        repo.addFavorite(center)
        val result = repo.addFavorite(distinctNeighbor)

        assertEquals(2, result.size)
        assertEquals(setOf("loc_a", "loc_b"), result.map { it.id }.toSet())
    }

    @Test
    fun f1_t2_removeNonExistentIdIsNoOp() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)
        val berlin = E2ETestFixtures.berlinFavorite()
        repo.addFavorite(berlin)

        val afterRemove = repo.removeFavorite("non_existent_city_id")

        assertEquals(1, afterRemove.size)
        assertEquals(berlin.id, afterRemove[0].id)
    }

    @Test
    fun f1_t2_removingActiveFavoriteClearsOrUpdatesActiveSelection() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)
        val berlin = E2ETestFixtures.berlinFavorite()
        val munich = E2ETestFixtures.munichFavorite()
        repo.addFavorite(berlin)
        repo.addFavorite(munich)
        repo.setActiveFavoriteId(berlin.id)

        repo.removeFavorite(berlin.id)

        val activeAfterRemoval = repo.getActiveFavoriteId()
        assertNotEquals(
            "Removed favorite ID must no longer remain active",
            berlin.id,
            activeAfterRemoval
        )
        assertTrue(
            "Active ID after removal should be null or point to an existing remaining favorite",
            activeAfterRemoval == null || repo.getFavorites().any { it.id == activeAfterRemoval }
        )
    }

    @Test
    fun f1_t2_emptyStoreReturnsEmptyListAndNullActiveId() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)

        assertTrue(repo.getFavorites().isEmpty())
        assertNull(repo.getActiveFavoriteId())
    }

    @Test
    fun f1_t2_corruptedJsonInStoreRecoversGracefully() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val probeRepo = FavoritesRepository(store)
        probeRepo.addFavorite(E2ETestFixtures.berlinFavorite())

        // Corrupt all stored string keys via opaque-box store inspection
        store.allKeys().forEach { key ->
            store.putString(key, "{not-valid-json[[[")
        }

        val recoveredRepo = FavoritesRepository(store)
        val favorites = recoveredRepo.getFavorites()
        assertTrue("Corrupt store payload should degrade safely to empty list", favorites.isEmpty())
    }

    // =========================================================================
    // FEATURE 2: Selectable App-Theme Architecture (ThemeRepository) — Tier 1 (5 tests)
    // =========================================================================

    @Test
    fun f2_t1_defaultThemeIsAeroClassicNonPlaceholder() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = ThemeRepository(store)

        val defaultTheme = repo.getSelectedTheme()

        assertEquals(AppThemeId.AERO_CLASSIC, defaultTheme)
        assertFalse(defaultTheme.isPlaceholder)
    }

    @Test
    fun f2_t1_availableThemesIncludeAeroAndAllPlaceholders() {
        val allThemes = AppThemeId.values().toList()

        assertTrue(allThemes.contains(AppThemeId.AERO_CLASSIC))
        assertTrue(allThemes.contains(AppThemeId.FROST_GLASS))
        assertTrue(allThemes.contains(AppThemeId.SOLAR_WARM))
        assertTrue(allThemes.contains(AppThemeId.FOREST_CANOPY))
        assertTrue(allThemes.contains(AppThemeId.NIGHT_AURORA))

        assertFalse(AppThemeId.AERO_CLASSIC.isPlaceholder)
        assertTrue(AppThemeId.FROST_GLASS.isPlaceholder)
        assertTrue(AppThemeId.SOLAR_WARM.isPlaceholder)
        assertTrue(AppThemeId.FOREST_CANOPY.isPlaceholder)
        assertTrue(AppThemeId.NIGHT_AURORA.isPlaceholder)
    }

    @Test
    fun f2_t1_setAndPersistPlaceholderThemeAcrossRestart() {
        val sharedStore = E2ETestFixtures.InMemoryKeyValueStore()
        val repo1 = ThemeRepository(sharedStore)

        repo1.setSelectedTheme(AppThemeId.NIGHT_AURORA)
        assertEquals(AppThemeId.NIGHT_AURORA, repo1.getSelectedTheme())

        val repo2 = ThemeRepository(sharedStore)
        assertEquals(AppThemeId.NIGHT_AURORA, repo2.getSelectedTheme())
    }

    @Test
    fun f2_t1_distinctThemeMetadataAcrossAllEntries() {
        val allThemes = AppThemeId.values().toList()
        val uniqueIds = allThemes.map { it.id }.toSet()
        val uniqueDisplayNames = allThemes.map { it.displayName }.toSet()

        assertEquals(allThemes.size, uniqueIds.size)
        assertEquals(allThemes.size, uniqueDisplayNames.size)
        allThemes.forEach { theme ->
            assertTrue("Description for ${theme.name} must not be blank", theme.description.isNotBlank())
        }
    }

    @Test
    fun f2_t1_switchBackToAeroClassicFromPlaceholder() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo1 = ThemeRepository(store)

        repo1.setSelectedTheme(AppThemeId.SOLAR_WARM)
        assertEquals(AppThemeId.SOLAR_WARM, repo1.getSelectedTheme())

        repo1.setSelectedTheme(AppThemeId.AERO_CLASSIC)
        val repo2 = ThemeRepository(store)
        assertEquals(AppThemeId.AERO_CLASSIC, repo2.getSelectedTheme())
    }

    // =========================================================================
    // FEATURE 2: Selectable App-Theme Architecture (ThemeRepository) — Tier 2 (5 tests)
    // =========================================================================

    @Test
    fun f2_t2_unknownStoredThemeIdFallsBackToAeroClassic() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val probeRepo = ThemeRepository(store)
        probeRepo.setSelectedTheme(AppThemeId.FROST_GLASS)

        val keysWritten = store.allKeys()
        assertTrue("ThemeRepository should persist a key in KeyValueStore", keysWritten.isNotEmpty())
        keysWritten.forEach { key -> store.putString(key, "unknown_future_theme_v99") }

        val repoAfterCorrupt = ThemeRepository(store)
        assertEquals(AppThemeId.AERO_CLASSIC, repoAfterCorrupt.getSelectedTheme())
    }

    @Test
    fun f2_t2_rapidConsecutiveSwitchesPersistLastSelection() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val repo = ThemeRepository(store)

        repo.setSelectedTheme(AppThemeId.AERO_CLASSIC)
        repo.setSelectedTheme(AppThemeId.FROST_GLASS)
        repo.setSelectedTheme(AppThemeId.SOLAR_WARM)
        repo.setSelectedTheme(AppThemeId.FOREST_CANOPY)
        repo.setSelectedTheme(AppThemeId.NIGHT_AURORA)

        assertEquals(AppThemeId.NIGHT_AURORA, repo.getSelectedTheme())
        assertEquals(AppThemeId.NIGHT_AURORA, ThemeRepository(store).getSelectedTheme())
    }

    @Test
    fun f2_t2_blankStoredThemeValueFallsBackToDefault() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val probeRepo = ThemeRepository(store)
        probeRepo.setSelectedTheme(AppThemeId.FOREST_CANOPY)

        store.allKeys().forEach { key -> store.putString(key, "   ") }
        assertEquals(AppThemeId.AERO_CLASSIC, ThemeRepository(store).getSelectedTheme())

        store.allKeys().forEach { key -> store.putString(key, "") }
        assertEquals(AppThemeId.AERO_CLASSIC, ThemeRepository(store).getSelectedTheme())
    }

    @Test
    fun f2_t2_allThemesHaveNonBlankIdAndDisplayName() {
        AppThemeId.values().forEach { theme ->
            assertTrue("${theme.name} id must be non-blank", theme.id.isNotBlank())
            assertEquals("${theme.name} id must be trimmed", theme.id.trim(), theme.id)
            assertTrue("${theme.name} displayName must be non-blank", theme.displayName.isNotBlank())
            assertTrue("${theme.name} description must be non-blank", theme.description.isNotBlank())
        }
    }

    @Test
    fun f2_t2_distinctPaletteConfigsPerTheme() {
        val palettes = AppThemeId.values().map { themeId ->
            themeId to paletteForTheme(themeId)
        }

        val distinctPrimaryColors = palettes.map { (_, palette) -> palette.materialColors.primary }.toSet()
        val distinctScreenBackgrounds = palettes.map { (_, palette) -> palette.screenBackground }.toSet()
        val distinctPalettes = palettes.map { (_, palette) -> palette }.toSet()

        assertTrue(
            "Themes should expose visually distinct palette configurations",
            distinctPrimaryColors.size >= 4 &&
                distinctScreenBackgrounds.size == AppThemeId.values().size &&
                distinctPalettes.size == AppThemeId.values().size
        )
    }

    // =========================================================================
    // FEATURE 3: City Diorama AI Prompt Generator — Tier 1 (6 tests)
    // =========================================================================

    @Test
    fun f3_t1_exactVerbatimTemplateForTokyoPartlyCloudy() {
        val expected = """
            CITY=Tokyo, Japan
            TEMPERATURE=16° C
            WEATHER = Partly Clouded
            ADDITIONAL_LANDMARKS (optional): NONE

            Present a clear, 45° top-down isometric miniature 3D cartoon scene of CITY, featuring its most iconic landmarks and architectural elements. Add ADDITIONAL_LANDMARKS if filled. Use soft, refined textures with realistic PBR materials and gentle, lifelike lighting and shadows. Integrate the current weather conditions directly into the city environment to create an immersive atmospheric mood.
            Use a clean, minimalistic composition with a soft, solid-colored background.

            Weather is: TEMPERATURE + WEATHER

            No Text
            Square 1080x1080 dimension.
        """.trimIndent()

        val actual = CityImagePromptGenerator.buildPrompt(
            cityLabel = "Tokyo, Japan",
            scenario = WeatherScenario.PARTLY_CLOUDY,
            additionalLandmarks = null
        )

        assertEquals(expected, actual)
    }

    @Test
    fun f3_t1_typicalTemperaturesMatchScenarioSpec() {
        assertEquals(26, CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.SUNNY))
        assertEquals(-2, CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.SNOW))
        assertEquals(16, CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.PARTLY_CLOUDY))

        val sunnyPrompt = CityImagePromptGenerator.buildPrompt("Madrid, Spain", WeatherScenario.SUNNY)
        val snowPrompt = CityImagePromptGenerator.buildPrompt("Oslo, Norway", WeatherScenario.SNOW)

        assertTrue(sunnyPrompt.contains("TEMPERATURE=26° C"))
        assertTrue(snowPrompt.contains("TEMPERATURE=-2° C"))
        assertTrue(
            CityImagePromptGenerator.typicalTemperatureCelsius(WeatherScenario.BLIZZARD) < 0
        )
    }

    @Test
    fun f3_t1_customAdditionalLandmarksInserted() {
        val prompt = CityImagePromptGenerator.buildPrompt(
            cityLabel = "Berlin, Deutschland",
            scenario = WeatherScenario.SUNNY,
            additionalLandmarks = "Fernsehturm, Brandenburger Tor"
        )

        assertTrue(prompt.contains("CITY=Berlin, Deutschland"))
        assertTrue(prompt.contains("ADDITIONAL_LANDMARKS (optional): Fernsehturm, Brandenburger Tor"))
        assertFalse(prompt.contains("ADDITIONAL_LANDMARKS (optional): NONE"))
    }

    @Test
    fun f3_t1_buildPromptsForMissingCoversAllPassedScenarios() {
        val missing = listOf(
            WeatherScenario.SUNNY,
            WeatherScenario.RAIN,
            WeatherScenario.NIGHT_CLEAR
        )

        val generated = CityImagePromptGenerator.buildPromptsForMissing(
            cityLabel = "München, Deutschland",
            missingScenarios = missing,
            additionalLandmarks = "Frauenkirche"
        )

        assertEquals(3, generated.size)
        assertEquals(missing, generated.map { it.scenario })
        generated.forEach { item ->
            assertTrue(item.prompt.contains("CITY=München, Deutschland"))
            assertTrue(item.prompt.contains("ADDITIONAL_LANDMARKS (optional): Frauenkirche"))
            assertTrue(
                item.prompt.contains(
                    "WEATHER = ${CityImagePromptGenerator.englishWeatherLabel(item.scenario)}"
                )
            )
        }
    }

    @Test
    fun f3_t1_formatAllPromptsJoinsScenariosCleanly() {
        val missing = listOf(WeatherScenario.SUNNY, WeatherScenario.THUNDERSTORM, WeatherScenario.SNOW)
        val prompts = CityImagePromptGenerator.buildPromptsForMissing("Wien, Österreich", missing)

        val formatted = CityImagePromptGenerator.formatAllPrompts(prompts)

        assertTrue(formatted.isNotBlank())
        assertTrue(formatted.contains(CityImagePromptGenerator.englishWeatherLabel(WeatherScenario.SUNNY)))
        assertTrue(formatted.contains(CityImagePromptGenerator.englishWeatherLabel(WeatherScenario.THUNDERSTORM)))
        assertTrue(formatted.contains(CityImagePromptGenerator.englishWeatherLabel(WeatherScenario.SNOW)))
        val dimensionOccurrences = Regex("Square 1080x1080 dimension\\.").findAll(formatted).count()
        assertEquals(3, dimensionOccurrences)
    }

    @Test
    fun f3_t1_weatherScenarioEnglishLabelsNonGermanAndDistinct() {
        val germanLeakWords = listOf("Sonnig", "Bewölkt", "Regen", "Schnee", "Gewitter", "Nebel", "Nacht")
        val labels = WeatherScenario.values().map { scenario ->
            val label = CityImagePromptGenerator.englishWeatherLabel(scenario)
            assertTrue("English label for $scenario must not be blank", label.isNotBlank())
            germanLeakWords.forEach { german ->
                assertFalse(
                    "Label '$label' for $scenario must be English, not German '$german'",
                    label.contains(german, ignoreCase = true)
                )
            }
            label
        }

        assertEquals(
            "All 13 WeatherScenario English labels should be distinct",
            WeatherScenario.values().size,
            labels.toSet().size
        )
    }

    // =========================================================================
    // FEATURE 3: City Diorama AI Prompt Generator — Tier 2 (6 tests)
    // =========================================================================

    @Test
    fun f3_t2_blankAndWhitespaceLandmarksNormalizeToNone() {
        listOf(null, "", "   ", "\t\n ").forEach { blankInput ->
            val prompt = CityImagePromptGenerator.buildPrompt(
                cityLabel = "Tokyo, Japan",
                scenario = WeatherScenario.PARTLY_CLOUDY,
                additionalLandmarks = blankInput
            )
            assertTrue(
                "Blank landmarks '$blankInput' must normalize to NONE",
                prompt.contains("ADDITIONAL_LANDMARKS (optional): NONE")
            )
        }
    }

    @Test
    fun f3_t2_emptyMissingScenariosYieldsEmptyOutput() {
        val prompts = CityImagePromptGenerator.buildPromptsForMissing("Paris, France", emptyList())
        val formatted = CityImagePromptGenerator.formatAllPrompts(prompts)

        assertTrue(prompts.isEmpty())
        assertTrue(formatted.isEmpty())
    }

    @Test
    fun f3_t2_all13ScenariosProduceValidPromptAndTemp() {
        val scenarios = E2ETestFixtures.allWeatherScenarios()
        assertEquals(13, scenarios.size)

        scenarios.forEach { scenario ->
            val temp = CityImagePromptGenerator.typicalTemperatureCelsius(scenario)
            val label = CityImagePromptGenerator.englishWeatherLabel(scenario)
            val prompt = CityImagePromptGenerator.buildPrompt("Hamburg, Germany", scenario)

            assertTrue("Temp $temp out of realistic bounds for $scenario", temp in -25..45)
            assertTrue(label.isNotBlank())
            assertTrue(prompt.contains("CITY=Hamburg, Germany"))
            assertTrue(prompt.contains("TEMPERATURE=$temp° C"))
            assertTrue(prompt.contains("WEATHER = $label"))
            assertTrue(prompt.contains("ADDITIONAL_LANDMARKS (optional): NONE"))
            assertTrue(prompt.contains("Weather is: TEMPERATURE + WEATHER"))
            assertTrue(prompt.contains("No Text"))
            assertTrue(prompt.contains("Square 1080x1080 dimension."))
        }
    }

    @Test
    fun f3_t2_cityLabelWithCommasAndUnicodePreservedVerbatim() {
        val unicodeCity = "München, Deutschland (Altstadt / Straße)"
        val prompt = CityImagePromptGenerator.buildPrompt(unicodeCity, WeatherScenario.LIGHT_RAIN)

        assertTrue(prompt.lines().first() == "CITY=$unicodeCity")

        val zurich = "Zürich, Schweiz"
        val zurichPrompt = CityImagePromptGenerator.buildPrompt(zurich, WeatherScenario.FOG)
        assertTrue(zurichPrompt.lines().first() == "CITY=$zurich")
    }

    @Test
    fun f3_t2_weatherScenarioMappingHandlesEnglishAndNightWithoutCloudyDefaultBias() {
        assertEquals(WeatherScenario.SUNNY, WeatherScenario.fromWeatherType("CLEAR", isNight = false))
        assertEquals(WeatherScenario.NIGHT_CLEAR, WeatherScenario.fromWeatherType("CLEAR", isNight = true))
        assertEquals(WeatherScenario.SUNNY, WeatherScenario.fromWeatherType("sunny", isNight = false))
        assertEquals(WeatherScenario.CLOUDY, WeatherScenario.fromWeatherType("CLOUDS", isNight = false))
        assertEquals(WeatherScenario.NIGHT_CLOUDY, WeatherScenario.fromWeatherType("CLOUDS", isNight = true))
        assertEquals(WeatherScenario.RAIN, WeatherScenario.fromWeatherType("RAIN", isNight = false))
        assertEquals(WeatherScenario.RAIN, WeatherScenario.fromWeatherType(WeatherType.RAINY.name, isNight = false))
        assertEquals(WeatherScenario.THUNDERSTORM, WeatherScenario.fromWeatherType(WeatherType.THUNDERSTORM.name, isNight = false))
        assertEquals(WeatherScenario.SNOW, WeatherScenario.fromWeatherType(WeatherType.SNOW.name, isNight = false))
        assertEquals(WeatherScenario.FOG, WeatherScenario.fromWeatherType("FOG", isNight = false))
    }

    @Test
    fun f3_t2_customLandmarksWhitespaceTrimmedCleanly() {
        val prompt = CityImagePromptGenerator.buildPrompt(
            cityLabel = "  Köln, Deutschland  ",
            scenario = WeatherScenario.SUNNY,
            additionalLandmarks = "  Kölner Dom, Hohenzollernbrücke  "
        )

        assertTrue(prompt.contains("CITY=Köln, Deutschland"))
        assertTrue(prompt.contains("ADDITIONAL_LANDMARKS (optional): Kölner Dom, Hohenzollernbrücke"))
    }

    // =========================================================================
    // FEATURE 4: Live Weather UI Overlay — Tier 1 (5 tests)
    // =========================================================================

    @Test
    fun f4_t1_rainyConditionMapsToRainOverlay() {
        val coarseRain = resolveLiveEffectMode(
            weatherType = WeatherType.RAINY,
            scenario = null,
            isNight = false
        )
        val lightRain = resolveLiveEffectMode(
            weatherType = WeatherType.RAINY,
            scenario = WeatherScenario.LIGHT_RAIN,
            isNight = false
        )
        val heavyRain = resolveLiveEffectMode(
            weatherType = WeatherType.RAINY,
            scenario = WeatherScenario.HEAVY_RAIN,
            isNight = false
        )

        assertEquals(LiveEffectMode.RAIN, coarseRain)
        assertEquals(LiveEffectMode.LIGHT_RAIN, lightRain)
        assertEquals(LiveEffectMode.HEAVY_RAIN, heavyRain)
        assertEquals(3, setOf(lightRain, coarseRain, heavyRain).size)
    }

    @Test
    fun f4_t1_snowyConditionMapsToSnowOverlay() {
        val coarseSnow = resolveLiveEffectMode(
            weatherType = WeatherType.SNOW,
            scenario = null,
            isNight = false
        )
        val lightSnow = resolveLiveEffectMode(
            weatherType = WeatherType.SNOW,
            scenario = WeatherScenario.LIGHT_SNOW,
            isNight = false
        )
        val blizzard = resolveLiveEffectMode(
            weatherType = WeatherType.SNOW,
            scenario = WeatherScenario.BLIZZARD,
            isNight = false
        )

        assertEquals(LiveEffectMode.SNOW, coarseSnow)
        assertEquals(LiveEffectMode.SNOW, lightSnow)
        assertEquals(LiveEffectMode.BLIZZARD, blizzard)
        assertNotEquals(coarseSnow, blizzard)
    }

    @Test
    fun f4_t1_thunderstormMapsToStormOverlayWithLightning() {
        val coarseStorm = resolveLiveEffectMode(
            weatherType = WeatherType.THUNDERSTORM,
            scenario = null,
            isNight = false
        )
        val nightStorm = resolveLiveEffectMode(
            weatherType = WeatherType.THUNDERSTORM,
            scenario = WeatherScenario.THUNDERSTORM,
            isNight = true
        )

        assertEquals(LiveEffectMode.THUNDERSTORM, coarseStorm)
        assertEquals(LiveEffectMode.THUNDERSTORM, nightStorm)
        assertNotEquals(LiveEffectMode.HEAVY_RAIN, coarseStorm)
        assertNotEquals(LiveEffectMode.RAIN, coarseStorm)
    }

    @Test
    fun f4_t1_fogCloudyClearDayNightMapToDistinctOverlays() {
        val fog = resolveLiveEffectMode(WeatherType.OTHER, WeatherScenario.FOG, isNight = false)
        val cloudy = resolveLiveEffectMode(WeatherType.CLOUDY, scenario = null, isNight = false)
        val clearDay = resolveLiveEffectMode(WeatherType.SUNNY, scenario = null, isNight = false)
        val clearNight = resolveLiveEffectMode(WeatherType.SUNNY, scenario = null, isNight = true)

        val effects = setOf(fog, cloudy, clearDay, clearNight)
        assertEquals(4, effects.size)
        assertEquals(LiveEffectMode.FOG, fog)
        assertEquals(LiveEffectMode.CLOUDY, cloudy)
        assertEquals(LiveEffectMode.SUNNY_GLOW, clearDay)
        assertEquals(LiveEffectMode.CLEAR_NIGHT, clearNight)
    }

    @Test
    fun f4_t1_scenarioOverridesCoarseWeatherTypeAndCoversAllEnums() {
        // Fine-grained WeatherScenario takes precedence over coarse WeatherType
        assertEquals(
            LiveEffectMode.FOG,
            resolveLiveEffectMode(WeatherType.CLOUDY, WeatherScenario.FOG, isNight = false)
        )
        assertEquals(
            LiveEffectMode.HEAVY_RAIN,
            resolveLiveEffectMode(WeatherType.RAINY, WeatherScenario.HEAVY_RAIN, isNight = false)
        )
        assertEquals(
            LiveEffectMode.BLIZZARD,
            resolveLiveEffectMode(WeatherType.SNOW, WeatherScenario.BLIZZARD, isNight = false)
        )

        // Every WeatherScenario and WeatherType resolves cleanly for both day and night
        WeatherScenario.values().forEach { scenario ->
            val dayMode = resolveLiveEffectMode(WeatherType.OTHER, scenario, isNight = false)
            val nightMode = resolveLiveEffectMode(WeatherType.OTHER, scenario, isNight = true)
            assertNotEquals("Scenario $scenario must map to an active overlay mode", LiveEffectMode.NONE, dayMode)
            assertNotEquals("Scenario $scenario at night must map to an active overlay mode", LiveEffectMode.NONE, nightMode)
        }
    }

    // =========================================================================
    // FEATURE 4: Live Weather UI Overlay — Tier 2 (5 tests)
    // =========================================================================

    @Test
    fun f4_t2_unknownOrNullWeatherDefaultsSafelyToNoOpOrClearNight() {
        val fromNullDay = resolveLiveEffectMode(weatherType = null, scenario = null, isNight = false)
        val fromOtherDay = resolveLiveEffectMode(weatherType = WeatherType.OTHER, scenario = null, isNight = false)
        val fromNullNight = resolveLiveEffectMode(weatherType = null, scenario = null, isNight = true)
        val fromOtherNight = resolveLiveEffectMode(weatherType = WeatherType.OTHER, scenario = null, isNight = true)

        assertEquals(LiveEffectMode.NONE, fromNullDay)
        assertEquals(LiveEffectMode.NONE, fromOtherDay)
        assertEquals(LiveEffectMode.CLEAR_NIGHT, fromNullNight)
        assertEquals(LiveEffectMode.CLEAR_NIGHT, fromOtherNight)
    }

    @Test
    fun f4_t2_precipitationAndCloudTiersMapToDistinctModes() {
        val rainTiers = listOf(
            WeatherScenario.LIGHT_RAIN,
            WeatherScenario.RAIN,
            WeatherScenario.HEAVY_RAIN,
            WeatherScenario.THUNDERSTORM
        ).map { resolveLiveEffectMode(WeatherType.RAINY, it, isNight = false) }
        assertEquals(4, rainTiers.toSet().size)

        val cloudTiers = listOf(
            WeatherScenario.PARTLY_CLOUDY,
            WeatherScenario.CLOUDY,
            WeatherScenario.NIGHT_CLOUDY
        ).map { resolveLiveEffectMode(WeatherType.CLOUDY, it, isNight = false) }
        assertTrue(cloudTiers.all { it == LiveEffectMode.CLOUDY })
    }

    @Test
    fun f4_t2_nightFlagSwitchesSunnyGlowToNightMode() {
        val dayMode = resolveLiveEffectMode(weatherType = WeatherType.SUNNY, scenario = null, isNight = false)
        val nightMode = resolveLiveEffectMode(weatherType = WeatherType.SUNNY, scenario = null, isNight = true)
        val sunnyScenarioAtNight = resolveLiveEffectMode(
            weatherType = WeatherType.SUNNY,
            scenario = WeatherScenario.SUNNY,
            isNight = true
        )
        val nightClearScenario = resolveLiveEffectMode(
            weatherType = WeatherType.SUNNY,
            scenario = WeatherScenario.NIGHT_CLEAR,
            isNight = false
        )

        assertEquals(LiveEffectMode.SUNNY_GLOW, dayMode)
        assertEquals(LiveEffectMode.CLEAR_NIGHT, nightMode)
        assertEquals(LiveEffectMode.CLEAR_NIGHT, sunnyScenarioAtNight)
        assertEquals(LiveEffectMode.CLEAR_NIGHT, nightClearScenario)
        assertNotEquals(dayMode, nightMode)
    }

    @Test
    fun f4_t2_precipAndFogModesRemainActiveAtNightWithoutFallingBackToClearNight() {
        val persistentScenarios = listOf(
            WeatherScenario.FOG to LiveEffectMode.FOG,
            WeatherScenario.LIGHT_RAIN to LiveEffectMode.LIGHT_RAIN,
            WeatherScenario.RAIN to LiveEffectMode.RAIN,
            WeatherScenario.HEAVY_RAIN to LiveEffectMode.HEAVY_RAIN,
            WeatherScenario.THUNDERSTORM to LiveEffectMode.THUNDERSTORM,
            WeatherScenario.SNOW to LiveEffectMode.SNOW,
            WeatherScenario.BLIZZARD to LiveEffectMode.BLIZZARD
        )

        persistentScenarios.forEach { (scenario, expectedMode) ->
            val dayResult = resolveLiveEffectMode(null, scenario, isNight = false)
            val nightResult = resolveLiveEffectMode(null, scenario, isNight = true)
            assertEquals(expectedMode, dayResult)
            assertEquals("Night flag must not suppress $scenario overlay", expectedMode, nightResult)
        }
    }

    @Test
    fun f4_t2_rapidWeatherTransitionsAreDeterministicAndAllModesReachable() {
        val sequence = listOf(
            Triple(WeatherType.SUNNY, null, false) to LiveEffectMode.SUNNY_GLOW,
            Triple(WeatherType.RAINY, WeatherScenario.LIGHT_RAIN, false) to LiveEffectMode.LIGHT_RAIN,
            Triple(WeatherType.RAINY, WeatherScenario.RAIN, false) to LiveEffectMode.RAIN,
            Triple(WeatherType.RAINY, WeatherScenario.HEAVY_RAIN, false) to LiveEffectMode.HEAVY_RAIN,
            Triple(WeatherType.THUNDERSTORM, null, true) to LiveEffectMode.THUNDERSTORM,
            Triple(WeatherType.SNOW, WeatherScenario.SNOW, true) to LiveEffectMode.SNOW,
            Triple(WeatherType.SNOW, WeatherScenario.BLIZZARD, true) to LiveEffectMode.BLIZZARD,
            Triple(WeatherType.CLOUDY, null, false) to LiveEffectMode.CLOUDY,
            Triple(WeatherType.OTHER, WeatherScenario.FOG, false) to LiveEffectMode.FOG,
            Triple(WeatherType.OTHER, null, false) to LiveEffectMode.NONE,
            Triple(WeatherType.SUNNY, null, true) to LiveEffectMode.CLEAR_NIGHT
        )

        val resolved = sequence.map { (input, expected) ->
            val first = resolveLiveEffectMode(input.first, input.second, input.third)
            val second = resolveLiveEffectMode(input.first, input.second, input.third)
            assertEquals("Resolution must be deterministic for $input", first, second)
            assertEquals(expected, first)
            first
        }

        assertEquals(
            "Every LiveEffectMode enum entry must be reachable",
            LiveEffectMode.values().toSet(),
            resolved.toSet()
        )
    }
}

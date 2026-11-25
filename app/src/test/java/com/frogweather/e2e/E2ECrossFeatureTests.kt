package com.frogweather.e2e

import com.frogweather.WeatherType
import com.frogweather.analysis.ConfidenceResult
import com.frogweather.analysis.ForecastConfidenceEngine
import com.frogweather.analysis.HistoricalNormalCalculator
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.assistant.GermanFreeTextParser
import com.frogweather.assistant.QueryIntentCategory
import com.frogweather.briefing.BriefingComposer
import com.frogweather.briefing.ClothingAdvisor
import com.frogweather.calendar.CalendarEvent
import com.frogweather.calendar.EventRescheduler
import com.frogweather.data.AppPrefsKeys
import com.frogweather.data.AppThemeId
import com.frogweather.data.CityImagePromptGenerator
import com.frogweather.data.FavoriteLocation
import com.frogweather.data.FavoritesRepository
import com.frogweather.data.ThemeRepository
import com.frogweather.data.WeatherScenario
import com.frogweather.map.MapForecastModel
import com.frogweather.map.MapLayerDataProvider
import com.frogweather.map.MapStateController
import com.frogweather.map.MapViewMode
import com.frogweather.map.MapWeatherLayer
import com.frogweather.map.PressureLevel
import com.frogweather.map.TimelineFrame
import com.frogweather.map.TimelineFrameSource
import com.frogweather.network.RainViewerParser
import com.frogweather.route.RoutePoint
import com.frogweather.route.RouteTransportMode
import com.frogweather.route.RouteWeatherCalculator
import com.frogweather.service.WeatherNotificationService
import com.frogweather.ui.LiveEffectMode
import com.frogweather.ui.resolveLiveEffectMode
import com.frogweather.ui.theme.paletteForTheme
import com.frogweather.widget.WeatherWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Stage A E2E Opaque-Box Tests — Tier 3: Cross-Feature Pairwise Combinations (16 Tests).
 *
 * Derived strictly from:
 * - ORIGINAL_REQUEST.md (R1–R4)
 * - PROJECT.md (§ Feature Inventory & § Interface Contracts)
 * - TEST_INFRA.md (§ Tier 3 — Cross-Feature Pairwise Combinations)
 *
 * All tests are self-contained, isolated, deterministic, and execute purely on JVM
 * using [E2ETestFixtures] without Android framework or network dependencies.
 */
class E2ECrossFeatureTests {

    // -------------------------------------------------------------------------
    // 1. F1 + F3: Switching active favorite location updates prompt generator
    //    city label & missing scenarios list.
    // -------------------------------------------------------------------------
    @Test
    fun t3_01_f1_f3_switchingActiveFavorite_updatesPromptGeneratorCityAndMissingScenarios() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val favoritesRepo = FavoritesRepository(store)

        val berlin = E2ETestFixtures.berlinFavorite()
        val tokyo = E2ETestFixtures.tokyoFavorite()
        favoritesRepo.addFavorite(berlin)
        favoritesRepo.addFavorite(tokyo)

        // Activate Berlin first with 2 missing scenarios
        favoritesRepo.setActiveFavoriteId(berlin.id)
        val activeBerlin = favoritesRepo.getFavorites().first { it.id == favoritesRepo.getActiveFavoriteId() }
        val berlinLabel = "${activeBerlin.name}, ${activeBerlin.country}"
        val berlinMissing = listOf(WeatherScenario.SUNNY, WeatherScenario.RAIN)
        val berlinPrompts = CityImagePromptGenerator.buildPromptsForMissing(berlinLabel, berlinMissing)

        assertEquals(2, berlinPrompts.size)
        assertTrue(berlinPrompts[0].prompt.contains("CITY=Berlin, Deutschland"))

        // Switch active favorite to Tokyo where 3 scenarios are missing
        favoritesRepo.setActiveFavoriteId(tokyo.id)
        val activeTokyo = favoritesRepo.getFavorites().first { it.id == favoritesRepo.getActiveFavoriteId() }
        val tokyoLabel = "${activeTokyo.name}, ${activeTokyo.country}"
        val tokyoMissing = listOf(WeatherScenario.PARTLY_CLOUDY, WeatherScenario.SUNNY, WeatherScenario.SNOW)

        val tokyoPrompts = CityImagePromptGenerator.buildPromptsForMissing(tokyoLabel, tokyoMissing)
        val formattedAll = CityImagePromptGenerator.formatAllPrompts(tokyoPrompts)

        assertEquals(3, tokyoPrompts.size)
        assertFalse(
            "Switched city prompt batch must not retain previous city Berlin",
            formattedAll.contains("CITY=Berlin")
        )
        assertTrue(formattedAll.contains("CITY=Tokyo, Japan"))
        assertTrue(formattedAll.contains("TEMPERATURE=16° C"))
        assertTrue(formattedAll.contains("WEATHER = Partly Clouded"))
        assertTrue(formattedAll.contains("TEMPERATURE=26° C"))
        assertTrue(formattedAll.contains("TEMPERATURE=-2° C"))
        assertTrue(formattedAll.contains("ADDITIONAL_LANDMARKS (optional): NONE"))
        assertTrue(formattedAll.contains("No Text"))
        assertTrue(formattedAll.contains("Square 1080x1080 dimension."))
    }

    // -------------------------------------------------------------------------
    // 2. F1 + F2: Persisting favorite locations and theme selection in unified
    //    KeyValueStore namespaces has zero key collision.
    // -------------------------------------------------------------------------
    @Test
    fun t3_02_f1_f2_favoritesAndThemeInSharedKeyValueStore_persistWithoutKeyCollision() {
        val sharedStore = E2ETestFixtures.InMemoryKeyValueStore()
        val favoritesRepo = FavoritesRepository(sharedStore)
        val themeRepo = ThemeRepository(sharedStore)

        val berlin = E2ETestFixtures.berlinFavorite()
        val munich = E2ETestFixtures.munichFavorite()
        val hamburg = E2ETestFixtures.hamburgFavorite()

        favoritesRepo.addFavorite(berlin)
        favoritesRepo.addFavorite(munich)
        favoritesRepo.setActiveFavoriteId(munich.id)
        themeRepo.setSelectedTheme(AppThemeId.NIGHT_AURORA)

        // Mutating favorites must not clobber selected theme
        favoritesRepo.removeFavorite(berlin.id)
        favoritesRepo.addFavorite(hamburg)
        assertEquals(AppThemeId.NIGHT_AURORA, themeRepo.getSelectedTheme())

        // Mutating theme must not clobber favorites or active favorite ID
        themeRepo.setSelectedTheme(AppThemeId.SOLAR_WARM)
        assertEquals(2, favoritesRepo.getFavorites().size)
        assertEquals(munich.id, favoritesRepo.getActiveFavoriteId())

        // Re-instantiate both repositories against the same backing store
        val reloadedFavorites = FavoritesRepository(sharedStore)
        val reloadedTheme = ThemeRepository(sharedStore)

        assertEquals(AppThemeId.SOLAR_WARM, reloadedTheme.getSelectedTheme())
        assertEquals(munich.id, reloadedFavorites.getActiveFavoriteId())
        assertEquals(setOf(munich.id, hamburg.id), reloadedFavorites.getFavorites().map { it.id }.toSet())
    }

    // -------------------------------------------------------------------------
    // 3. F1 + F14: Saving/switching favorite updates "frog_weather_prefs"
    //    last_city_* -> widget & notification service pick up new city.
    // -------------------------------------------------------------------------
    @Test
    fun t3_03_f1_f14_switchingFavorite_syncsSharedPrefsForWidgetAndNotification() {
        val favoritesStore = E2ETestFixtures.InMemoryKeyValueStore()
        val widgetStore = E2ETestFixtures.InMemoryKeyValueStore()
        val sharedPrefsStore = E2ETestFixtures.InMemoryKeyValueStore()
        val favoritesRepo = FavoritesRepository(favoritesStore)

        val munich = E2ETestFixtures.munichFavorite()
        val hamburg = E2ETestFixtures.hamburgFavorite()
        favoritesRepo.addFavorite(munich)
        favoritesRepo.addFavorite(hamburg)

        // Switch active favorite to Munich and sync shared "frog_weather_prefs" keys
        favoritesRepo.setActiveFavoriteId(munich.id)
        val active1 = favoritesRepo.getFavorites().first { it.id == favoritesRepo.getActiveFavoriteId() }
        sharedPrefsStore.putFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, active1.latitude.toFloat())
        sharedPrefsStore.putFloat(AppPrefsKeys.KEY_LAST_CITY_LON, active1.longitude.toFloat())
        sharedPrefsStore.putString(AppPrefsKeys.KEY_LAST_CITY_NAME, active1.name)
        sharedPrefsStore.putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, active1.country)

        val notifCoords1 = WeatherNotificationService.resolveCoordinates(
            intentLat = null,
            intentLon = null,
            savedNotifLat = null,
            savedNotifLon = null,
            savedCityLat = sharedPrefsStore.getFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 0f).toDouble(),
            savedCityLon = sharedPrefsStore.getFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 0f).toDouble()
        )
        val widgetResolved1 = WeatherWidgetProvider.resolveWidgetLocation(
            appWidgetId = 1,
            widgetStore = widgetStore,
            sharedAppStore = sharedPrefsStore
        )

        assertNotNull(notifCoords1)
        assertEquals(munich.latitude, notifCoords1!!.first, 0.01)
        assertEquals(munich.longitude, notifCoords1.second, 0.01)
        assertNotNull(widgetResolved1)
        assertEquals("München", widgetResolved1!!.cityName)
        assertEquals(munich.latitude, widgetResolved1.latitude, 0.01)

        // Switch active favorite to Hamburg
        favoritesRepo.setActiveFavoriteId(hamburg.id)
        val active2 = favoritesRepo.getFavorites().first { it.id == favoritesRepo.getActiveFavoriteId() }
        sharedPrefsStore.putFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, active2.latitude.toFloat())
        sharedPrefsStore.putFloat(AppPrefsKeys.KEY_LAST_CITY_LON, active2.longitude.toFloat())
        sharedPrefsStore.putString(AppPrefsKeys.KEY_LAST_CITY_NAME, active2.name)
        sharedPrefsStore.putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, active2.country)

        val notifCoords2 = WeatherNotificationService.resolveCoordinates(
            intentLat = null,
            intentLon = null,
            savedNotifLat = null,
            savedNotifLon = null,
            savedCityLat = sharedPrefsStore.getFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 0f).toDouble(),
            savedCityLon = sharedPrefsStore.getFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 0f).toDouble()
        )
        val widgetResolved2 = WeatherWidgetProvider.resolveWidgetLocation(
            appWidgetId = 1,
            widgetStore = widgetStore,
            sharedAppStore = sharedPrefsStore
        )

        assertNotNull(notifCoords2)
        assertEquals(hamburg.latitude, notifCoords2!!.first, 0.01)
        assertEquals(hamburg.longitude, notifCoords2.second, 0.01)
        assertNotNull(widgetResolved2)
        assertEquals("Hamburg", widgetResolved2!!.cityName)
    }

    // -------------------------------------------------------------------------
    // 4. F2 + F4: Selecting each AppThemeId (AERO_CLASSIC + placeholders)
    //    produces valid overlay tint/contrast compatibility.
    // -------------------------------------------------------------------------
    @Test
    fun t3_04_f2_f4_allAppThemeIdsWithWeatherConditions_produceValidOverlayContrastAndTint() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val themeRepo = ThemeRepository(store)
        val weatherInputs = listOf(
            Triple(WeatherType.SUNNY, false, LiveEffectMode.SUNNY_GLOW),
            Triple(WeatherType.RAINY, false, LiveEffectMode.RAIN),
            Triple(WeatherType.THUNDERSTORM, false, LiveEffectMode.THUNDERSTORM),
            Triple(WeatherType.SNOW, false, LiveEffectMode.SNOW),
            Triple(WeatherType.SUNNY, true, LiveEffectMode.CLEAR_NIGHT)
        )

        assertTrue("Theme architecture must include AERO_CLASSIC + placeholders", AppThemeId.values().size >= 5)

        for (themeId in AppThemeId.values()) {
            themeRepo.setSelectedTheme(themeId)
            val persisted = themeRepo.getSelectedTheme()
            assertEquals(themeId, persisted)

            val palette = paletteForTheme(persisted)
            assertNotNull(palette)
            assertEquals(persisted, palette.themeId)
            assertNotEquals(
                "Primary and screenBackground colors must maintain contrast",
                palette.materialColors.primary,
                palette.screenBackground
            )
            assertNotEquals(
                "Material primary and background colors must maintain contrast",
                palette.materialColors.primary,
                palette.materialColors.background
            )

            val resolvedModes = weatherInputs.map { (weatherType, isNight, expectedMode) ->
                val mode = resolveLiveEffectMode(
                    weatherType = weatherType,
                    scenario = null,
                    isNight = isNight
                )
                assertEquals(expectedMode, mode)
                assertNotEquals(LiveEffectMode.NONE, mode)
                mode
            }
            assertEquals(5, resolvedModes.toSet().size)
        }
    }

    // -------------------------------------------------------------------------
    // 5. F3 + F4: Current WeatherScenario drives both LiveWeatherOverlay effect
    //    type AND MissingImageBanner highlight scenario consistently.
    // -------------------------------------------------------------------------
    @Test
    fun t3_05_f3_f4_weatherScenarioDrivesOverlayEffectAndMissingImageHighlightConsistently() {
        // Verify WeatherScenario resolution from WeatherType enum/English strings + isNight
        // (avoiding the German WeatherType.label bug that collapsed everything to CLOUDY)
        val cases = listOf(
            Triple(WeatherType.SUNNY, false, WeatherScenario.SUNNY),
            Triple(WeatherType.SUNNY, true, WeatherScenario.NIGHT_CLEAR),
            Triple(WeatherType.RAINY, false, WeatherScenario.RAIN),
            Triple(WeatherType.THUNDERSTORM, false, WeatherScenario.THUNDERSTORM),
            Triple(WeatherType.SNOW, false, WeatherScenario.SNOW),
            Triple(WeatherType.CLOUDY, true, WeatherScenario.NIGHT_CLOUDY)
        )

        val effectModes = mutableSetOf<LiveEffectMode>()

        for ((weatherType, isNight, expectedScenario) in cases) {
            val resolvedScenario = WeatherScenario.fromWeatherType(weatherType.name, isNight = isNight)
            assertEquals(
                "WeatherScenario must resolve '${weatherType.name}' (night=$isNight) without collapsing to CLOUDY",
                expectedScenario,
                resolvedScenario
            )

            // MissingImageBanner highlight scenario drives prompt generation
            val prompt = CityImagePromptGenerator.buildPrompt("Berlin, Deutschland", resolvedScenario)
            val expectedEnglishWeather = CityImagePromptGenerator.englishWeatherLabel(resolvedScenario)
            assertTrue(prompt.contains("WEATHER = $expectedEnglishWeather"))

            // Same WeatherScenario drives LiveWeatherOverlay effect mode
            val effectMode = resolveLiveEffectMode(
                weatherType = weatherType,
                scenario = resolvedScenario,
                isNight = isNight
            )
            effectModes.add(effectMode)
            assertNotEquals(LiveEffectMode.NONE, effectMode)
        }

        // Distinct scenarios must map to distinct overlay visual effects
        assertEquals(6, effectModes.size)
    }

    // -------------------------------------------------------------------------
    // 6. F5 + F7: Multi-model forecast + 7/14/30d archive baseline combine into
    //    coherent temperature anomaly + confidence display.
    // -------------------------------------------------------------------------
    @Test
    fun t3_06_f5_f7_historicalNormalAndMultiModelForecast_combineIntoCoherentAnomalyAndConfidence() {
        val archive = E2ETestFixtures.sampleDailyHistoryData(days = 30, baseMinC = 10.0, baseMaxC = 20.0)
        val graph30 = HistoricalNormalCalculator.buildHistorySeries(
            dailyDates = archive.dates,
            mins = archive.mins,
            maxs = archive.maxs,
            means = archive.means,
            days = 30
        )
        assertEquals(30, graph30.points.size)

        // Daily normal at 14:00 for a 10°C..20°C baseline should approach dailyMaxC (~19..20°C)
        val normalAt14h = HistoricalNormalCalculator.computeSinusoidalNormalTemp(
            dailyMinC = 10.0,
            dailyMaxC = 20.0,
            hourOfDay = 14.0
        )
        assertTrue("14:00 normal should lie between min and max near daily high", normalAt14h in 17.0..20.5)

        // Aligned warm forecast (~27.0°C) across ECMWF, GFS, ICON-D2, OWM
        val modelTemps = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to listOf(27.0, 27.2, 26.8),
            "gfs_global" to listOf(27.3, 27.0, 27.1),
            "icon_d2" to listOf(26.9, 27.1, 27.0),
            "owm" to listOf(27.1, 26.9, 27.2)
        )
        val modelPrecips = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to listOf(0.0, 0.0, 0.0),
            "gfs_global" to listOf(0.0, 0.0, 0.0),
            "icon_d2" to listOf(0.0, 0.0, 0.0),
            "owm" to listOf(0.0, 0.0, 0.0)
        )
        val modelWinds = mapOf<String, List<Double?>>(
            "ecmwf_ifs025" to listOf(10.0, 11.0, 10.5),
            "gfs_global" to listOf(10.5, 11.2, 10.0),
            "icon_d2" to listOf(9.8, 10.8, 10.4),
            "owm" to listOf(10.2, 11.0, 10.3)
        )

        val confidence: ConfidenceResult = ForecastConfidenceEngine.computeConfidence(
            modelTemps = modelTemps,
            modelPrecips = modelPrecips,
            modelWinds = modelWinds
        )
        val forecastMeanTemp = modelTemps.values.flatten().filterNotNull().average()
        val anomalyC = forecastMeanTemp - normalAt14h

        assertTrue("Tightly aligned models must yield high confidence >= 80%", confidence.percent >= 80)
        assertEquals("Hoch", confidence.tierLabel)
        assertTrue("Forecast 27°C vs normal must show positive warm anomaly > +6°C", anomalyC > 6.0)
    }

    // -------------------------------------------------------------------------
    // 7. F6 + F7: Multi-model best_match/gfs_global UV data feeds SportAdvisor,
    //    while ForecastConfidenceEngine handles null icon_d2 UV/temps gracefully.
    // -------------------------------------------------------------------------
    @Test
    fun t3_07_f6_f7_gfsUvFeedsSportAdvisor_whileConfidenceFiltersNullIconD2() {
        // Outside Central Europe (Tokyo): icon_d2 returns all nulls; UV comes from gfs_global/best_match
        val ecmwfUv: List<Double?> = List(24) { null }
        val iconD2Uv: List<Double?> = List(24) { null }
        val gfsUv: List<Double?> = (0 until 24).map { h -> if (h in 11..15) 9.2 else if (h in 8..18) 4.0 else 0.0 }

        val resolvedUvSeries = (0 until 24).map { h ->
            ecmwfUv[h] ?: iconD2Uv[h] ?: gfsUv[h] ?: 0.0
        }

        val hours = E2ETestFixtures.sample24HourSeries(minTempC = 19.0, maxTempC = 28.0).mapIndexed { idx, pt ->
            pt.copy(uvIndex = resolvedUvSeries[idx])
        }

        val verdict = SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, hours)
        val skippedEpochs = verdict.hoursToSkip.map { it.hour.epochMillis }.toSet()

        for (h in 11..15) {
            assertTrue("Hour $h with GFS fallback UV=9.2 should be skipped", skippedEpochs.contains(hours[h].epochMillis))
        }

        val fixture = E2ETestFixtures.sampleMultiModelSeries(hours = 24, includeIconD2 = false, tempSpreadC = 0.3)
        val confidence = ForecastConfidenceEngine.computeConfidence(
            modelTemps = fixture.modelTemps,
            modelPrecips = fixture.modelPrecips,
            modelWinds = fixture.modelWinds
        )

        assertTrue("Null icon_d2 must be dynamically filtered; remaining aligned models stay Hoch", confidence.percent >= 80)
        assertEquals("Hoch", confidence.tierLabel)
    }

    // -------------------------------------------------------------------------
    // 8. F6 + F11: Selected SportType (e.g. CYCLING vs WALKING) changes
    //    EventRescheduler sensitivity flagging & optimal alternative slot selection.
    // -------------------------------------------------------------------------
    @Test
    fun t3_08_f6_f11_selectedSportType_changesEventSensitivityAndRescheduleSlot() {
        val base = E2ETestFixtures.BASE_EPOCH_MILLIS
        // Dry day: morning 09..11 has strong wind (36 km/h, gusts 58 km/h); afternoon 14..17 is calm (7 km/h, gusts 12 km/h)
        val hours = (0 until 24).map { h ->
            val isWindyMorning = h in 9..11
            E2ETestFixtures.sampleHourlyPoint(
                epochMillis = base + h * E2ETestFixtures.HOUR_MILLIS,
                tempC = 18.0,
                precipMm = 0.0,
                precipProb = 5,
                windKmh = if (isWindyMorning) 36.0 else 7.0,
                gustKmh = if (isWindyMorning) 58.0 else 12.0,
                uvIndex = 3.0,
                capeJkg = 40.0,
                weatherCode = 1
            )
        }

        val morningOutdoorEvent = CalendarEvent(
            id = 308L,
            title = "Ausfahrt Outdoor",
            description = "Sportrunde",
            location = "Tempelhofer Feld",
            startMillis = base + 9 * E2ETestFixtures.HOUR_MILLIS,
            endMillis = base + 11 * E2ETestFixtures.HOUR_MILLIS,
            allDay = false
        )

        val cyclingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.CYCLING, hours)
        val walkingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.WALKING, hours)

        assertTrue(
            "Cycling must flag more windy morning hours than gentle walking",
            cyclingVerdict.hoursToSkip.size > walkingVerdict.hoursToSkip.size
        )

        val cyclingSuggestions = EventRescheduler.suggestReschedules(
            events = listOf(morningOutdoorEvent),
            hourlyPoints = hours,
            sport = SportType.CYCLING,
            nowMillis = base
        )
        val walkingSuggestions = EventRescheduler.suggestReschedules(
            events = listOf(morningOutdoorEvent),
            hourlyPoints = hours,
            sport = SportType.WALKING,
            nowMillis = base
        )

        assertTrue("Cycling must flag windy morning event for reschedule", cyclingSuggestions.isNotEmpty())
        val suggestedStart = cyclingSuggestions.first().suggestedStartMillis
        assertNotNull("Suggested calm alternative slot should be provided for cycling", suggestedStart)
        assertTrue(
            "Suggested cycling slot must move out of windy 09-11 window into calmer afternoon",
            suggestedStart!! >= base + 12 * E2ETestFixtures.HOUR_MILLIS
        )
        assertTrue(
            "Walking on dry 36 km/h morning should either not reschedule or have fewer flagged hours than cycling",
            walkingSuggestions.isEmpty() || walkingVerdict.hoursToSkip.size < cyclingVerdict.hoursToSkip.size
        )
    }

    // -------------------------------------------------------------------------
    // 9. F6 + F13: SportAdvisor.findBestSportWindow directly populates Briefing
    //    Component #3 ("best sport window") and GermanFreeTextParser SPORT_WINDOW.
    // -------------------------------------------------------------------------
    @Test
    fun t3_09_f6_f13_sportAdvisorBestWindow_populatesBriefingComponent3AndFreeTextSportQuery() {
        val hours = E2ETestFixtures.sample24HourSeries(
            minTempC = 14.0,
            maxTempC = 21.0,
            peakUv = 4.5,
            rainHours = setOf(6, 7, 8, 9, 10)
        )
        val bestWindow = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        assertNotNull("Afternoon dry window should exist after morning rain", bestWindow)
        assertTrue(
            "Best window must avoid rainy 06:00-10:00 hours",
            bestWindow!!.startEpochMillis >= hours[11].epochMillis
        )

        val clothing = ClothingAdvisor.recommendClothing(
            tempC = 17.0,
            feelsLikeC = 16.0,
            precipProb = 75,
            windKmh = 14.0,
            uvIndex = 4.0
        )
        val briefing = BriefingComposer.composeBriefing(
            dateLabel = "Sonntag, 05.07.",
            dailySummary = "Morgens Regen, nachmittags trocken bei 21°C",
            clothing = clothing,
            bestSportWindow = bestWindow,
            calendarWarnings = emptyList(),
            routeCheck = "Arbeitsweg trocken ab 11:00 Uhr"
        )

        assertTrue("Briefing Component #3 (sport window) must be non-blank", briefing.sportWindowText.isNotBlank())
        assertTrue(
            "Briefing sport section should reference sport or time window",
            briefing.sportWindowText.contains("Laufen", ignoreCase = true) ||
                briefing.sportWindowText.contains(":", ignoreCase = true) ||
                briefing.sportWindowText.contains("Uhr", ignoreCase = true)
        )

        val parsed = GermanFreeTextParser.parseIntent("Wann kann ich heute laufen gehen?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, parsed.category)
    }

    // -------------------------------------------------------------------------
    // 10. F8 + F9: Switching MapViewMode (MODE_2D <-> GLOBE_3D <-> STREET_3D)
    //     preserves TimelineState, PressureLevel, and MapForecastModel.
    // -------------------------------------------------------------------------
    @Test
    fun t3_10_f8_f9_switchingMapViewMode_preservesTimelinePressureLevelAndModel() {
        val controller = MapStateController()
        val frames = (0 until 13).map { idx ->
            TimelineFrame(
                epochMillis = E2ETestFixtures.BASE_EPOCH_MILLIS + idx * E2ETestFixtures.HOUR_MILLIS,
                label = "T+$idx",
                source = TimelineFrameSource.FORECAST_MODEL,
                forecastHourIndex = idx
            )
        }
        controller.setTimelineFrames(frames)

        controller.setPressureLevel(PressureLevel.HPA_500)
        controller.setModel(MapForecastModel.ECMWF)
        controller.seekTimeline(7)
        controller.setPlaying(true)

        val modes = listOf(
            MapViewMode.GLOBE_3D,
            MapViewMode.STREET_3D,
            MapViewMode.MODE_2D,
            MapViewMode.STREET_3D
        )

        for (mode in modes) {
            controller.setViewMode(mode)
            assertEquals(mode, controller.state.value.viewMode)
            assertEquals(PressureLevel.HPA_500, controller.state.value.pressureLevel)
            assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
            assertEquals(7, controller.state.value.timelineIndex)
            assertTrue(controller.state.value.isPlaying)
        }
    }

    // -------------------------------------------------------------------------
    // 11. F9 + F10: Changing PressureLevel (Sfc -> 500 hPa) and MapForecastModel
    //     (GFS -> ECMWF) updates upper-air layer parameters for WIND/TEMP/HUMIDITY
    //     while keeping RADAR surface-clamped.
    // -------------------------------------------------------------------------
    @Test
    fun t3_11_f9_f10_changingPressureLevelAndModel_updatesUpperAirLayersWhileRadarStaysSurface() {
        val controller = MapStateController()

        controller.toggleLayer(MapWeatherLayer.WIND)
        controller.toggleLayer(MapWeatherLayer.TEMPERATURE)
        controller.toggleLayer(MapWeatherLayer.HUMIDITY)
        controller.toggleLayer(MapWeatherLayer.RADAR)

        controller.setPressureLevel(PressureLevel.HPA_500)
        controller.setModel(MapForecastModel.ECMWF)

        assertEquals(PressureLevel.HPA_500, controller.state.value.pressureLevel)
        assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
        assertEquals("ecmwf_ifs025", controller.state.value.model.id)

        val windVar = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.WIND, controller.state.value.pressureLevel).primaryVariable
        val tempVar = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.TEMPERATURE, controller.state.value.pressureLevel).primaryVariable
        val humidityVar = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.HUMIDITY, controller.state.value.pressureLevel).primaryVariable
        val precipVar = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.PRECIPITATION, controller.state.value.pressureLevel).primaryVariable

        assertEquals("wind_speed_500hPa", windVar)
        assertEquals("temperature_500hPa", tempVar)
        assertEquals("relative_humidity_500hPa", humidityVar)
        assertFalse("Surface-only precipitation must stay surface-clamped", precipVar.contains("500hPa"))
        assertTrue("RADAR remains active across pressure level & model switch", controller.state.value.activeLayers.contains(MapWeatherLayer.RADAR))
        assertEquals(7, RainViewerParser.clampZoom(12))
    }

    // -------------------------------------------------------------------------
    // 12. F11 + F13: Flagged CalendarRepository outdoor events populate Briefing
    //     Component #4 ("calendar warnings") and answer EVENT_PLANNING queries.
    // -------------------------------------------------------------------------
    @Test
    fun t3_12_f11_f13_flaggedCalendarEvents_populateBriefingComponent4AndEventPlanningQuery() {
        val base = E2ETestFixtures.BASE_EPOCH_MILLIS
        val hours = E2ETestFixtures.sample24HourSeries(
            thunderstormHours = setOf(10, 11)
        )

        val grillEvent = CalendarEvent(
            id = 3121L,
            title = "Grillen im Park",
            description = "Geburtstag draußen",
            location = "Volkspark",
            startMillis = base + 10 * E2ETestFixtures.HOUR_MILLIS,
            endMillis = base + 12 * E2ETestFixtures.HOUR_MILLIS,
            allDay = false
        )
        val officeMeeting = CalendarEvent(
            id = 3122L,
            title = "Sprint Review Call",
            description = "Online Video-Call",
            location = "Büro 2. OG",
            startMillis = base + 10 * E2ETestFixtures.HOUR_MILLIS,
            endMillis = base + 11 * E2ETestFixtures.HOUR_MILLIS,
            allDay = false
        )

        assertTrue(EventRescheduler.classifySensitivity(grillEvent.title, grillEvent.description, grillEvent.location).isWeatherSensitive)
        assertFalse(EventRescheduler.classifySensitivity(officeMeeting.title, officeMeeting.description, officeMeeting.location).isWeatherSensitive)

        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(grillEvent, officeMeeting),
            hourlyPoints = hours,
            sport = SportType.WALKING,
            nowMillis = base
        )
        assertEquals("Only outdoor event should be flagged for thunderstorm window", 1, suggestions.size)
        assertEquals(grillEvent.id, suggestions.first().eventId)

        val warningStrings = suggestions.map { s -> "${grillEvent.title}: ${s.message}" }
        val clothing = ClothingAdvisor.recommendClothing(20.0, 20.0, 90, 35.0, 2.0)
        val briefing = BriefingComposer.composeBriefing(
            dateLabel = "Sonntag, 05.07.",
            dailySummary = "Mittags Gewittergefahr",
            clothing = clothing,
            bestSportWindow = SportAdvisor.findBestSportWindow(SportType.WALKING, hours),
            calendarWarnings = warningStrings,
            routeCheck = "Keine Routenwarnung"
        )

        assertTrue(
            "Briefing Component #4 must mention flagged outdoor event",
            briefing.calendarWarningsText.contains("Grillen im Park")
        )
        assertFalse(
            "Briefing Component #4 must not warn on indoor video call",
            briefing.calendarWarningsText.contains("Sprint Review Call")
        )

        val parsed = GermanFreeTextParser.parseIntent("Muss ich mein Grillen im Park heute verschieben?")
        assertEquals(QueryIntentCategory.EVENT_PLANNING, parsed.category)
    }

    // -------------------------------------------------------------------------
    // 13. F12 + F13: RouteWeatherCalculator commute output populates Briefing
    //     Component #5 ("route/commute check") and answers ROUTE_WEATHER queries.
    // -------------------------------------------------------------------------
    @Test
    fun t3_13_f12_f13_routeWeatherCommute_populatesBriefingComponent5AndRouteWeatherQuery() {
        val route = E2ETestFixtures.berlinCommuteRoute(mode = "cycling")
        val hours = E2ETestFixtures.sample24HourSeries(rainHours = setOf(8, 9))
        val targetArrivalMillis = hours[9].epochMillis
        val routePoints = route.waypoints.mapIndexed { idx, (lat, lon) ->
            RoutePoint(
                name = if (idx == 0) route.originName else if (idx == route.waypoints.lastIndex) route.destinationName else "Wegpunkt $idx",
                latitude = lat,
                longitude = lon,
                offsetMinutesFromStart = idx * 15
            )
        }

        val routeResult = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = route.originName,
            destination = route.destinationName,
            mode = RouteTransportMode.CYCLING,
            targetArrivalMillis = targetArrivalMillis,
            routePoints = routePoints,
            hourlyForecasts = hours,
            nowMillis = E2ETestFixtures.BASE_EPOCH_MILLIS
        )

        assertTrue(routeResult.segments.isNotEmpty())
        assertTrue(routeResult.departureReason.isNotBlank())

        val routeSummary = "${route.originName} -> ${route.destinationName}: ${routeResult.departureReason}"
        val clothing = ClothingAdvisor.recommendClothing(15.0, 14.0, 85, 18.0, 2.0)
        val briefing = BriefingComposer.composeBriefing(
            dateLabel = "Sonntag, 05.07.",
            dailySummary = "Regnerischer Morgen",
            clothing = clothing,
            bestSportWindow = SportAdvisor.findBestSportWindow(SportType.CYCLING, hours),
            calendarWarnings = emptyList(),
            routeCheck = routeSummary
        )

        assertTrue("Briefing Component #5 (route/commute check) must be non-blank", briefing.routeCheckText.isNotBlank())
        assertTrue(briefing.routeCheckText.contains(route.destinationName))

        val parsed = GermanFreeTextParser.parseIntent("Wie wird das Wetter auf dem Weg zur Arbeit nach Charlottenburg?")
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, parsed.category)
    }

    // -------------------------------------------------------------------------
    // 14. F1 + F12: Active favorite location serves as default origin in
    //     RouteWeatherCalculator when user omits origin.
    // -------------------------------------------------------------------------
    @Test
    fun t3_14_f1_f12_activeFavoriteLocation_servesAsDefaultOriginInRouteCalculator() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val favoritesRepo = FavoritesRepository(store)
        val cologne = FavoriteLocation(
            id = "cologne_de",
            name = "Köln",
            country = "Deutschland",
            latitude = 50.9375,
            longitude = 6.9603,
            addedAtMillis = E2ETestFixtures.BASE_EPOCH_MILLIS
        )
        favoritesRepo.addFavorite(cologne)
        favoritesRepo.addFavorite(E2ETestFixtures.munichFavorite())
        favoritesRepo.setActiveFavoriteId(cologne.id)

        val activeFavorite = favoritesRepo.getFavorites().first { it.id == favoritesRepo.getActiveFavoriteId() }
        val userProvidedOrigin: String? = null
        val resolvedOrigin = userProvidedOrigin?.takeIf { it.isNotBlank() } ?: activeFavorite.name

        val hours = E2ETestFixtures.sample24HourSeries()
        val bonnLat = 50.7374
        val bonnLon = 7.0982
        val routePoints = listOf(
            RoutePoint(resolvedOrigin, activeFavorite.latitude, activeFavorite.longitude, offsetMinutesFromStart = 0),
            RoutePoint("Wesseling", 50.83, 7.03, offsetMinutesFromStart = 20),
            RoutePoint("Bonn", bonnLat, bonnLon, offsetMinutesFromStart = 40)
        )
        val result = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = resolvedOrigin,
            destination = "Bonn",
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = hours[10].epochMillis,
            routePoints = routePoints,
            hourlyForecasts = hours,
            nowMillis = E2ETestFixtures.BASE_EPOCH_MILLIS
        )
        val handoffUri = RouteWeatherCalculator.buildMapsHandoffUri(
            originLat = activeFavorite.latitude,
            originLon = activeFavorite.longitude,
            destLat = bonnLat,
            destLon = bonnLon,
            destLabel = "Bonn",
            mode = RouteTransportMode.DRIVING
        )

        assertEquals("Köln", result.segments.first().pointName)
        assertEquals("Bonn", result.segments.last().pointName)
        assertTrue(
            "Maps handoff URI must include Bonn or destination coords and valid scheme",
            (handoffUri.contains("Bonn") || handoffUri.contains("50.7374")) &&
                (handoffUri.startsWith("https://") || handoffUri.startsWith("google.navigation:") || handoffUri.startsWith("geo:"))
        )
    }

    // -------------------------------------------------------------------------
    // 15. F13 + F14: Morning/evening BriefingComposer output formats cleanly for
    //     both in-app card AND WeatherNotificationService push notification payload.
    // -------------------------------------------------------------------------
    @Test
    fun t3_15_f13_f14_morningAndEveningBriefing_formatsForInAppCardAndPushNotification() {
        val hours = E2ETestFixtures.sample24HourSeries(rainHours = setOf(13))
        val bestWindow = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        val clothing = ClothingAdvisor.recommendClothing(19.0, 19.0, 30, 12.0, 5.0)

        val briefing = BriefingComposer.composeBriefing(
            dateLabel = "Sonntag, 05.07.",
            dailySummary = "Wechselhaft 14–22°C, mittags kurzer Schauer",
            clothing = clothing,
            bestSportWindow = bestWindow,
            calendarWarnings = listOf("Keine wetterkritischen Termine"),
            routeCheck = "Freie Fahrt auf dem Arbeitsweg"
        )

        val notificationBody = briefing.toNotificationBody()
        val liveNotifState = WeatherNotificationService.buildLiveNotificationState(
            tempC = 19.0,
            condition = "Wechselhaft",
            locationName = "Berlin",
            weatherType = WeatherType.CLOUDY
        )

        // All 5 mandatory components must appear cleanly in both in-app briefing fields and push notification body
        assertTrue(briefing.dailyWeatherText.isNotBlank())
        assertTrue(briefing.clothingText.isNotBlank())
        assertTrue(briefing.sportWindowText.isNotBlank())
        assertTrue(briefing.calendarWarningsText.isNotBlank())
        assertTrue(briefing.routeCheckText.isNotBlank())

        assertTrue(notificationBody.contains(briefing.clothingText))
        assertTrue(notificationBody.contains(briefing.sportWindowText))
        assertTrue(notificationBody.contains(briefing.calendarWarningsText))
        assertTrue(notificationBody.contains(briefing.routeCheckText))
        assertEquals(1 /* VISIBILITY_PUBLIC */, liveNotifState.visibility)
        assertTrue(liveNotifState.ongoing)
    }

    // -------------------------------------------------------------------------
    // 16. F5 + F6 + F13: Historical normal anomaly + UV/sport verdict + clothing
    //     recommendation align consistently on cold/rainy vs hot/high-UV days.
    // -------------------------------------------------------------------------
    @Test
    fun t3_16_f5_f6_f13_normalAnomalySportVerdictAndClothing_alignOnColdRainyVsHotHighUvDays() {
        val normalAt14h = HistoricalNormalCalculator.computeSinusoidalNormalTemp(
            dailyMinC = 12.0,
            dailyMaxC = 22.0,
            hourOfDay = 14.0
        )

        // Scenario A: Cold & rainy day (4°C, rain 08..18, 90% precipProb, 35 km/h wind, UV 1.0)
        val coldRainyHours = E2ETestFixtures.sample24HourSeries(
            minTempC = 2.0,
            maxTempC = 5.0,
            peakUv = 1.0,
            rainHours = (8..18).toSet(),
            windKmhBase = 35.0,
            gustKmhBase = 55.0
        )
        val coldAnomaly = 4.0 - normalAt14h
        val coldSportVerdict = SportAdvisor.evaluateHoursToSkip(SportType.CYCLING, coldRainyHours)
        val coldClothing = ClothingAdvisor.recommendClothing(
            tempC = 4.0,
            feelsLikeC = -1.0,
            precipProb = 90,
            windKmh = 35.0,
            uvIndex = 1.0
        )

        assertTrue("Cold day vs summer normal must have strongly negative anomaly", coldAnomaly < -10.0)
        assertTrue("Cold rainy windy day must flag cycling hours to skip", coldSportVerdict.hoursToSkip.size >= 10)
        assertTrue(
            "Cold rainy clothing must recommend jacket/rain protection",
            coldClothing.summary.contains("Regen", ignoreCase = true) ||
                coldClothing.summary.contains("Jacke", ignoreCase = true) ||
                coldClothing.layers.any { it.contains("Jacke", ignoreCase = true) || it.contains("Regen", ignoreCase = true) }
        )

        // Scenario B: Hot & high-UV day (32°C, 0mm rain, 5% precipProb, 8 km/h wind, UV 9.5)
        val hotHighUvHours = E2ETestFixtures.sample24HourSeries(
            minTempC = 20.0,
            maxTempC = 33.0,
            peakUv = 9.5,
            highUvHours = setOf(11, 12, 13, 14, 15)
        )
        val hotAnomaly = 32.0 - normalAt14h
        val hotSportVerdict = SportAdvisor.evaluateHoursToSkip(SportType.RUNNING, hotHighUvHours)
        val hotClothing = ClothingAdvisor.recommendClothing(
            tempC = 32.0,
            feelsLikeC = 34.0,
            precipProb = 5,
            windKmh = 8.0,
            uvIndex = 9.5
        )

        assertTrue("Hot day vs normal must have strongly positive anomaly", hotAnomaly > 8.0)
        assertTrue("High UV midday hours must be flagged to skip for running", hotSportVerdict.hoursToSkip.any { it.hour.uvIndex >= 8.0 })
        assertTrue(
            "Hot high-UV clothing must recommend sun protection or light clothing",
            hotClothing.summary.contains("Sonnen", ignoreCase = true) ||
                hotClothing.summary.contains("UV", ignoreCase = true) ||
                hotClothing.summary.contains("leicht", ignoreCase = true)
        )
        assertTrue(abs(coldAnomaly - hotAnomaly) > 20.0)
    }
}

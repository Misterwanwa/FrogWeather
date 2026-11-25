package com.frogweather.e2e

import com.frogweather.WeatherType
import com.frogweather.analysis.ForecastConfidenceEngine
import com.frogweather.analysis.HistoricalNormalCalculator
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.assistant.GermanFreeTextParser
import com.frogweather.assistant.QueryIntentCategory
import com.frogweather.briefing.BriefingComposer
import com.frogweather.briefing.BriefingScheduler
import com.frogweather.briefing.ClothingAdvisor
import com.frogweather.calendar.CalendarEvent
import com.frogweather.calendar.EventRescheduler
import com.frogweather.data.AppPrefsKeys
import com.frogweather.data.AppThemeId
import com.frogweather.data.CityImagePromptGenerator
import com.frogweather.data.FavoriteLocation
import com.frogweather.data.FavoritesRepository
import com.frogweather.data.ForecastSnapshot
import com.frogweather.data.ObservedWeatherPoint
import com.frogweather.data.ThemeRepository
import com.frogweather.data.TrackRecordRepository
import com.frogweather.data.WeatherScenario
import com.frogweather.map.GeoBounds
import com.frogweather.map.MapForecastModel
import com.frogweather.map.MapLayerDataProvider
import com.frogweather.map.MapStateController
import com.frogweather.map.MapViewMode
import com.frogweather.map.MapWeatherLayer
import com.frogweather.map.PressureLevel
import com.frogweather.map.ResolvedLayerOverlay
import com.frogweather.map.ScalarGridField
import com.frogweather.map.TimelineFrameSource
import com.frogweather.network.RainViewerFrame
import com.frogweather.network.RainViewerMapsData
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage A E2E Opaque-Box Tests — Tier 4: Real-World Application Scenarios (8 Scenarios).
 *
 * Derived strictly from:
 * - ORIGINAL_REQUEST.md (R1–R4)
 * - PROJECT.md (§ Feature Inventory & § Interface Contracts)
 * - TEST_INFRA.md (§ Tier 4 — Real-World Application Scenarios)
 *
 * Each scenario validates an end-to-end user workflow spanning multiple phases/modules
 * on pure JVM using [E2ETestFixtures] with zero Android framework or network calls.
 */
class E2EScenarioTests {

    // -------------------------------------------------------------------------
    // Scenario 1: Berlin Commuter Morning Routine
    // Features: F1, F6, F11, F12, F13, F14
    // -------------------------------------------------------------------------
    @Test
    fun t4_01_berlinCommuterMorningRoutine_0700BriefingRainyCommuteAndLockScreenSync() {
        val base = E2ETestFixtures.BASE_EPOCH_MILLIS
        val favStore = E2ETestFixtures.InMemoryKeyValueStore()
        val prefsStore = E2ETestFixtures.InMemoryKeyValueStore()
        val widgetStore = E2ETestFixtures.InMemoryKeyValueStore()
        val schedulerStore = E2ETestFixtures.InMemoryKeyValueStore()

        // 1. User activates Berlin favorite and syncs shared frog_weather_prefs
        val favoritesRepo = FavoritesRepository(favStore)
        val berlin = E2ETestFixtures.berlinFavorite()
        favoritesRepo.addFavorite(berlin)
        favoritesRepo.setActiveFavoriteId(berlin.id)
        prefsStore.putFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, berlin.latitude.toFloat())
        prefsStore.putFloat(AppPrefsKeys.KEY_LAST_CITY_LON, berlin.longitude.toFloat())
        prefsStore.putString(AppPrefsKeys.KEY_LAST_CITY_NAME, berlin.name)
        prefsStore.putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, berlin.country)

        // 2. Morning briefing scheduler defaults to 07:00
        val scheduler = BriefingScheduler(schedulerStore)
        val morningSchedule = scheduler.getMorningSchedule()
        assertEquals(7, morningSchedule.hour)
        assertEquals(0, morningSchedule.minute)

        // 3. Rainy morning commute (07:00-09:00), dry mild afternoon (15:00-19:00)
        val hours = E2ETestFixtures.sample24HourSeries(
            startEpochMillis = base,
            minTempC = 11.0,
            maxTempC = 18.0,
            peakUv = 3.5,
            rainHours = setOf(7, 8, 9)
        )

        // 4. Calendar has indoor Standup + outdoor running group overlapping morning rain
        val standup = CalendarEvent(
            id = 4011L,
            title = "Team Standup Meeting",
            description = "Büro Call",
            location = "Büro Mitte",
            startMillis = base + 9 * E2ETestFixtures.HOUR_MILLIS,
            endMillis = base + 10 * E2ETestFixtures.HOUR_MILLIS,
            allDay = false
        )
        val outdoorRun = CalendarEvent(
            id = 4012L,
            title = "Laufrunde im Tiergarten",
            description = "Joggen draußen",
            location = "Tiergarten, Berlin",
            startMillis = base + 8 * E2ETestFixtures.HOUR_MILLIS,
            endMillis = base + 9 * E2ETestFixtures.HOUR_MILLIS,
            allDay = false
        )
        val reschedules = EventRescheduler.suggestReschedules(
            events = listOf(standup, outdoorRun),
            hourlyPoints = hours,
            sport = SportType.RUNNING,
            nowMillis = base
        )
        assertEquals("Only outdoor run should be flagged during rainy commute", 1, reschedules.size)
        assertEquals(outdoorRun.id, reschedules.first().eventId)
        assertNotNull(reschedules.first().suggestedStartMillis)
        assertTrue(reschedules.first().suggestedStartMillis!! >= base + 10 * E2ETestFixtures.HOUR_MILLIS)

        // 5. Cycling commute route Berlin Alexanderplatz -> Charlottenburg at 09:00 arrival
        val route = E2ETestFixtures.berlinCommuteRoute(mode = "cycling")
        val routePoints = route.waypoints.mapIndexed { idx, (lat, lon) ->
            RoutePoint("Stop $idx", lat, lon, offsetMinutesFromStart = idx * 15)
        }
        val routeResult = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = route.originName,
            destination = route.destinationName,
            mode = RouteTransportMode.CYCLING,
            targetArrivalMillis = base + 9 * E2ETestFixtures.HOUR_MILLIS,
            routePoints = routePoints,
            hourlyForecasts = hours,
            nowMillis = base
        )
        assertTrue(routeResult.segments.isNotEmpty())
        assertTrue(routeResult.departureReason.isNotBlank())

        // 6. Clothing + best sport window + 5-component briefing + notification payload
        val clothing = ClothingAdvisor.recommendClothing(
            tempC = hours[8].tempC,
            feelsLikeC = hours[8].tempC - 2.0,
            precipProb = hours[8].precipProb,
            windKmh = hours[8].windKmh,
            uvIndex = hours[8].uvIndex
        )
        val bestWindow = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        assertNotNull(bestWindow)
        assertTrue(bestWindow!!.startEpochMillis >= base + 10 * E2ETestFixtures.HOUR_MILLIS)

        val briefing = BriefingComposer.composeBriefing(
            dateLabel = "Montag, Berlin",
            dailySummary = "11–18°C, morgens Regen, nachmittags trocken",
            clothing = clothing,
            bestSportWindow = bestWindow,
            calendarWarnings = reschedules.map { "${outdoorRun.title}: ${it.message}" },
            routeCheck = routeResult.departureReason
        )
        val pushBody = briefing.toNotificationBody()
        assertTrue(pushBody.contains(briefing.clothingText))
        assertTrue(pushBody.contains(briefing.sportWindowText))
        assertTrue(pushBody.contains("Laufrunde im Tiergarten"))
        assertTrue(pushBody.contains(routeResult.departureReason))

        // 7. Lock-screen widget + live notification sync to Berlin
        val widgetLoc = WeatherWidgetProvider.resolveWidgetLocation(1, widgetStore, prefsStore)
        val notifCoords = WeatherNotificationService.resolveCoordinates(
            intentLat = null,
            intentLon = null,
            savedNotifLat = null,
            savedNotifLon = null,
            savedCityLat = prefsStore.getFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 0f).toDouble(),
            savedCityLon = prefsStore.getFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 0f).toDouble()
        )
        val notifState = WeatherNotificationService.buildLiveNotificationState(
            tempC = hours[8].tempC,
            condition = WeatherType.RAINY.label,
            locationName = "Berlin",
            weatherType = WeatherType.RAINY
        )

        assertEquals("Berlin", widgetLoc?.cityName)
        assertEquals(berlin.latitude, notifCoords!!.first, 0.01)
        assertEquals(1 /* VISIBILITY_PUBLIC */, notifState.visibility)
        assertTrue(WeatherWidgetProvider.REQUIRED_WIDGET_CATEGORY.contains("keyguard"))
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Munich Alpine Weekend Hike Planning
    // Features: F1, F3, F6, F11, F13
    // -------------------------------------------------------------------------
    @Test
    fun t4_02_munichAlpineWeekendHikePlanning_calendarRescheduleUvSportAndPromptGen() {
        val base = E2ETestFixtures.BASE_EPOCH_MILLIS
        val favRepo = FavoritesRepository(E2ETestFixtures.InMemoryKeyValueStore())
        val munich = E2ETestFixtures.munichFavorite()
        favRepo.addFavorite(munich)
        favRepo.setActiveFavoriteId(munich.id)

        // Morning 07..10 is clear & low UV; midday 12..14 has UV >= 9; afternoon 15..18 has thunderstorm
        val hours = E2ETestFixtures.sample24HourSeries(
            startEpochMillis = base,
            minTempC = 12.0,
            maxTempC = 25.0,
            peakUv = 9.0,
            highUvHours = setOf(12, 13, 14),
            thunderstormHours = setOf(15, 16, 17, 18)
        )

        // Hiking evaluation
        val hikeVerdict = SportAdvisor.evaluateHoursToSkip(SportType.HIKING, hours)
        val skippedEpochs = hikeVerdict.hoursToSkip.map { it.hour.epochMillis }.toSet()
        assertTrue("Afternoon thunderstorm hours must be skipped for hiking", hours[16].epochMillis in skippedEpochs)
        assertTrue("High-UV midday hour must be flagged for hiking", hours[13].epochMillis in skippedEpochs)

        val bestHikeWindow = SportAdvisor.findBestSportWindow(SportType.HIKING, hours)
        assertNotNull(bestHikeWindow)
        assertTrue("Best hiking window should fall before afternoon storms", bestHikeWindow!!.endEpochMillis <= base + 15 * E2ETestFixtures.HOUR_MILLIS)

        // Calendar event scheduled in afternoon thunderstorm slot (15:00-18:00)
        val hikeEvent = CalendarEvent(
            id = 4021L,
            title = "Alpenwanderung Tegernsee",
            description = "Wochenend-Wanderung in den Bergen",
            location = "Tegernsee",
            startMillis = base + 15 * E2ETestFixtures.HOUR_MILLIS,
            endMillis = base + 18 * E2ETestFixtures.HOUR_MILLIS,
            allDay = false
        )
        val suggestions = EventRescheduler.suggestReschedules(
            events = listOf(hikeEvent),
            hourlyPoints = hours,
            sport = SportType.HIKING,
            nowMillis = base
        )
        assertEquals(1, suggestions.size)
        val suggestedStart = suggestions.first().suggestedStartMillis
        assertNotNull(suggestedStart)
        assertTrue("Rescheduled hike must move earlier before 15:00 storms", suggestedStart!! < base + 15 * E2ETestFixtures.HOUR_MILLIS)

        // Generate missing diorama prompts for Munich with custom landmarks
        val missingPrompts = CityImagePromptGenerator.buildPromptsForMissing(
            cityLabel = "${munich.name}, ${munich.country}",
            missingScenarios = listOf(WeatherScenario.SUNNY, WeatherScenario.THUNDERSTORM),
            additionalLandmarks = "Frauenkirche, Alpenpanorama"
        )
        assertEquals(2, missingPrompts.size)
        assertTrue(missingPrompts[0].prompt.contains("CITY=München, Deutschland"))
        assertTrue(missingPrompts[0].prompt.contains("ADDITIONAL_LANDMARKS (optional): Frauenkirche, Alpenpanorama"))
        assertTrue(missingPrompts[1].prompt.contains("WEATHER = Thunderstorm"))

        // Compose weekend briefing + verify clothing advice
        val clothing = ClothingAdvisor.recommendClothing(
            tempC = 22.0,
            feelsLikeC = 22.0,
            precipProb = 60,
            windKmh = 22.0,
            uvIndex = 8.5
        )
        val briefing = BriefingComposer.composeBriefing(
            dateLabel = "Samstag, München",
            dailySummary = "Vormittags freundlich, nachmittags Gewitter",
            clothing = clothing,
            bestSportWindow = bestHikeWindow,
            calendarWarnings = suggestions.map { "${hikeEvent.title}: ${it.message}" },
            routeCheck = "Anfahrt über A8 am Vormittag problemlos"
        )
        assertTrue(briefing.calendarWarningsText.contains("Alpenwanderung Tegernsee"))
        assertTrue(briefing.sportWindowText.isNotBlank())
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Tokyo Travel Check Outside Central Europe
    // Features: F1, F2, F3, F4, F7
    // -------------------------------------------------------------------------
    @Test
    fun t4_03_tokyoTravelCheckOutsideCentralEurope_iconD2NullFavoritesThemeAndDiorama() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val favoritesRepo = FavoritesRepository(store)
        val themeRepo = ThemeRepository(store)

        val berlin = E2ETestFixtures.berlinFavorite()
        val tokyo = E2ETestFixtures.tokyoFavorite()
        favoritesRepo.addFavorite(berlin)
        favoritesRepo.addFavorite(tokyo)
        favoritesRepo.setActiveFavoriteId(tokyo.id)
        themeRepo.setSelectedTheme(AppThemeId.NIGHT_AURORA)

        assertEquals(tokyo.id, favoritesRepo.getActiveFavoriteId())
        assertEquals(AppThemeId.NIGHT_AURORA, themeRepo.getSelectedTheme())
        val nightPalette = paletteForTheme(AppThemeId.NIGHT_AURORA)
        assertNotNull(nightPalette)
        assertEquals(AppThemeId.NIGHT_AURORA, nightPalette.themeId)

        // Outside Central Europe: icon_d2 is all null, while ecmwf/gfs/owm agree closely
        val tokyoModels = E2ETestFixtures.sampleMultiModelSeries(
            hours = 24,
            includeIconD2 = false,
            tempSpreadC = 0.25,
            precipSpreadMm = 0.0,
            windSpreadKmh = 1.0
        )
        val confidence = ForecastConfidenceEngine.computeConfidence(
            modelTemps = tokyoModels.modelTemps,
            modelPrecips = tokyoModels.modelPrecips,
            modelWinds = tokyoModels.modelWinds
        )
        assertTrue("Tokyo aligned models with null icon_d2 must yield Hoch confidence", confidence.percent >= 80)
        assertEquals("Hoch", confidence.tierLabel)
        assertFalse(confidence.divergenceExplanation.contains("icon_d2", ignoreCase = true))

        // Clear night in Tokyo drives both LiveWeatherOverlay (CLEAR_NIGHT) & Diorama prompt
        val scenario = WeatherScenario.fromWeatherType("CLEAR", isNight = true)
        assertEquals(WeatherScenario.NIGHT_CLEAR, scenario)

        val overlayMode = resolveLiveEffectMode(
            weatherType = WeatherType.SUNNY,
            scenario = scenario,
            isNight = true
        )
        assertEquals(LiveEffectMode.CLEAR_NIGHT, overlayMode)

        val prompt = CityImagePromptGenerator.buildPrompt(
            cityLabel = "${tokyo.name}, ${tokyo.country}",
            scenario = scenario,
            additionalLandmarks = "Tokyo Tower, Shibuya Crossing"
        )
        assertTrue(prompt.contains("CITY=Tokyo, Japan"))
        assertTrue(prompt.contains("ADDITIONAL_LANDMARKS (optional): Tokyo Tower, Shibuya Crossing"))
        assertTrue(prompt.contains("Square 1080x1080 dimension."))
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Severe Summer Thunderstorm Tracking on 3D Map
    // Features: F4, F8, F9, F10
    // -------------------------------------------------------------------------
    @Test
    fun t4_04_severeSummerThunderstormTrackingOn3DMap_globeStreetTimelineLayersAndCape() {
        val controller = MapStateController()

        // Default is 2D
        assertEquals(MapViewMode.MODE_2D, controller.state.value.viewMode)

        // Switch to 3D Globe & enable 6 severe-storm layers (including CAPE)
        controller.setViewMode(MapViewMode.GLOBE_3D)
        val stormLayers = listOf(
            MapWeatherLayer.RADAR,
            MapWeatherLayer.SATELLITE,
            MapWeatherLayer.WIND,
            MapWeatherLayer.GUSTS,
            MapWeatherLayer.CAPE,
            MapWeatherLayer.PRECIPITATION
        )
        stormLayers.forEach { controller.toggleLayer(it) }
        assertEquals(stormLayers.toSet(), controller.state.value.activeLayers)

        // Switch to ECMWF + 500 hPa upper-air steering level + scrub timeline
        controller.setModel(MapForecastModel.ECMWF)
        controller.setPressureLevel(PressureLevel.HPA_500)

        val baseMillis = 1_783_250_000_000L
        val rvFeed = RainViewerMapsData(
            version = "2.0",
            generatedEpochMillis = baseMillis,
            host = "https://tilecache.rainviewer.com",
            radarPast = (0 until 10).map { i ->
                RainViewerFrame(
                    epochMillis = baseMillis + i * 600_000L,
                    path = "/v2/radar/${baseMillis / 1000L + i * 600L}",
                    isNowcast = false
                )
            },
            radarNowcast = emptyList(),
            satelliteInfrared = emptyList()
        )
        val futureTs = (1..8).map { i -> baseMillis + 6_000_000L + i * 3_600_000L }
        val (timeline, _) = MapLayerDataProvider.buildTimelineFrames(
            rainViewer = rvFeed,
            forecastTimestampsMillis = futureTs,
            nowEpochMillis = baseMillis + 6_000_000L
        )
        assertEquals(18, timeline.size)

        controller.setTimelineFrames(timeline, initialIndex = 9)
        controller.setPlaying(true)

        // Zoom down from Globe to 3D Street view over storm cell
        controller.setViewMode(MapViewMode.STREET_3D)
        val streetGrid = MapLayerDataProvider.buildSampleGrid(
            bounds = GeoBounds(south = 48.134, west = 11.581, north = 48.136, east = 11.583),
            rows = 2,
            cols = 2,
            minSpanDeg = 0.6
        )

        assertEquals(MapViewMode.STREET_3D, controller.state.value.viewMode)
        assertEquals(4, streetGrid.coordinates.size)
        assertEquals(0.6, streetGrid.lats.last() - streetGrid.lats.first(), 1e-4)
        assertEquals(stormLayers.toSet(), controller.state.value.activeLayers)
        assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
        assertEquals(PressureLevel.HPA_500, controller.state.value.pressureLevel)
        assertEquals(9, controller.state.value.timelineIndex)

        // Verify layer configs: Radar zoom<=7, Satellite fallback, CAPE surface-clamped, Gust field active
        val radarOverlay = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.RADAR,
            level = controller.state.value.pressureLevel,
            model = controller.state.value.model,
            frame = timeline[9],
            rainViewer = rvFeed,
            gridForecasts = emptyList(),
            gridPlan = null,
            owmApiKey = "demo",
            nowEpochMillis = timeline[9].epochMillis
        ) as? ResolvedLayerOverlay.RasterTiles
        val satFallback = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.SATELLITE,
            level = controller.state.value.pressureLevel,
            model = controller.state.value.model,
            frame = timeline[9],
            rainViewer = rvFeed,
            gridForecasts = emptyList(),
            gridPlan = null,
            owmApiKey = "demo",
            nowEpochMillis = timeline[9].epochMillis
        ) as? ResolvedLayerOverlay.RasterTiles
        val capeVar = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.CAPE, controller.state.value.pressureLevel).primaryVariable
        val wind500Var = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.WIND, controller.state.value.pressureLevel).primaryVariable
        val gustField = ScalarGridField(
            rows = 2,
            cols = 2,
            lats = listOf(48.0, 48.3),
            lons = listOf(11.4, 11.7),
            values = listOf(88.0, 88.0, 88.0, 88.0),
            unit = "km/h",
            isSurfaceFallback = true
        )
        val gustAtStorm = MapLayerDataProvider.interpolateScalar(gustField, 48.1351, 11.5820)

        assertNotNull(radarOverlay)
        assertTrue(radarOverlay!!.maxZoom <= 7)
        assertNotNull(satFallback)
        assertTrue(satFallback!!.urlTemplate.contains("clouds_new"))
        assertEquals("cape", capeVar)
        assertEquals("wind_speed_500hPa", wind500Var)
        assertEquals(88.0, gustAtStorm!!, 0.01)

        // Matching LiveWeatherOverlay storm mode stays THUNDERSTORM
        val overlayMode = resolveLiveEffectMode(
            weatherType = WeatherType.THUNDERSTORM,
            scenario = WeatherScenario.THUNDERSTORM,
            isNight = false
        )
        assertEquals(LiveEffectMode.THUNDERSTORM, overlayMode)
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Winter Ski Trip Planning
    // Features: F5, F6, F12, F13
    // -------------------------------------------------------------------------
    @Test
    fun t4_05_winterSkiTripPlanning_wintersportSubZeroClothingHistoryAnomalyAndRoute() {
        val base = E2ETestFixtures.BASE_EPOCH_MILLIS
        val winterArchive = E2ETestFixtures.sampleDailyHistoryData(days = 14, baseMinC = -6.0, baseMaxC = 2.0)
        val history14 = HistoricalNormalCalculator.buildHistorySeries(
            dailyDates = winterArchive.dates,
            mins = winterArchive.mins,
            maxs = winterArchive.maxs,
            means = winterArchive.means,
            days = 14
        )
        assertEquals(14, history14.points.size)

        val normalAfternoon = HistoricalNormalCalculator.computeSinusoidalNormalTemp(-6.0, 2.0, 14.0)
        val skiDayHours = (0 until 24).map { h ->
            E2ETestFixtures.sampleHourlyPoint(
                epochMillis = base + h * E2ETestFixtures.HOUR_MILLIS,
                tempC = -5.0,
                precipMm = if (h in 9..15) 1.0 else 0.0,
                precipProb = if (h in 9..15) 70 else 20,
                windKmh = 14.0,
                gustKmh = 24.0,
                uvIndex = if (h in 11..14) 4.0 else 0.5,
                capeJkg = 10.0,
                weatherCode = 71
            )
        }
        val anomaly = skiDayHours[14].tempC - normalAfternoon
        assertTrue("Sub-zero -5°C vs +1..2°C afternoon normal shows negative cold anomaly", anomaly < -3.0)

        // Wintersport tolerates sub-zero light snow whereas Cycling/Running warn
        val winterSportVerdict = SportAdvisor.evaluateHoursToSkip(SportType.WINTER_SPORT, skiDayHours)
        val cyclingVerdict = SportAdvisor.evaluateHoursToSkip(SportType.CYCLING, skiDayHours)
        val bestSkiWindow = SportAdvisor.findBestSportWindow(SportType.WINTER_SPORT, skiDayHours)

        assertTrue(
            "Wintersport must skip fewer hours on -5°C snow day than Cycling",
            winterSportVerdict.hoursToSkip.size < cyclingVerdict.hoursToSkip.size
        )
        assertNotNull("Wintersport should find a usable ski window on -5°C day", bestSkiWindow)

        // Sub-zero clothing recommendation
        val clothing = ClothingAdvisor.recommendClothing(
            tempC = -5.0,
            feelsLikeC = -10.0,
            precipProb = 70,
            windKmh = 14.0,
            uvIndex = 4.0
        )
        assertTrue(
            "Sub-zero clothing must recommend winter/warm layers",
            clothing.summary.contains("Winter", ignoreCase = true) ||
                clothing.summary.contains("warm", ignoreCase = true) ||
                clothing.summary.contains("Jacke", ignoreCase = true) ||
                clothing.layers.any { it.contains("Winter", ignoreCase = true) || it.contains("Handschuh", ignoreCase = true) || it.contains("Mütze", ignoreCase = true) }
        )

        // Driving route München -> Garmisch-Partenkirchen + Maps handoff
        val alpineRoute = E2ETestFixtures.munichAlpineRoute(mode = "driving")
        val routePoints = alpineRoute.waypoints.mapIndexed { idx, (lat, lon) ->
            RoutePoint("Stage $idx", lat, lon, offsetMinutesFromStart = idx * 35)
        }
        val routeResult = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = alpineRoute.originName,
            destination = alpineRoute.destinationName,
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = base + 9 * E2ETestFixtures.HOUR_MILLIS,
            routePoints = routePoints,
            hourlyForecasts = skiDayHours,
            nowMillis = base
        )
        val mapsUri = RouteWeatherCalculator.buildMapsHandoffUri(
            originLat = alpineRoute.originLat,
            originLon = alpineRoute.originLon,
            destLat = alpineRoute.destinationLat,
            destLon = alpineRoute.destinationLon,
            destLabel = alpineRoute.destinationName,
            mode = RouteTransportMode.DRIVING
        )
        assertTrue(routeResult.segments.size >= 3)
        assertNotNull(routeResult.arrivalWeather)
        assertEquals(-5.0, routeResult.arrivalWeather!!.tempC, 0.1)
        assertTrue(mapsUri.contains("Garmisch") || mapsUri.contains("47.4917"))
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Multi-Day Forecast Verification & Track Record Audit
    // Features: F5, F7
    // -------------------------------------------------------------------------
    @Test
    fun t4_06_multiDayForecastVerificationAndTrackRecordAudit_snapshotActualAndConfidence() {
        val store = E2ETestFixtures.InMemoryKeyValueStore()
        val trackRepo = TrackRecordRepository(store)
        val base = E2ETestFixtures.BASE_EPOCH_MILLIS

        // 7-day historical baseline
        val archive7 = E2ETestFixtures.sampleDailyHistoryData(days = 30, baseMinC = 11.0, baseMaxC = 21.0)
        val graph7 = HistoricalNormalCalculator.buildHistorySeries(
            dailyDates = archive7.dates,
            mins = archive7.mins,
            maxs = archive7.maxs,
            means = archive7.means,
            days = 7
        )
        assertEquals(7, graph7.points.size)

        // Day 1–3 forecast snapshots vs actuals: ecmwf closest (0.3°C), icon_d2 medium (1.5°C), gfs off (3.5°C)
        val frankfurtLat = 50.1109
        val frankfurtLon = 8.6821
        val forecasts = listOf(
            Triple(base + 1 * E2ETestFixtures.DAY_MILLIS, 20.0 to 0.0, 20.5 to 0.0),
            Triple(base + 2 * E2ETestFixtures.DAY_MILLIS, 18.0 to 4.0, 17.5 to 3.2),
            Triple(base + 3 * E2ETestFixtures.DAY_MILLIS, 22.0 to 0.0, 23.0 to 0.0)
        )
        forecasts.forEach { (epoch, pred, actual) ->
            val actualTemp = actual.first
            trackRepo.recordForecastSnapshot(
                ForecastSnapshot(
                    id = "50.11,8.68_$epoch",
                    locationKey = "50.11,8.68",
                    cityName = "Frankfurt",
                    latitude = frankfurtLat,
                    longitude = frankfurtLon,
                    capturedAtMillis = epoch - E2ETestFixtures.HOUR_MILLIS,
                    targetEpochMillis = epoch,
                    predictedTempC = pred.first,
                    predictedPrecipMm = pred.second,
                    predictedPrecipProb = if (pred.second >= 0.1) 80 else 10,
                    predictedWindKmh = 12.0,
                    predictedDrySportWindow = pred.second == 0.0,
                    modelPredictedTemps = mapOf(
                        "ecmwf_ifs025" to actualTemp + 0.3,
                        "icon_d2" to actualTemp + 1.5,
                        "gfs_global" to actualTemp - 3.5
                    )
                )
            )
            trackRepo.verifyWithObservations(
                latitude = frankfurtLat,
                longitude = frankfurtLon,
                observations = listOf(
                    ObservedWeatherPoint(
                        epochMillis = epoch,
                        tempC = actualTemp,
                        precipMm = actual.second,
                        windKmh = 12.0
                    )
                )
            )
        }

        // Reload repository from store to confirm persistence across restarts
        val reloadedSummary = TrackRecordRepository(store).getTrackRecordSummary()
        assertEquals(3, reloadedSummary.verifiedCount)
        assertNotNull(reloadedSummary.meanTempErrorC)
        assertTrue(reloadedSummary.meanTempErrorC!! in 0.4..1.0)
        assertEquals(100.0, reloadedSummary.rainHitRatePct!!.toDouble(), 1.0)
        assertEquals(100.0, reloadedSummary.drySportWindowAccuracyPct!!.toDouble(), 1.0)
        assertTrue(reloadedSummary.bestModel!!.contains("ecmwf", ignoreCase = true))

        // Day 4 shows strong divergence across models -> confidence drops to Unsicher
        val divergentConfidence = ForecastConfidenceEngine.computeConfidence(
            modelTemps = mapOf(
                "ecmwf_ifs025" to listOf(16.0, 17.0, 16.5),
                "gfs_global" to listOf(28.0, 29.0, 27.5),
                "icon_d2" to listOf(21.0, 20.5, 22.0),
                "owm" to listOf(18.0, 19.0, 18.5)
            ),
            modelPrecips = mapOf(
                "ecmwf_ifs025" to listOf(8.0, 10.0, 6.0),
                "gfs_global" to listOf(0.0, 0.0, 0.0),
                "icon_d2" to listOf(2.0, 1.0, 3.0),
                "owm" to listOf(5.0, 4.0, 6.0)
            ),
            modelWinds = mapOf(
                "ecmwf_ifs025" to listOf(35.0, 40.0, 38.0),
                "gfs_global" to listOf(8.0, 10.0, 9.0),
                "icon_d2" to listOf(20.0, 22.0, 21.0),
                "owm" to listOf(25.0, 24.0, 26.0)
            )
        )
        assertEquals("Unsicher", divergentConfidence.tierLabel)
        assertTrue(divergentConfidence.percent < 50)
        assertTrue(divergentConfidence.divergenceExplanation.isNotBlank())
    }

    // -------------------------------------------------------------------------
    // Scenario 7: German Voice/Free-Text Assistant Session
    // Features: F6, F11, F12, F13
    // -------------------------------------------------------------------------
    @Test
    fun t4_07_germanVoiceFreeTextAssistantSession_allFiveQueryIntentsAndFallback() {
        val base = E2ETestFixtures.BASE_EPOCH_MILLIS
        val hours = E2ETestFixtures.sample24HourSeries(startEpochMillis = base, rainHours = setOf(8, 9))

        // 1. WEATHER_QUERY
        val q1 = GermanFreeTextParser.parseIntent("Wie wird das Wetter morgen in Hamburg?")
        assertEquals(QueryIntentCategory.WEATHER_QUERY, q1.category)
        assertTrue(q1.responseHint.isNotBlank())

        // 2. SPORT_WINDOW -> answered using SportAdvisor
        val q2 = GermanFreeTextParser.parseIntent("Wann kann ich heute laufen gehen?")
        assertEquals(QueryIntentCategory.SPORT_WINDOW, q2.category)
        val bestWindow = SportAdvisor.findBestSportWindow(SportType.RUNNING, hours)
        assertNotNull(bestWindow)

        // 3. EVENT_PLANNING -> answered using EventRescheduler
        val q3 = GermanFreeTextParser.parseIntent("Muss mein Grillen im Park wegen Regen verschoben werden?")
        assertEquals(QueryIntentCategory.EVENT_PLANNING, q3.category)
        val event = CalendarEvent(
            id = 4071L,
            title = "Grillen im Park",
            description = "BBQ draußen",
            location = "Stadtpark",
            startMillis = base + 8 * E2ETestFixtures.HOUR_MILLIS,
            endMillis = base + 10 * E2ETestFixtures.HOUR_MILLIS,
            allDay = false
        )
        val reschedules = EventRescheduler.suggestReschedules(listOf(event), hours, SportType.WALKING, nowMillis = base)
        assertEquals(1, reschedules.size)

        // 4. ROUTE_WEATHER -> answered using RouteWeatherCalculator
        val q4 = GermanFreeTextParser.parseIntent("Regnet es auf meinem Arbeitsweg nach Potsdam?")
        assertEquals(QueryIntentCategory.ROUTE_WEATHER, q4.category)
        val routeRes = RouteWeatherCalculator.computeRouteAndDeparture(
            origin = "Berlin",
            destination = "Potsdam",
            mode = RouteTransportMode.DRIVING,
            targetArrivalMillis = base + 9 * E2ETestFixtures.HOUR_MILLIS,
            routePoints = listOf(
                RoutePoint("Berlin", 52.52, 13.405, 0),
                RoutePoint("Potsdam", 52.40, 13.06, 40)
            ),
            hourlyForecasts = hours,
            nowMillis = base
        )
        assertTrue(routeRes.segments.isNotEmpty())

        // 5. CLOTHING -> answered using ClothingAdvisor
        val q5 = GermanFreeTextParser.parseIntent("Was soll ich heute anziehen?")
        assertEquals(QueryIntentCategory.CLOTHING, q5.category)
        val clothing = ClothingAdvisor.recommendClothing(12.0, 10.0, 85, 22.0, 2.0)
        assertTrue(clothing.summary.isNotBlank())

        // 6. Fallback for gibberish & blank query
        val qFallback = GermanFreeTextParser.parseIntent("qwerty asdf 999")
        val qEmpty = GermanFreeTextParser.parseIntent("   ")
        assertEquals(QueryIntentCategory.UNKNOWN, qFallback.category)
        assertEquals(QueryIntentCategory.UNKNOWN, qEmpty.category)
        assertTrue(qFallback.responseHint.isNotBlank())
        assertTrue(qEmpty.responseHint.isNotBlank())
    }

    // -------------------------------------------------------------------------
    // Scenario 8: New City Onboarding & Widget/Notification Sync
    // Features: F1, F3, F14
    // -------------------------------------------------------------------------
    @Test
    fun t4_08_newCityOnboardingAndWidgetNotificationSync_favoritePromptsAndSharedPrefs() {
        val favStore = E2ETestFixtures.InMemoryKeyValueStore()
        val sharedPrefs = E2ETestFixtures.InMemoryKeyValueStore()
        val widgetStore = E2ETestFixtures.InMemoryKeyValueStore()
        val favoritesRepo = FavoritesRepository(favStore)

        // 1. User searches & adds brand-new city Dresden (0 existing diorama images)
        val dresden = FavoriteLocation(
            id = "dresden_de",
            name = "Dresden",
            country = "Deutschland",
            latitude = 51.0504,
            longitude = 13.7373,
            addedAtMillis = E2ETestFixtures.BASE_EPOCH_MILLIS
        )
        favoritesRepo.addFavorite(dresden)
        favoritesRepo.setActiveFavoriteId(dresden.id)

        // 2. All 13 WeatherScenario images are missing -> generate full prompt batch
        val allScenarios = E2ETestFixtures.allWeatherScenarios()
        assertEquals(13, allScenarios.size)
        val prompts = CityImagePromptGenerator.buildPromptsForMissing(
            cityLabel = "${dresden.name}, ${dresden.country}",
            missingScenarios = allScenarios,
            additionalLandmarks = "Frauenkirche, Semperoper"
        )
        val copyAllText = CityImagePromptGenerator.formatAllPrompts(prompts)
        assertEquals(13, prompts.size)
        assertTrue(copyAllText.contains("CITY=Dresden, Deutschland"))
        assertTrue(copyAllText.contains("ADDITIONAL_LANDMARKS (optional): Frauenkirche, Semperoper"))

        // 3. Sync new active city to shared "frog_weather_prefs"
        sharedPrefs.putFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, dresden.latitude.toFloat())
        sharedPrefs.putFloat(AppPrefsKeys.KEY_LAST_CITY_LON, dresden.longitude.toFloat())
        sharedPrefs.putString(AppPrefsKeys.KEY_LAST_CITY_NAME, dresden.name)
        sharedPrefs.putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, dresden.country)

        // 4. Widget falls back to shared prefs and handles missing local city image safely
        val widgetLoc = WeatherWidgetProvider.resolveWidgetLocation(
            appWidgetId = 7,
            widgetStore = widgetStore,
            sharedAppStore = sharedPrefs
        )
        val localImg = WeatherWidgetProvider.resolveLocalCityImageFilename(
            scenario = WeatherScenario.PARTLY_CLOUDY,
            cityImages = emptyMap()
        )
        val notifCoords = WeatherNotificationService.resolveCoordinates(
            intentLat = null,
            intentLon = null,
            savedNotifLat = null,
            savedNotifLon = null,
            savedCityLat = sharedPrefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 0f).toDouble(),
            savedCityLon = sharedPrefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 0f).toDouble()
        )

        assertNotNull(widgetLoc)
        assertEquals("Dresden", widgetLoc!!.cityName)
        assertEquals(51.0504, widgetLoc.latitude, 0.01)
        assertNull("Missing local image should return null cleanly so widget uses drawable/emoji fallback", localImg)
        assertNotNull(notifCoords)
        assertEquals(51.0504, notifCoords!!.first, 0.01)
        assertEquals(13.7373, notifCoords.second, 0.01)
    }
}

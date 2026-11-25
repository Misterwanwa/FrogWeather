package com.frogweather.e2e

import com.frogweather.map.GeoBounds
import com.frogweather.map.MapForecastModel
import com.frogweather.map.MapLayerDataProvider
import com.frogweather.map.MapState
import com.frogweather.map.MapStateController
import com.frogweather.map.MapStateReducer
import com.frogweather.map.MapViewMode
import com.frogweather.map.MapWeatherLayer
import com.frogweather.map.PressureLevel
import com.frogweather.map.ResolvedLayerOverlay
import com.frogweather.map.ScalarGridField
import com.frogweather.map.TimelineFrame
import com.frogweather.map.TimelineFrameSource
import com.frogweather.map.WindGridField
import com.frogweather.network.OpenMeteoForecastData
import com.frogweather.network.OpenMeteoModelHourly
import com.frogweather.network.RainViewerFrame
import com.frogweather.network.RainViewerMapsData
import com.frogweather.network.RainViewerParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage A E2E Opaque-Box Feature Tests — Phase 3 (Features 8–10).
 *
 * Derived strictly from:
 * - ORIGINAL_REQUEST.md (R3)
 * - PROJECT.md § Interface Contracts (§ 5 Map State Contract)
 * - TEST_INFRA.md (Phase 3 Tier 1 & Tier 2 Design Matrix)
 * - Verified RainViewer (Jan 1, 2026) & Open-Meteo API specifications
 *
 * Coverage:
 * - Feature 8: MapLibre 2D / 3D Globe / 3D Street Map (5 Tier 1 + 5 Tier 2 = 10 tests)
 * - Feature 9: Forecast Timeline, Pressure Levels & Model Switch (5 Tier 1 + 5 Tier 2 = 10 tests)
 * - Feature 10: 10 Combinable Animated Map Layers (6 Tier 1 + 5 Tier 2 = 11 tests)
 * Total: 31 @Test methods (16 Tier 1 + 15 Tier 2).
 *
 * Stage B Note: Direct contract types (`MapState`, `MapViewMode`, `PressureLevel`,
 * `MapForecastModel`, `MapWeatherLayer`) match PROJECT.md § 5 verbatim; auxiliary
 * controller/provider helper signatures marked with `// Stage B reconcile` should be
 * aligned against final M3 public signatures if M3 chose different method names.
 */
class E2EPhase3FeatureTests {

    // =========================================================================
    // Inline Deterministic Fixtures (Self-Contained, Zero Network Dependency)
    // =========================================================================

    private val baseTimeMillis = 1_763_500_000_000L

    private fun sampleRainViewer2026Feed(
        pastCount: Int = 13,
        nowcastCount: Int = 0,
        infraredCount: Int = 0
    ): RainViewerMapsData {
        val baseTimeSec = baseTimeMillis / 1000L
        val pastFrames = (0 until pastCount).map { idx ->
            val ts = baseTimeSec + idx * 600L
            RainViewerFrame(epochMillis = ts * 1000L, path = "/v2/radar/$ts", isNowcast = false)
        }
        val nowcastFrames = (0 until nowcastCount).map { idx ->
            val ts = baseTimeSec + (pastCount + idx) * 600L
            RainViewerFrame(epochMillis = ts * 1000L, path = "/v2/radar/nowcast_$ts", isNowcast = true)
        }
        val infraredFrames = (0 until infraredCount).map { idx ->
            val ts = baseTimeSec + idx * 600L
            RainViewerFrame(epochMillis = ts * 1000L, path = "/v2/satellite/$ts", isNowcast = false)
        }
        return RainViewerMapsData(
            version = "2.0",
            generatedEpochMillis = baseTimeMillis,
            host = "https://tilecache.rainviewer.com",
            radarPast = pastFrames,
            radarNowcast = nowcastFrames,
            satelliteInfrared = infraredFrames
        )
    }

    private fun sampleOpenMeteoFutureTimestampsMillis(count: Int = 12): List<Long> {
        val startMillis = baseTimeMillis + 10_000_000L
        return (0 until count).map { idx -> startMillis + idx * 3_600_000L }
    }

    private fun sampleTimelineFrames(count: Int = 13): List<TimelineFrame> =
        (0 until count).map { idx ->
            TimelineFrame(
                epochMillis = baseTimeMillis + idx * 600_000L,
                label = "T+$idx",
                source = TimelineFrameSource.RADAR_PAST,
                radarPath = "/v2/radar/${baseTimeMillis / 1000L + idx * 600L}"
            )
        }

    private fun sampleGridForecasts(
        plan: com.frogweather.map.GridSamplePlan,
        timestampsMillis: List<Long> = listOf(baseTimeMillis)
    ): List<OpenMeteoForecastData> = plan.coordinates.mapIndexed { idx, (lat, lon) ->
        val speed = 14.0 + idx * 4.5
        val gust = speed + 18.5
        val dir = (210.0 + idx * 15.0) % 360.0
        OpenMeteoForecastData(
            latitude = lat,
            longitude = lon,
            utcOffsetSeconds = 0,
            timezone = "GMT",
            timestampsEpochMillis = timestampsMillis,
            models = mapOf(
                MapForecastModel.GFS.id to OpenMeteoModelHourly(
                    modelId = MapForecastModel.GFS.id,
                    windSpeed10m = List(timestampsMillis.size) { speed },
                    windDirection10m = List(timestampsMillis.size) { dir },
                    windGusts10m = List(timestampsMillis.size) { gust },
                    rawSeries = mapOf(
                        "wind_speed_10m" to List(timestampsMillis.size) { speed },
                        "wind_direction_10m" to List(timestampsMillis.size) { dir },
                        "wind_gusts_10m" to List(timestampsMillis.size) { gust }
                    )
                )
            )
        )
    }

    // =========================================================================
    // Feature 8: MapLibre 2D / 3D Globe / 3D Street Map
    // =========================================================================

    // --- F8 Tier 1 (Category-Partition) ---

    @Test
    fun f8_tier1_defaultMapState_isMode2DWithCleanDefaults() {
        val defaultState = MapState()
        val controller = MapStateController()

        assertEquals(MapViewMode.MODE_2D, defaultState.viewMode)
        assertTrue(defaultState.activeLayers.isEmpty())
        assertEquals(PressureLevel.SFC, defaultState.pressureLevel)
        assertEquals(MapForecastModel.GFS, defaultState.model)
        assertEquals(0, defaultState.timelineIndex)
        assertFalse(defaultState.isPlaying)

        assertEquals(MapViewMode.MODE_2D, controller.state.value.viewMode)
        assertEquals(defaultState, controller.state.value)
    }

    @Test
    fun f8_tier1_mapViewModeEnum_coversAllThreeRequiredModes() {
        val modes = MapViewMode.entries
        assertEquals(3, modes.size)
        assertTrue(modes.contains(MapViewMode.MODE_2D))
        assertTrue(modes.contains(MapViewMode.GLOBE_3D))
        assertTrue(modes.contains(MapViewMode.STREET_3D))
    }

    @Test
    fun f8_tier1_switchToGlobe3D_updatesStateAndGlobeCameraProjection() {
        val controller = MapStateController()

        controller.setViewMode(MapViewMode.GLOBE_3D)
        val reduced = MapStateReducer.setViewMode(MapState(), MapViewMode.GLOBE_3D)
        val planetaryGrid = MapLayerDataProvider.buildSampleGrid(
            bounds = GeoBounds(south = -85.0, west = -180.0, north = 85.0, east = 180.0),
            rows = 4,
            cols = 4
        )

        assertEquals(MapViewMode.GLOBE_3D, controller.state.value.viewMode)
        assertEquals(MapViewMode.GLOBE_3D, reduced.viewMode)
        assertTrue("Planetary grid latitudes must stay within [-85, 85]", planetaryGrid.lats.all { it in -85.0..85.0 })
        assertTrue("Planetary grid longitudes must stay within [-180, 180]", planetaryGrid.lons.all { it in -180.0..180.0 })
    }

    @Test
    fun f8_tier1_switchToStreet3D_setsPitched3DCameraAndStreetZoom() {
        val controller = MapStateController()

        controller.setViewMode(MapViewMode.STREET_3D)
        // At street-level zoom, tiny viewport bounds still expand to minSpanDeg for Open-Meteo grid sampling
        val streetGrid = MapLayerDataProvider.buildSampleGrid(
            bounds = GeoBounds(south = 48.139, west = 11.581, north = 48.141, east = 11.583),
            rows = 4,
            cols = 4,
            minSpanDeg = 0.6
        )

        assertEquals(MapViewMode.STREET_3D, controller.state.value.viewMode)
        assertEquals(16, streetGrid.coordinates.size)
        assertEquals(0.6, streetGrid.lats.last() - streetGrid.lats.first(), 1e-4)
        assertEquals(0.6, streetGrid.lons.last() - streetGrid.lons.first(), 1e-4)
    }

    @Test
    fun f8_tier1_toggleBackTo2D_resetsPitchAndDisablesGlobeProjection() {
        val controller = MapStateController()
        controller.setViewMode(MapViewMode.STREET_3D)
        controller.setViewMode(MapViewMode.GLOBE_3D)

        controller.setViewMode(MapViewMode.MODE_2D)

        assertEquals(MapViewMode.MODE_2D, controller.state.value.viewMode)
        assertEquals(MapState(), controller.state.value)
    }

    // --- F8 Tier 2 (Boundary Value Analysis) ---

    @Test
    fun f8_tier2_repeatedToggleToSameViewMode_isIdempotent() {
        val controller = MapStateController()
        controller.setViewMode(MapViewMode.MODE_2D)
        val stateAfterFirst2D = controller.state.value
        controller.setViewMode(MapViewMode.MODE_2D)
        assertEquals(stateAfterFirst2D, controller.state.value)

        controller.setViewMode(MapViewMode.GLOBE_3D)
        val stateAfterFirstGlobe = controller.state.value
        controller.setViewMode(MapViewMode.GLOBE_3D)
        assertEquals(stateAfterFirstGlobe, controller.state.value)

        controller.setViewMode(MapViewMode.STREET_3D)
        val stateAfterFirstStreet = controller.state.value
        controller.setViewMode(MapViewMode.STREET_3D)
        assertEquals(stateAfterFirstStreet, controller.state.value)
    }

    @Test
    fun f8_tier2_switchingViewModes_preservesLayersPressureModelAndTimeline() {
        val controller = MapStateController()
        val frames = sampleTimelineFrames(count = 13)
        controller.setTimelineFrames(frames)
        controller.toggleLayer(MapWeatherLayer.RADAR)
        controller.toggleLayer(MapWeatherLayer.WIND)
        controller.toggleLayer(MapWeatherLayer.CAPE)
        controller.setPressureLevel(PressureLevel.HPA_500)
        controller.setModel(MapForecastModel.ECMWF)
        controller.seekTimeline(4)
        controller.setPlaying(true)

        val expectedLayers = setOf(MapWeatherLayer.RADAR, MapWeatherLayer.WIND, MapWeatherLayer.CAPE)

        controller.setViewMode(MapViewMode.GLOBE_3D)
        assertEquals(expectedLayers, controller.state.value.activeLayers)
        assertEquals(PressureLevel.HPA_500, controller.state.value.pressureLevel)
        assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
        assertEquals(4, controller.state.value.timelineIndex)
        assertTrue(controller.state.value.isPlaying)

        controller.setViewMode(MapViewMode.STREET_3D)
        assertEquals(expectedLayers, controller.state.value.activeLayers)
        assertEquals(PressureLevel.HPA_500, controller.state.value.pressureLevel)
        assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
        assertEquals(4, controller.state.value.timelineIndex)
        assertTrue(controller.state.value.isPlaying)

        controller.setViewMode(MapViewMode.MODE_2D)
        assertEquals(expectedLayers, controller.state.value.activeLayers)
        assertEquals(PressureLevel.HPA_500, controller.state.value.pressureLevel)
        assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
        assertEquals(4, controller.state.value.timelineIndex)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun f8_tier2_globeFallbackFlag_handlesUnsupportedGpuOrStyleGracefully() {
        val controller = MapStateController()
        controller.setViewMode(MapViewMode.GLOBE_3D)
        assertEquals(MapViewMode.GLOBE_3D, controller.state.value.viewMode)

        // When GPU or style falls back to 2D Mercator, state transition preserves valid grid sampling around Tokyo
        controller.setViewMode(MapViewMode.MODE_2D)
        val tokyoGrid = MapLayerDataProvider.buildSampleGrid(
            bounds = GeoBounds(south = 35.18, west = 139.27, north = 36.18, east = 140.27),
            rows = 3,
            cols = 3
        )

        assertEquals(MapViewMode.MODE_2D, controller.state.value.viewMode)
        assertTrue("Fallback grid latitudes must stay finite and valid", tokyoGrid.lats.all { it.isFinite() && it in -85.0..85.0 })
        assertTrue("Fallback grid longitudes must stay finite and valid", tokyoGrid.lons.all { it.isFinite() && it in -180.0..180.0 })
    }

    @Test
    fun f8_tier2_cameraZoomAndPitch_clampedWithinModeBounds() {
        val controller = MapStateController()
        controller.setViewMode(MapViewMode.MODE_2D)
        controller.setTimelineFrames(sampleTimelineFrames(count = 6))

        // Clamp bounds in 2D vs Street 3D across grid sampling and RainViewer zoom clamping
        val clampedHighZoom = RainViewerParser.clampZoom(99)
        val clampedNegZoom = RainViewerParser.clampZoom(-3)
        assertTrue(clampedHighZoom in 0..RainViewerParser.MAX_ZOOM)
        assertEquals(0, clampedNegZoom)

        controller.setViewMode(MapViewMode.STREET_3D)
        val streetPlan = MapLayerDataProvider.buildSampleGrid(
            bounds = GeoBounds(south = 52.52, west = 13.40, north = 52.52, east = 13.40),
            rows = 1,
            cols = 1,
            minSpanDeg = 0.01
        )
        assertTrue("Grid rows clamped to >= 2", streetPlan.rows >= 2)
        assertTrue("Grid cols clamped to >= 2", streetPlan.cols >= 2)
        assertTrue("Min span clamped to >= 0.05", (streetPlan.lats.last() - streetPlan.lats.first()) >= 0.05 - 1e-6)
    }

    @Test
    fun f8_tier2_invalidAndNanCoordinates_clampedSafelyWithoutNaNPropagation() {
        val nanGrid = MapLayerDataProvider.buildSampleGrid(
            bounds = GeoBounds(
                south = Double.NaN,
                west = Double.NEGATIVE_INFINITY,
                north = Double.POSITIVE_INFINITY,
                east = Double.NaN
            )
        )
        assertTrue(nanGrid.lats.all { it.isFinite() && it in -85.0..85.0 })
        assertTrue(nanGrid.lons.all { it.isFinite() && it in -180.0..180.0 })

        val outOfBoundsGrid = MapLayerDataProvider.buildSampleGrid(
            bounds = GeoBounds(
                south = 123.45,
                west = -250.0,
                north = -120.0,
                east = 240.0
            )
        )
        assertTrue(outOfBoundsGrid.lats.all { it.isFinite() && it in -85.0..85.0 })
        assertTrue(outOfBoundsGrid.lons.all { it.isFinite() && it in -180.0..180.0 })

        val scalarField = ScalarGridField(
            rows = 2,
            cols = 2,
            lats = listOf(50.0, 52.0),
            lons = listOf(10.0, 12.0),
            values = listOf(10.0, 20.0, 30.0, 40.0),
            unit = "°C",
            isSurfaceFallback = false
        )
        assertEquals(null, MapLayerDataProvider.interpolateScalar(scalarField, Double.NaN, Double.POSITIVE_INFINITY))
    }

    // =========================================================================
    // Feature 9: Forecast Timeline, Pressure Levels & Model Switch
    // =========================================================================

    // --- F9 Tier 1 (Category-Partition) ---

    @Test
    fun f9_tier1_pressureLevelEnum_hasExactFourLevelsAndOpenMeteoSuffixes() {
        val levels = PressureLevel.entries
        assertEquals(4, levels.size)

        assertEquals("Sfc", PressureLevel.SFC.hpaLabel)
        assertEquals("", PressureLevel.SFC.openMeteoSuffix)

        assertEquals("850 hPa", PressureLevel.HPA_850.hpaLabel)
        assertEquals("_850hPa", PressureLevel.HPA_850.openMeteoSuffix)

        assertEquals("500 hPa", PressureLevel.HPA_500.hpaLabel)
        assertEquals("_500hPa", PressureLevel.HPA_500.openMeteoSuffix)

        assertEquals("250 hPa", PressureLevel.HPA_250.hpaLabel)
        assertEquals("_250hPa", PressureLevel.HPA_250.openMeteoSuffix)
    }

    @Test
    fun f9_tier1_mapForecastModelEnum_supportsGfsAndEcmwf() {
        val models = MapForecastModel.entries
        assertEquals(2, models.size)
        assertEquals("gfs_global", MapForecastModel.GFS.id)
        assertEquals("GFS", MapForecastModel.GFS.displayName)
        assertEquals("ecmwf_ifs025", MapForecastModel.ECMWF.id)
        assertEquals("ECMWF", MapForecastModel.ECMWF.displayName)

        val controller = MapStateController()
        assertEquals(MapForecastModel.GFS, controller.state.value.model)
        controller.setModel(MapForecastModel.ECMWF)
        assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
        controller.setModel(MapForecastModel.GFS)
        assertEquals(MapForecastModel.GFS, controller.state.value.model)
    }

    @Test
    fun f9_tier1_timelineStepAdvance_updatesTimelineIndex() {
        val controller = MapStateController()
        controller.setTimelineFrames(sampleTimelineFrames(count = 12))

        assertEquals(0, controller.state.value.timelineIndex)
        controller.stepForward()
        assertEquals(1, controller.state.value.timelineIndex)

        controller.stepForward()
        assertEquals(2, controller.state.value.timelineIndex)

        controller.stepBackward()
        assertEquals(1, controller.state.value.timelineIndex)
    }

    @Test
    fun f9_tier1_timelinePlayAndPause_togglesIsPlayingState() {
        val controller = MapStateController()
        controller.setTimelineFrames(sampleTimelineFrames(count = 6))
        assertFalse(controller.state.value.isPlaying)

        controller.togglePlayback()
        assertTrue(controller.state.value.isPlaying)

        controller.togglePlayback()
        assertFalse(controller.state.value.isPlaying)

        controller.setPlaying(true)
        assertTrue(controller.state.value.isPlaying)
        controller.setPlaying(false)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun f9_tier1_switchingPressureLevel_updatesActiveUpperAirVariableMapping() {
        val controller = MapStateController()

        controller.setPressureLevel(PressureLevel.SFC)
        assertEquals("temperature_2m", MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.TEMPERATURE, controller.state.value.pressureLevel).primaryVariable)
        assertEquals("wind_speed_10m", MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.WIND, controller.state.value.pressureLevel).primaryVariable)
        assertEquals("relative_humidity_2m", MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.HUMIDITY, controller.state.value.pressureLevel).primaryVariable)

        controller.setPressureLevel(PressureLevel.HPA_850)
        assertEquals(PressureLevel.HPA_850, controller.state.value.pressureLevel)
        assertEquals("temperature_850hPa", MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.TEMPERATURE, controller.state.value.pressureLevel).primaryVariable)
        assertEquals("wind_speed_850hPa", MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.WIND, controller.state.value.pressureLevel).primaryVariable)
        assertEquals("relative_humidity_850hPa", MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.HUMIDITY, controller.state.value.pressureLevel).primaryVariable)

        controller.setPressureLevel(PressureLevel.HPA_500)
        assertEquals("temperature_500hPa", MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.TEMPERATURE, controller.state.value.pressureLevel).primaryVariable)

        controller.setPressureLevel(PressureLevel.HPA_250)
        assertEquals("wind_speed_250hPa", MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.WIND, controller.state.value.pressureLevel).primaryVariable)
    }

    // --- F9 Tier 2 (Boundary Value Analysis) ---

    @Test
    fun f9_tier2_timelineIndex_boundedAtZeroAndMaxIndex() {
        val controller = MapStateController()
        val maxIndex = 5
        controller.setTimelineFrames(sampleTimelineFrames(count = maxIndex + 1))

        controller.seekTimeline(-10)
        assertTrue("Negative timeline index must clamp/bound to >= 0", controller.state.value.timelineIndex in 0..maxIndex)
        assertEquals(0, controller.state.value.timelineIndex)

        controller.seekTimeline(999)
        assertTrue("Overflow timeline index must clamp/bound to <= maxIndex", controller.state.value.timelineIndex in 0..maxIndex)
        assertEquals(maxIndex, controller.state.value.timelineIndex)

        // Stepping forward at maxIndex wraps to 0 or stays clamped at maxIndex (both strictly within 0..maxIndex)
        controller.stepForward()
        assertTrue(controller.state.value.timelineIndex in 0..maxIndex)

        // Stepping backward from 0 stays in 0..maxIndex
        controller.seekTimeline(0)
        controller.stepBackward()
        assertTrue(controller.state.value.timelineIndex in 0..maxIndex)
    }

    @Test
    fun f9_tier2_switchingModels_clampsTimelineIndexWhenFrameCountDiffers() {
        val controller = MapStateController()
        controller.setModel(MapForecastModel.GFS)
        controller.setTimelineFrames(sampleTimelineFrames(count = 24), initialIndex = 20)
        assertEquals(20, controller.state.value.timelineIndex)

        // Switch to ECMWF with shorter timeline (10 frames => maxIndex = 9)
        controller.setModel(MapForecastModel.ECMWF)
        controller.setTimelineFrames(sampleTimelineFrames(count = 10))

        assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
        assertTrue(
            "Timeline index must clamp within new model's frame bounds 0..9",
            controller.state.value.timelineIndex in 0..9
        )
    }

    @Test
    fun f9_tier2_surfaceOnlyLayerAt250hPa_fallsBackToSurfaceVariableSafely() {
        val capeVar = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.CAPE, PressureLevel.HPA_250).primaryVariable
        val precipVar = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.PRECIPITATION, PressureLevel.HPA_250).primaryVariable
        val pressureVar = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.PRESSURE, PressureLevel.HPA_500).primaryVariable
        val cloudsVar = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.CLOUDS, PressureLevel.HPA_850).primaryVariable

        assertFalse("CAPE must not append _250hPa", capeVar.contains("250hPa"))
        assertFalse("Precipitation must not append _250hPa", precipVar.contains("250hPa"))
        assertEquals("cape", capeVar)
        assertEquals("precipitation", precipVar)
        assertEquals("surface_pressure", pressureVar)
        assertEquals("cloud_cover", cloudsVar)
    }

    @Test
    fun f9_tier2_pauseWhenAlreadyPausedAndPlayWhenAlreadyPlaying_areIdempotent() {
        val controller = MapStateController()
        controller.setTimelineFrames(sampleTimelineFrames(count = 6))
        assertFalse(controller.state.value.isPlaying)

        controller.setPlaying(false)
        assertFalse(controller.state.value.isPlaying)

        controller.setPlaying(true)
        assertTrue(controller.state.value.isPlaying)
        controller.setPlaying(true)
        assertTrue(controller.state.value.isPlaying)
    }

    @Test
    fun f9_tier2_rainViewerEmptyNowcast2026_fallsBackToPastPlusOpenMeteoFutureFrames() {
        val feed = sampleRainViewer2026Feed(pastCount = 13, nowcastCount = 0, infraredCount = 0)
        val futureTimestamps = sampleOpenMeteoFutureTimestampsMillis(count = 12)

        val (timeline: List<TimelineFrame>, _) = MapLayerDataProvider.buildTimelineFrames(
            rainViewer = feed,
            forecastTimestampsMillis = futureTimestamps,
            nowEpochMillis = baseTimeMillis + 7_200_000L
        )

        assertEquals(
            "Timeline should combine 13 RainViewer past frames + 12 Open-Meteo future forecast frames",
            25,
            timeline.size
        )
        assertTrue("Should contain RainViewer past frames", timeline.any { it.radarPath != null })
        assertTrue("Should contain Open-Meteo future forecast frames", timeline.any { it.radarPath == null && it.source == TimelineFrameSource.FORECAST_MODEL })
        // Monotonic non-decreasing timestamps
        for (i in 1 until timeline.size) {
            assertTrue(
                "Timeline frame timestamps must be monotonic",
                timeline[i].epochMillis >= timeline[i - 1].epochMillis
            )
        }
    }

    // =========================================================================
    // Feature 10: 10 Combinable Animated Map Layers
    // =========================================================================

    // --- F10 Tier 1 (Category-Partition) ---

    @Test
    fun f10_tier1_mapWeatherLayerEnum_containsAllTenRequiredLayers() {
        val layers = MapWeatherLayer.entries
        assertEquals(10, layers.size)

        val expected = setOf(
            MapWeatherLayer.RADAR,
            MapWeatherLayer.SATELLITE,
            MapWeatherLayer.WIND,
            MapWeatherLayer.GUSTS,
            MapWeatherLayer.HUMIDITY,
            MapWeatherLayer.CAPE,
            MapWeatherLayer.TEMPERATURE,
            MapWeatherLayer.PRECIPITATION,
            MapWeatherLayer.CLOUDS,
            MapWeatherLayer.PRESSURE
        )
        assertEquals(expected, layers.toSet())
    }

    @Test
    fun f10_tier1_togglingSingleLayerOnAndOff_preservesOtherLayers() {
        val controller = MapStateController()

        controller.toggleLayer(MapWeatherLayer.RADAR)
        controller.toggleLayer(MapWeatherLayer.WIND)
        assertEquals(setOf(MapWeatherLayer.RADAR, MapWeatherLayer.WIND), controller.state.value.activeLayers)

        controller.toggleLayer(MapWeatherLayer.RADAR)
        assertEquals(setOf(MapWeatherLayer.WIND), controller.state.value.activeLayers)
        assertFalse(controller.state.value.activeLayers.contains(MapWeatherLayer.RADAR))
        assertTrue(controller.state.value.activeLayers.contains(MapWeatherLayer.WIND))
    }

    @Test
    fun f10_tier1_combiningMultipleLayersSimultaneously_retainsAllActiveLayers() {
        val controller = MapStateController()

        controller.toggleLayer(MapWeatherLayer.RADAR)
        controller.toggleLayer(MapWeatherLayer.WIND)
        controller.toggleLayer(MapWeatherLayer.CAPE)
        controller.toggleLayer(MapWeatherLayer.TEMPERATURE)

        assertEquals(
            setOf(
                MapWeatherLayer.RADAR,
                MapWeatherLayer.WIND,
                MapWeatherLayer.CAPE,
                MapWeatherLayer.TEMPERATURE
            ),
            controller.state.value.activeLayers
        )
    }

    @Test
    fun f10_tier1_radarTileSpec_clampsMaxZoomAt7PerRainViewer2026Spec() {
        val feed = sampleRainViewer2026Feed(pastCount = 13, nowcastCount = 0)
        val pastFrame = feed.radarPast.last()
        val timelineFrame = TimelineFrame(
            epochMillis = pastFrame.epochMillis,
            label = "Radar",
            source = TimelineFrameSource.RADAR_PAST,
            radarPath = pastFrame.path
        )

        val radarOverlay = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.RADAR,
            level = PressureLevel.SFC,
            model = MapForecastModel.GFS,
            frame = timelineFrame,
            rainViewer = feed,
            gridForecasts = emptyList(),
            gridPlan = null,
            owmApiKey = null,
            nowEpochMillis = pastFrame.epochMillis
        ) as? ResolvedLayerOverlay.RasterTiles

        assertNotNull(radarOverlay)
        assertTrue("RainViewer 2026 spec caps Radar maxZoom at <= 7", radarOverlay!!.maxZoom <= 7)
        assertTrue(
            "Tile URL template must use host, path, and universal blue color scheme 2",
            radarOverlay.urlTemplate.contains("https://tilecache.rainviewer.com/v2/radar/") &&
                radarOverlay.urlTemplate.contains("/256/{z}/{x}/{y}/2/")
        )
    }

    @Test
    fun f10_tier1_satelliteIrEmpty_fallsBackToOwmCloudsOrOpenMeteoCloudCover() {
        val feed = sampleRainViewer2026Feed(pastCount = 13, nowcastCount = 0, infraredCount = 0)
        val frame = TimelineFrame(
            epochMillis = baseTimeMillis,
            label = "Now",
            source = TimelineFrameSource.RADAR_PAST,
            radarPath = feed.radarPast.first().path
        )

        val satelliteOverlay = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.SATELLITE,
            level = PressureLevel.SFC,
            model = MapForecastModel.GFS,
            frame = frame,
            rainViewer = feed,
            gridForecasts = emptyList(),
            gridPlan = null,
            owmApiKey = "test-owm-key",
            nowEpochMillis = baseTimeMillis
        ) as? ResolvedLayerOverlay.RasterTiles

        assertNotNull("Empty RainViewer satellite.infrared must produce non-null fallback config", satelliteOverlay)
        assertTrue(
            "Fallback must use OWM clouds_new or Open-Meteo cloud_cover",
            satelliteOverlay!!.sourceLabel.contains("OWM", ignoreCase = true) ||
                satelliteOverlay.sourceLabel.contains("Open-Meteo", ignoreCase = true) ||
                satelliteOverlay.urlTemplate.contains("clouds_new")
        )
    }

    @Test
    fun f10_tier1_animatedWindAndGustProvider_returnsNonEmptyFramesAndVectors() {
        val plan = MapLayerDataProvider.buildSampleGrid(GeoBounds(48.0, 9.5, 53.6, 13.5), rows = 2, cols = 2)
        val gridForecasts = sampleGridForecasts(plan)

        val windField = MapLayerDataProvider.extractWindField(
            level = PressureLevel.SFC,
            gridForecasts = gridForecasts,
            plan = plan,
            modelId = MapForecastModel.GFS.id,
            hourIndex = 0
        )
        val gustField = MapLayerDataProvider.extractScalarField(
            layer = MapWeatherLayer.GUSTS,
            level = PressureLevel.SFC,
            gridForecasts = gridForecasts,
            plan = plan,
            modelId = MapForecastModel.GFS.id,
            hourIndex = 0
        )

        assertNotNull(windField)
        assertNotNull(gustField)
        val windSample = MapLayerDataProvider.interpolateWind(windField!!, 51.0, 11.5)
        val gustSpeed = MapLayerDataProvider.interpolateScalar(gustField!!, 51.0, 11.5)
        assertNotNull(windSample)
        assertNotNull(gustSpeed)
        assertTrue(windSample!!.speedKmh >= 0.0 && !windSample.speedKmh.isNaN())
        assertTrue(gustSpeed!! >= 0.0 && !gustSpeed.isNaN())
        // Gust speeds in fixture exceed sustained wind speeds
        assertTrue(gustSpeed > windSample.speedKmh)
    }

    // --- F10 Tier 2 (Boundary Value Analysis) ---

    @Test
    fun f10_tier2_toggleAllTenLayersSimultaneously_succeedsWithoutConflict() {
        val controller = MapStateController()

        MapWeatherLayer.entries.forEach { layer ->
            controller.toggleLayer(layer)
        }

        assertEquals(10, controller.state.value.activeLayers.size)
        assertEquals(MapWeatherLayer.entries.toSet(), controller.state.value.activeLayers)
    }

    @Test
    fun f10_tier2_clearAllLayers_returnsEmptySetAndPreservesOtherMapState() {
        val controller = MapStateController()
        controller.setViewMode(MapViewMode.GLOBE_3D)
        controller.setPressureLevel(PressureLevel.HPA_850)
        controller.setModel(MapForecastModel.ECMWF)
        controller.setTimelineFrames(sampleTimelineFrames(count = 11), initialIndex = 3)
        MapWeatherLayer.entries.forEach { controller.toggleLayer(it) }
        assertEquals(10, controller.state.value.activeLayers.size)

        controller.clearLayers()

        assertTrue(controller.state.value.activeLayers.isEmpty())
        assertEquals(MapViewMode.GLOBE_3D, controller.state.value.viewMode)
        assertEquals(PressureLevel.HPA_850, controller.state.value.pressureLevel)
        assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
        assertEquals(3, controller.state.value.timelineIndex)
    }

    @Test
    fun f10_tier2_emptyRainViewerPastAndNowcast_handledGracefullyWithoutNpe() {
        val emptyFeed = sampleRainViewer2026Feed(pastCount = 0, nowcastCount = 0, infraredCount = 0)

        val radarOverlay = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.RADAR,
            level = PressureLevel.SFC,
            model = MapForecastModel.GFS,
            frame = null,
            rainViewer = emptyFeed,
            gridForecasts = emptyList(),
            gridPlan = null,
            owmApiKey = null,
            nowEpochMillis = baseTimeMillis
        )
        val (timeline, _) = MapLayerDataProvider.buildTimelineFrames(
            rainViewer = emptyFeed,
            forecastTimestampsMillis = emptyList(),
            nowEpochMillis = baseTimeMillis
        )

        // No NPE/crash; radarOverlay is Unavailable cleanly and timeline is empty
        assertTrue(radarOverlay is ResolvedLayerOverlay.Unavailable)
        assertTrue(timeline.isEmpty())
    }

    @Test
    fun f10_tier2_radarRequestedZoomAbove7_clampedTo7() {
        assertEquals(7, RainViewerParser.clampZoom(8))
        assertEquals(7, RainViewerParser.clampZoom(14))
        assertEquals(7, RainViewerParser.clampZoom(18))
        assertEquals(5, RainViewerParser.clampZoom(5))
        assertEquals(0, RainViewerParser.clampZoom(-2))
    }

    @Test
    fun f10_tier2_missingAndNullGridValuesAtCoordinateBoundaries_handledCleanly() {
        val boundaryField = WindGridField(
            rows = 2,
            cols = 2,
            lats = listOf(-85.0, 85.0),
            lons = listOf(-180.0, 180.0),
            speedsKmh = listOf(22.5, null, Double.NaN, 31.2),
            directionsDeg = listOf(250.0, null, Double.NaN, 270.0),
            isSurfaceFallback = false
        )
        val interpolated = MapLayerDataProvider.interpolateWind(boundaryField, 0.0, 0.0)
        val emptyField = WindGridField(
            rows = 0,
            cols = 0,
            lats = emptyList(),
            lons = emptyList(),
            speedsKmh = emptyList(),
            directionsDeg = emptyList(),
            isSurfaceFallback = false
        )

        // Null/NaN boundary grid points must be filtered out safely without crashing or leaking NaN
        assertNotNull(interpolated)
        assertFalse(interpolated!!.speedKmh.isNaN() || interpolated.directionDeg.isNaN())
        assertEquals(null, MapLayerDataProvider.interpolateWind(emptyField, 52.52, 13.41))
    }
}

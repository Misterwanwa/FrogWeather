package com.frogweather.phase3

import com.frogweather.map.GeoBounds
import com.frogweather.map.MapForecastModel
import com.frogweather.map.MapLayerDataProvider
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayerDataProviderTest {

    @Test
    fun resolveLayerVariables_distinguishesUpperAirAndSurfaceFallbackLayers() {
        val temp850 = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.TEMPERATURE, PressureLevel.HPA_850)
        assertTrue(temp850.isPressureLevelSupported)
        assertEquals("temperature_850hPa", temp850.primaryVariable)

        val wind500 = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.WIND, PressureLevel.HPA_500)
        assertTrue(wind500.isPressureLevelSupported)
        assertEquals("wind_speed_500hPa", wind500.primaryVariable)
        assertEquals("wind_direction_500hPa", wind500.secondaryVariable)

        val hum250 = MapLayerDataProvider.resolveLayerVariables(MapWeatherLayer.HUMIDITY, PressureLevel.HPA_250)
        assertTrue(hum250.isPressureLevelSupported)
        assertEquals("relative_humidity_250hPa", hum250.primaryVariable)

        val surfaceOnly = listOf(
            MapWeatherLayer.CAPE to "cape",
            MapWeatherLayer.GUSTS to "wind_gusts_10m",
            MapWeatherLayer.PRECIPITATION to "precipitation",
            MapWeatherLayer.CLOUDS to "cloud_cover",
            MapWeatherLayer.PRESSURE to "surface_pressure"
        )
        for ((layer, expectedVar) in surfaceOnly) {
            val spec = MapLayerDataProvider.resolveLayerVariables(layer, PressureLevel.HPA_500)
            assertFalse(spec.isPressureLevelSupported)
            assertEquals(expectedVar, spec.primaryVariable)
        }
    }

    @Test
    fun buildSampleGrid_enforcesMinimumSpanAtStreetLevelAndClampsBounds() {
        val tinyStreetBounds = GeoBounds(south = 52.519, west = 13.404, north = 52.521, east = 13.406)
        val streetPlan = MapLayerDataProvider.buildSampleGrid(tinyStreetBounds, rows = 4, cols = 4, minSpanDeg = 0.6)
        assertEquals(16, streetPlan.coordinates.size)
        assertEquals(0.6, streetPlan.lats.last() - streetPlan.lats.first(), 1e-4)
        assertEquals(0.6, streetPlan.lons.last() - streetPlan.lons.first(), 1e-4)

        val polarBounds = GeoBounds(south = 84.9, west = 179.9, north = 89.0, east = 185.0)
        val clampedPlan = MapLayerDataProvider.buildSampleGrid(polarBounds, rows = 3, cols = 3, minSpanDeg = 0.6)
        assertTrue(clampedPlan.lats.all { it in -85.0..85.0 })
        assertTrue(clampedPlan.lons.all { it in -180.0..180.0 })
    }

    @Test
    fun interpolateScalar_bilinearAndNullCornerIdwFallback() {
        val field = ScalarGridField(
            rows = 2,
            cols = 2,
            lats = listOf(50.0, 52.0),
            lons = listOf(10.0, 12.0),
            values = listOf(10.0, 20.0, 30.0, 40.0),
            unit = "°C",
            isSurfaceFallback = false
        )
        val center = MapLayerDataProvider.interpolateScalar(field, 51.0, 11.0)
        assertNotNull(center)
        assertEquals(25.0, center!!, 1e-6)

        val partialNullField = field.copy(values = listOf(10.0, null, 30.0, 40.0))
        val idwCenter = MapLayerDataProvider.interpolateScalar(partialNullField, 51.0, 11.0)
        assertNotNull(idwCenter)
        assertEquals((10.0 + 30.0 + 40.0) / 3.0, idwCenter!!, 1e-6)
    }

    @Test
    fun interpolateWind_decomposesUVAcrossZeroDegreeWraparound() {
        // 350° and 10° with equal speed 20 km/h must average to ~0° (north), NEVER 180° (south)
        val field = WindGridField(
            rows = 2,
            cols = 2,
            lats = listOf(50.0, 52.0),
            lons = listOf(10.0, 12.0),
            speedsKmh = listOf(20.0, 20.0, 20.0, 20.0),
            directionsDeg = listOf(350.0, 10.0, 350.0, 10.0),
            isSurfaceFallback = false
        )
        val center = MapLayerDataProvider.interpolateWind(field, 51.0, 11.0)
        assertNotNull(center)
        assertTrue(
            "Expected direction near 0°/360°, got ${center!!.directionDeg}",
            center.directionDeg < 2.0 || center.directionDeg > 358.0
        )
        assertTrue("Expected speed ~19.7 km/h, got ${center.speedKmh}", center.speedKmh > 19.0)
    }

    @Test
    fun resolveLayerOverlay_andTimelineMerge_followFallbackMatrix() {
        val plan = MapLayerDataProvider.buildSampleGrid(
            GeoBounds(50.0, 10.0, 52.0, 12.0),
            rows = 2,
            cols = 2
        )
        val now = 1_795_368_000_000L
        val forecastTimestamps = listOf(now, now + 3_600_000L, now + 7_200_000L)
        val gridForecasts = plan.coordinates.map { (lat, lon) ->
            OpenMeteoForecastData(
                latitude = lat,
                longitude = lon,
                utcOffsetSeconds = 0,
                timezone = "GMT",
                timestampsEpochMillis = forecastTimestamps,
                models = mapOf(
                    MapForecastModel.GFS.id to OpenMeteoModelHourly(
                        modelId = MapForecastModel.GFS.id,
                        rawSeries = mapOf(
                            "precipitation" to listOf(0.5, 2.0, 4.0),
                            "cloud_cover" to listOf(30.0, 65.0, 90.0),
                            "cape" to listOf(100.0, 800.0, 1200.0),
                            "temperature_850hPa" to listOf(4.0, 5.0, 6.0)
                        )
                    )
                )
            )
        }

        val rainViewerEmptySat = RainViewerMapsData(
            version = "2.0",
            generatedEpochMillis = now,
            host = "https://tilecache.rainviewer.com",
            radarPast = listOf(RainViewerFrame(now - 600_000L, "/v2/radar/p1", false)),
            radarNowcast = emptyList(),
            satelliteInfrared = emptyList()
        )

        val (frames, defaultIdx) = MapLayerDataProvider.buildTimelineFrames(
            rainViewer = rainViewerEmptySat,
            forecastTimestampsMillis = forecastTimestamps,
            nowEpochMillis = now
        )
        assertTrue(frames.size >= 3)
        assertEquals(0, defaultIdx)

        // 1. Radar on past frame -> RainViewer maxZoom = 7
        val radarPastOverlay = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.RADAR,
            level = PressureLevel.SFC,
            model = MapForecastModel.GFS,
            frame = frames.first(),
            rainViewer = rainViewerEmptySat,
            gridForecasts = gridForecasts,
            gridPlan = plan,
            owmApiKey = "YOUR_API_KEY_HERE",
            nowEpochMillis = now
        )
        assertTrue(radarPastOverlay is ResolvedLayerOverlay.RasterTiles)
        assertEquals(7, (radarPastOverlay as ResolvedLayerOverlay.RasterTiles).maxZoom)

        // 2. Radar on future forecast frame -> Open-Meteo precipitation grid
        val futureFrame = TimelineFrame(
            epochMillis = now + 7_200_000L,
            label = "Forecast",
            source = TimelineFrameSource.FORECAST_MODEL,
            radarPath = null,
            forecastHourIndex = 2
        )
        val radarFutureOverlay = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.RADAR,
            level = PressureLevel.SFC,
            model = MapForecastModel.GFS,
            frame = futureFrame,
            rainViewer = rainViewerEmptySat,
            gridForecasts = gridForecasts,
            gridPlan = plan,
            owmApiKey = "valid_key_123",
            nowEpochMillis = now
        )
        assertTrue(radarFutureOverlay is ResolvedLayerOverlay.ScalarGrid)

        // 3. Satellite with empty RainViewer IR & valid OWM key near-now -> OWM clouds_new
        val satOwmOverlay = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.SATELLITE,
            level = PressureLevel.SFC,
            model = MapForecastModel.GFS,
            frame = frames.first(),
            rainViewer = rainViewerEmptySat,
            gridForecasts = gridForecasts,
            gridPlan = plan,
            owmApiKey = "valid_key_123",
            nowEpochMillis = now
        )
        assertTrue(satOwmOverlay is ResolvedLayerOverlay.RasterTiles)
        assertTrue((satOwmOverlay as ResolvedLayerOverlay.RasterTiles).urlTemplate.contains("clouds_new"))

        // 4. Satellite with placeholder OWM key -> Open-Meteo cloud_cover grid (no 401 URL)
        val satPlaceholderKeyOverlay = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.SATELLITE,
            level = PressureLevel.SFC,
            model = MapForecastModel.GFS,
            frame = frames.first(),
            rainViewer = rainViewerEmptySat,
            gridForecasts = gridForecasts,
            gridPlan = plan,
            owmApiKey = "YOUR_API_KEY_HERE",
            nowEpochMillis = now
        )
        assertTrue(satPlaceholderKeyOverlay is ResolvedLayerOverlay.ScalarGrid)

        // 5. CAPE at 500 hPa flags surface fallback; Temperature at 850 hPa does not
        val cape500 = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.CAPE,
            level = PressureLevel.HPA_500,
            model = MapForecastModel.GFS,
            frame = futureFrame,
            rainViewer = rainViewerEmptySat,
            gridForecasts = gridForecasts,
            gridPlan = plan,
            owmApiKey = null,
            nowEpochMillis = now
        ) as ResolvedLayerOverlay.ScalarGrid
        assertTrue(cape500.field.isSurfaceFallback)

        val temp850 = MapLayerDataProvider.resolveLayerOverlay(
            layer = MapWeatherLayer.TEMPERATURE,
            level = PressureLevel.HPA_850,
            model = MapForecastModel.GFS,
            frame = futureFrame,
            rainViewer = rainViewerEmptySat,
            gridForecasts = gridForecasts,
            gridPlan = plan,
            owmApiKey = null,
            nowEpochMillis = now
        ) as ResolvedLayerOverlay.ScalarGrid
        assertFalse(temp850.field.isSurfaceFallback)
    }

    @Test
    fun buildSampleGrid_sanitizesNonFiniteBoundsAndHandlesAntimeridian() {
        val nanBounds = GeoBounds(
            south = Double.NaN,
            west = Double.NEGATIVE_INFINITY,
            north = 52.0,
            east = Double.POSITIVE_INFINITY
        )
        val nanPlan = MapLayerDataProvider.buildSampleGrid(nanBounds, rows = 4, cols = 4)
        assertEquals(16, nanPlan.coordinates.size)
        assertTrue(nanPlan.lats.all { it.isFinite() && it in -85.0..85.0 })
        assertTrue(nanPlan.lons.all { it.isFinite() && it in -180.0..180.0 })

        val allInfBounds = GeoBounds(
            south = Double.POSITIVE_INFINITY,
            west = Double.NaN,
            north = Double.NaN,
            east = Double.NEGATIVE_INFINITY
        )
        val infPlan = MapLayerDataProvider.buildSampleGrid(allInfBounds, rows = 3, cols = 3, minSpanDeg = Double.NaN)
        assertTrue(infPlan.lats.all { it.isFinite() && it in -85.0..85.0 })
        assertTrue(infPlan.lons.all { it.isFinite() && it in -180.0..180.0 })

        val antimeridianPlan = MapLayerDataProvider.buildSampleGrid(
            GeoBounds(south = -5.0, west = 179.0, north = 5.0, east = -179.0),
            rows = 4,
            cols = 4
        )
        assertTrue(antimeridianPlan.lons.all { it.isFinite() && it in -180.0..180.0 })
        assertEquals(179.0, antimeridianPlan.lons.first(), 1e-4)
        assertEquals(-179.0, antimeridianPlan.lons.last(), 1e-4)
        assertTrue(antimeridianPlan.lons[1] > 179.0)
        assertTrue(antimeridianPlan.lons[2] < -179.0)
    }

    @Test
    fun interpolateScalarAndWind_returnNullOnNonFiniteCoordinates() {
        val scalarField = ScalarGridField(
            rows = 2,
            cols = 2,
            lats = listOf(50.0, 52.0),
            lons = listOf(10.0, 12.0),
            values = listOf(10.0, 20.0, 30.0, 40.0),
            unit = "°C",
            isSurfaceFallback = false
        )
        assertNull(MapLayerDataProvider.interpolateScalar(scalarField, Double.NaN, 11.0))
        assertNull(MapLayerDataProvider.interpolateScalar(scalarField, 51.0, Double.POSITIVE_INFINITY))
        assertNull(MapLayerDataProvider.interpolateScalar(scalarField, Double.NEGATIVE_INFINITY, Double.NaN))

        val windField = WindGridField(
            rows = 2,
            cols = 2,
            lats = listOf(50.0, 52.0),
            lons = listOf(10.0, 12.0),
            speedsKmh = listOf(20.0, 20.0, 20.0, 20.0),
            directionsDeg = listOf(350.0, 10.0, 350.0, 10.0),
            isSurfaceFallback = false
        )
        assertNull(MapLayerDataProvider.interpolateWind(windField, Double.NaN, 11.0))
        assertNull(MapLayerDataProvider.interpolateWind(windField, 51.0, Double.NaN))
        assertNull(MapLayerDataProvider.interpolateWind(windField, 51.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun interpolateScalar_handlesDescendingAxesAccuratelyWithoutException() {
        val descLatField = ScalarGridField(
            rows = 2,
            cols = 2,
            lats = listOf(52.0, 50.0),
            lons = listOf(12.0, 10.0),
            values = listOf(30.0, 40.0, 10.0, 20.0),
            unit = "°C",
            isSurfaceFallback = false
        )
        val mid = MapLayerDataProvider.interpolateScalar(descLatField, 51.0, 11.0)
        assertNotNull(mid)
        assertEquals(25.0, mid!!, 1e-6)

        val topLeft = MapLayerDataProvider.interpolateScalar(descLatField, 52.0, 12.0)
        assertNotNull(topLeft)
        assertEquals(30.0, topLeft!!, 1e-6)

        val bottomRight = MapLayerDataProvider.interpolateScalar(descLatField, 50.0, 10.0)
        assertNotNull(bottomRight)
        assertEquals(20.0, bottomRight!!, 1e-6)

        val weighted = MapLayerDataProvider.interpolateScalar(descLatField, 51.5, 11.5)
        assertNotNull(weighted)
        // lat=51.5 is 75% toward 52.0 (25% toward 50.0); lon=11.5 is 75% toward 12.0 (25% toward 10.0)
        // top(52): 0.75*30 + 0.25*40 = 32.5; bottom(50): 0.75*10 + 0.25*20 = 12.5
        // interp: 0.75*32.5 + 0.25*12.5 = 27.5
        assertEquals(27.5, weighted!!, 1e-6)
    }
}

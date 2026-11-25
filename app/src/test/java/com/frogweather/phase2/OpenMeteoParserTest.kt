package com.frogweather.phase2

import com.frogweather.analysis.ForecastConfidenceEngine
import com.frogweather.network.OpenMeteoModelIds
import com.frogweather.network.OpenMeteoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoParserTest {

    @Test
    fun `parses Tokyo payload with completely omitted icon_d2 and null ecmwf uv_index`() {
        val tokyoJson = """
            {
              "latitude": 35.68,
              "longitude": 139.77,
              "utc_offset_seconds": 32400,
              "timezone": "Asia/Tokyo",
              "hourly": {
                "time": [1795042800, 1795046400],
                "temperature_2m_best_match": [18.5, 19.2],
                "temperature_2m_ecmwf_ifs025": [18.3, 19.0],
                "temperature_2m_gfs_global": [18.7, 19.4],
                "precipitation_best_match": [0.0, 0.1],
                "precipitation_ecmwf_ifs025": [0.0, 0.0],
                "precipitation_gfs_global": [0.0, 0.2],
                "precipitation_probability_best_match": [5, 15],
                "wind_speed_10m_best_match": [9.5, 11.0],
                "wind_speed_10m_ecmwf_ifs025": [9.0, 10.5],
                "wind_speed_10m_gfs_global": [10.0, 11.5],
                "uv_index_best_match": [null, 3.7],
                "uv_index_ecmwf_ifs025": [null, null],
                "uv_index_gfs_global": [1.2, 3.9],
                "weather_code_best_match": [1, 2]
              }
            }
        """.trimIndent()

        val parsed = OpenMeteoParser.parseForecastJson(
            json = tokyoJson,
            requestedModels = OpenMeteoModelIds.ALL_FORECAST_MODELS
        )

        assertNull(parsed.models[OpenMeteoModelIds.ICON_D2])
        assertEquals(2, parsed.timestampsEpochMillis.size)

        val points = parsed.toHourlyWeatherPoints()
        assertEquals(2, points.size)
        // Hour 0: best_match uv_index is null -> falls back to gfs_global (1.2)
        assertEquals(1.2, points[0].uvIndex, 1e-6)
        // Hour 1: best_match uv_index is 3.7
        assertEquals(3.7, points[1].uvIndex, 1e-6)

        val confidence = ForecastConfidenceEngine.computeConfidence(
            parsed.comparisonModelTemps(),
            parsed.comparisonModelPrecips(),
            parsed.comparisonModelWinds()
        )
        assertTrue(confidence.hasEnoughModels)
        assertTrue(OpenMeteoModelIds.ICON_D2 in confidence.excludedModels)
        assertFalse(OpenMeteoModelIds.ICON_D2 in confidence.activeModels)
    }

    @Test
    fun `parses Berlin payload with partial trailing nulls in icon_d2`() {
        val berlinJson = """
            {
              "latitude": 52.52,
              "longitude": 13.41,
              "utc_offset_seconds": 3600,
              "timezone": "Europe/Berlin",
              "hourly": {
                "time": [1795042800, 1795046400, 1795050000],
                "temperature_2m_best_match": [7.0, 7.5, 8.0],
                "temperature_2m_ecmwf_ifs025": [6.8, 7.4, 7.9],
                "temperature_2m_gfs_global": [7.2, 7.7, 8.2],
                "temperature_2m_icon_d2": [6.9, 7.5, null],
                "precipitation_best_match": [0.0, 0.0, 0.0],
                "precipitation_ecmwf_ifs025": [0.0, 0.0, 0.0],
                "precipitation_gfs_global": [0.0, 0.0, 0.0],
                "precipitation_icon_d2": [0.0, 0.0, null],
                "wind_speed_10m_best_match": [14.0, 15.0, 16.0],
                "wind_speed_10m_ecmwf_ifs025": [13.5, 14.5, 15.5],
                "wind_speed_10m_gfs_global": [14.2, 15.1, 16.2],
                "wind_speed_10m_icon_d2": [14.0, 15.0, null]
              }
            }
        """.trimIndent()

        val parsed = OpenMeteoParser.parseForecastJson(
            json = berlinJson,
            requestedModels = OpenMeteoModelIds.ALL_FORECAST_MODELS
        )
        val iconD2 = parsed.models[OpenMeteoModelIds.ICON_D2]
        assertTrue(iconD2 != null && iconD2.hasAnyFiniteData())
        assertEquals(6.9, iconD2!!.temperature2m[0]!!, 1e-6)
        assertNull(iconD2.temperature2m[2])

        val confidence = ForecastConfidenceEngine.computeConfidence(
            parsed.comparisonModelTemps(),
            parsed.comparisonModelPrecips(),
            parsed.comparisonModelWinds()
        )
        assertTrue(OpenMeteoModelIds.ICON_D2 in confidence.activeModels)
        assertEquals(3, confidence.hourlyConfidences.size)
        assertTrue(confidence.percent >= 75)
    }

    @Test
    fun `parses single-model unsuffixed M3 upper-air payload and strips archive trailing nulls`() {
        val singleModelJson = """
            {
              "latitude": 52.52,
              "longitude": 13.41,
              "utc_offset_seconds": 3600,
              "hourly": {
                "time": [1795042800, 1795046400],
                "temperature_2m": [8.0, 9.0],
                "temperature_850hPa": [-2.5, -2.1]
              }
            }
        """.trimIndent()

        val singleParsed = OpenMeteoParser.parseForecastJson(
            json = singleModelJson,
            requestedModels = listOf(OpenMeteoModelIds.GFS)
        )
        val gfs = singleParsed.models[OpenMeteoModelIds.GFS]!!
        assertEquals(listOf(8.0, 9.0), gfs.temperature2m)
        assertEquals(listOf(-2.5, -2.1), gfs.getSeries("temperature_850hPa"))

        val archiveJson = """
            {
              "latitude": 52.52,
              "longitude": 13.41,
              "utc_offset_seconds": 3600,
              "daily": {
                "time": ["2026-11-16", "2026-11-17", "2026-11-18", "2026-11-19"],
                "temperature_2m_min": [2.0, 3.0, null, null],
                "temperature_2m_max": [9.0, 10.0, null, null],
                "temperature_2m_mean": [5.5, 6.5, null, null]
              }
            }
        """.trimIndent()

        val archive = OpenMeteoParser.parseArchiveJson(archiveJson)
        assertEquals(listOf("2026-11-16", "2026-11-17"), archive.dates)
        assertEquals(2, archive.dailyMinC.size)
    }

    @Test
    fun `parses fractional-second local ISO timestamps and preserves alignment when forecast times are malformed or null`() {
        val fractionalIsoJson = """
            {
              "latitude": 52.52,
              "longitude": 13.40,
              "utc_offset_seconds": 0,
              "hourly": {
                "time": ["2026-11-22T12:00", "2026-11-22T13:00:00.000", "2026-11-22T14:00:00Z"],
                "temperature_2m": [10.0, 20.0, 30.0]
              }
            }
        """.trimIndent()

        val parsedIso = OpenMeteoParser.parseForecastJson(fractionalIsoJson)
        assertEquals(3, parsedIso.timestampsEpochMillis.size)
        val isoPoints = parsedIso.toHourlyWeatherPoints()
        assertEquals(listOf(10.0, 20.0, 30.0), isoPoints.map { it.tempC })
        assertEquals(isoPoints[0].epochMillis + 3_600_000L, isoPoints[1].epochMillis)
        assertEquals(isoPoints[1].epochMillis + 3_600_000L, isoPoints[2].epochMillis)

        val misalignedForecastJson = """
            {
              "latitude": 52.52,
              "longitude": 13.40,
              "utc_offset_seconds": 0,
              "hourly": {
                "time": ["2026-11-22T12:00", null, "not-a-timestamp", "2026-11-22T15:00"],
                "temperature_2m": [10.0, 20.0, 25.0, 30.0],
                "precipitation": [0.0, 1.5, 2.5, 0.2]
              }
            }
        """.trimIndent()

        val parsedAligned = OpenMeteoParser.parseForecastJson(misalignedForecastJson)
        assertEquals(2, parsedAligned.timestampsEpochMillis.size)
        val alignedPoints = parsedAligned.toHourlyWeatherPoints()
        assertEquals(2, alignedPoints.size)
        assertEquals(10.0, alignedPoints[0].tempC, 1e-6)
        assertEquals(30.0, alignedPoints[1].tempC, 1e-6)
        assertEquals(0.2, alignedPoints[1].precipMm, 1e-6)
        assertEquals(alignedPoints[0].epochMillis + 3 * 3_600_000L, alignedPoints[1].epochMillis)
    }

    @Test
    fun `preserves daily index alignment when archive time contains null or blank entries`() {
        val archiveWithGapsJson = """
            {
              "latitude": 52.52,
              "longitude": 13.40,
              "utc_offset_seconds": 0,
              "daily": {
                "time": ["2026-11-20", null, "   ", "2026-11-23", "2026-11-24"],
                "temperature_2m_min": [1.0, 2.0, 3.0, 4.0, null],
                "temperature_2m_max": [5.0, 6.0, 7.0, 8.0, null],
                "temperature_2m_mean": [3.0, 4.0, 5.0, 6.0, null]
              }
            }
        """.trimIndent()

        val archive = OpenMeteoParser.parseArchiveJson(archiveWithGapsJson)
        assertEquals(listOf("2026-11-20", "2026-11-23"), archive.dates)
        assertEquals(listOf(1.0, 4.0), archive.dailyMinC)
        assertEquals(listOf(5.0, 8.0), archive.dailyMaxC)
        assertEquals(listOf(3.0, 6.0), archive.dailyMeanC)
    }

    @Test
    fun `preserves null gaps in forecast parallel series during timestamp realignment`() {
        val forecastWithNullGapsJson = """
            {
              "latitude": 52.52,
              "longitude": 13.40,
              "utc_offset_seconds": 0,
              "hourly": {
                "time": ["2026-11-22T12:00", "2026-11-22T13:00", "2026-11-22T14:00", "bad-date"],
                "temperature_2m_best_match": [10.0, 20.0, 30.0, 40.0],
                "temperature_2m_ecmwf_ifs025": [10.0, null, 30.0, 40.0],
                "temperature_2m_gfs_global": [10.0, 20.0, 30.0, 40.0],
                "uv_index_best_match": [1.0, null, 7.0, 9.0],
                "uv_index_gfs_global": [1.0, null, 2.0, 3.0],
                "wind_speed_850hPa_gfs_global": [20.0, null, 60.0, 80.0]
              }
            }
        """.trimIndent()

        val parsed = OpenMeteoParser.parseForecastJson(
            json = forecastWithNullGapsJson,
            requestedModels = listOf(
                OpenMeteoModelIds.BEST_MATCH,
                OpenMeteoModelIds.ECMWF,
                OpenMeteoModelIds.GFS
            )
        )

        assertEquals(3, parsed.timestampsEpochMillis.size)
        assertEquals(listOf(10.0, null, 30.0), parsed.models[OpenMeteoModelIds.ECMWF]!!.temperature2m)
        assertEquals(listOf(1.0, null, 7.0), parsed.models[OpenMeteoModelIds.BEST_MATCH]!!.uvIndex)
        assertEquals(listOf(1.0, null, 2.0), parsed.models[OpenMeteoModelIds.GFS]!!.uvIndex)
        assertEquals(
            listOf(20.0, null, 60.0),
            parsed.models[OpenMeteoModelIds.GFS]!!.getSeries("wind_speed_850hPa")
        )

        val hourlyPoints = parsed.toHourlyWeatherPoints()
        assertEquals(3, hourlyPoints.size)
        assertEquals(1.0, hourlyPoints[0].uvIndex, 1e-6)
        assertEquals(0.0, hourlyPoints[1].uvIndex, 1e-6)
        assertEquals(7.0, hourlyPoints[2].uvIndex, 1e-6)

        val comparisonTemps = parsed.comparisonModelTemps(listOf(OpenMeteoModelIds.ECMWF, OpenMeteoModelIds.GFS))
        assertEquals(listOf(10.0, null, 30.0), comparisonTemps[OpenMeteoModelIds.ECMWF])
    }

    @Test
    fun `preserves null gaps in archive hourly temperatures during timestamp realignment`() {
        val archiveWithHourlyNullGapJson = """
            {
              "latitude": 52.52,
              "longitude": 13.40,
              "utc_offset_seconds": 0,
              "hourly": {
                "time": ["2026-11-22T12:00", "2026-11-22T13:00", "2026-11-22T14:00", null],
                "temperature_2m": [10.0, null, 30.0, 40.0]
              }
            }
        """.trimIndent()

        val archive = OpenMeteoParser.parseArchiveJson(archiveWithHourlyNullGapJson)
        assertEquals(3, archive.hourlyEpochMillis.size)
        assertEquals(listOf(10.0, null, 30.0), archive.hourlyTempC)
    }
}

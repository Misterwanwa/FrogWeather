package com.frogweather.phase3

import com.frogweather.network.OpenMeteoModelIds
import com.frogweather.network.OpenMeteoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenMeteoM3UpperAirTest {

    @Test
    fun parseForecastJson_gfsAndEcmwfUpperAirVariables_populatesRawSeriesWithNullGaps() {
        val json = """
        {
          "latitude": 52.52,
          "longitude": 13.41,
          "utc_offset_seconds": 3600,
          "timezone": "Europe/Berlin",
          "hourly": {
            "time": [1795366800, 1795370400, 1795374000],
            "temperature_2m": [8.5, 9.0, 8.2],
            "temperature_850hPa": [1.2, null, 0.8],
            "wind_speed_500hPa": [42.0, 48.5, 51.0],
            "wind_direction_500hPa": [265.0, 270.0, 275.0],
            "relative_humidity_250hPa": [55.0, 62.0, 60.0],
            "wind_gusts_10m": [28.0, 32.0, 35.0],
            "cape": [120.0, 450.0, 300.0]
          }
        }
        """.trimIndent()

        val parsed = OpenMeteoParser.parseForecastJson(
            json = json,
            requestedModels = listOf(OpenMeteoModelIds.ECMWF)
        )
        val ecmwf = parsed.models[OpenMeteoModelIds.ECMWF]
        assertNotNull(ecmwf)
        assertEquals(listOf(1.2, null, 0.8), ecmwf!!.getSeries("temperature_850hPa"))
        assertNull(ecmwf.getSeries("temperature_850hPa")!![1])
        assertEquals(listOf(42.0, 48.5, 51.0), ecmwf.getSeries("wind_speed_500hPa"))
        assertEquals(listOf(265.0, 270.0, 275.0), ecmwf.getSeries("wind_direction_500hPa"))
        assertEquals(listOf(55.0, 62.0, 60.0), ecmwf.getSeries("relative_humidity_250hPa"))
        assertEquals(listOf(120.0, 450.0, 300.0), ecmwf.getSeries("cape"))
    }

    @Test
    fun parseForecastBatchJson_multiCoordinateArray_parsesAllGridPoints() {
        val batchJson = """
        [
          {
            "latitude": 51.0,
            "longitude": 11.0,
            "utc_offset_seconds": 0,
            "timezone": "GMT",
            "hourly": {
              "time": [1795366800, 1795370400],
              "temperature_850hPa": [2.0, 2.5],
              "cloud_cover": [40.0, 50.0],
              "surface_pressure": [1012.0, 1011.5]
            }
          },
          {
            "latitude": 53.0,
            "longitude": 14.0,
            "utc_offset_seconds": 0,
            "timezone": "GMT",
            "hourly": {
              "time": [1795366800, 1795370400],
              "temperature_850hPa": [-1.0, -0.5],
              "cloud_cover": [80.0, 85.0],
              "surface_pressure": [1006.0, 1005.0]
            }
          }
        ]
        """.trimIndent()

        val grid = OpenMeteoParser.parseForecastBatchJson(
            json = batchJson,
            requestedModels = listOf(OpenMeteoModelIds.GFS)
        )
        assertEquals(2, grid.size)
        assertEquals(51.0, grid[0].latitude, 1e-4)
        assertEquals(53.0, grid[1].latitude, 1e-4)
        assertEquals(2.0, grid[0].models[OpenMeteoModelIds.GFS]!!.getSeries("temperature_850hPa")!![0]!!, 1e-4)
        assertEquals(-1.0, grid[1].models[OpenMeteoModelIds.GFS]!!.getSeries("temperature_850hPa")!![0]!!, 1e-4)
    }

    @Test
    fun parseForecastBatchJson_singleObject_wrapsInSingletonList() {
        val singleJson = """
        {
          "latitude": 52.52,
          "longitude": 13.41,
          "utc_offset_seconds": 0,
          "timezone": "GMT",
          "hourly": {
            "time": [1795366800],
            "precipitation": [1.4]
          }
        }
        """.trimIndent()

        val result = OpenMeteoParser.parseForecastBatchJson(
            json = singleJson,
            requestedModels = listOf(OpenMeteoModelIds.GFS)
        )
        assertEquals(1, result.size)
        assertEquals(1.4, result[0].models[OpenMeteoModelIds.GFS]!!.getSeries("precipitation")!![0]!!, 1e-4)
    }

    @Test
    fun parseForecastBatchJson_filtersNullElementsWithoutNpe() {
        val allNullResult = OpenMeteoParser.parseForecastBatchJson(
            json = "[null]",
            requestedModels = listOf(OpenMeteoModelIds.GFS)
        )
        assertEquals(0, allNullResult.size)

        val mixedJson = """
        [
          null,
          {
            "latitude": 52.52,
            "longitude": 13.41,
            "utc_offset_seconds": 0,
            "timezone": "GMT",
            "hourly": {
              "time": [1795366800],
              "temperature_2m": [11.5]
            }
          },
          null
        ]
        """.trimIndent()
        val mixedResult = OpenMeteoParser.parseForecastBatchJson(
            json = mixedJson,
            requestedModels = listOf(OpenMeteoModelIds.GFS)
        )
        assertEquals(1, mixedResult.size)
        assertEquals(52.52, mixedResult[0].latitude, 1e-4)
        assertEquals(11.5, mixedResult[0].models[OpenMeteoModelIds.GFS]!!.temperature2m[0]!!, 1e-4)
    }
}

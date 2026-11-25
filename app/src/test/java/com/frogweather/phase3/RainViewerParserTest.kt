package com.frogweather.phase3

import com.frogweather.network.RainViewerParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainViewerParserTest {

    @Test
    fun parseJson_2026ShapeWithEmptyNowcastAndInfrared_parsesPastAndSortsChronologically() {
        val json = """
        {
          "version": "2.0",
          "generated": 1795368323,
          "host": "https://tilecache.rainviewer.com/",
          "radar": {
            "past": [
              { "time": 1795367400, "path": "/v2/radar/frame2" },
              { "time": 1795366800, "path": "/v2/radar/frame1" }
            ],
            "nowcast": []
          },
          "satellite": {
            "infrared": []
          }
        }
        """.trimIndent()

        val data = RainViewerParser.parseJson(json)
        assertEquals("https://tilecache.rainviewer.com", data.host)
        assertEquals(2, data.radarPast.size)
        assertTrue(data.radarNowcast.isEmpty())
        assertTrue(data.satelliteInfrared.isEmpty())
        assertFalse(data.hasSatelliteInfrared)
        assertEquals(1795366800_000L, data.radarPast[0].epochMillis)
        assertEquals("/v2/radar/frame1", data.radarPast[0].path)
        assertFalse(data.radarPast[0].isNowcast)
        assertEquals(1795367400_000L, data.radarPast[1].epochMillis)
    }

    @Test
    fun parseJson_populatedNowcastAndInfrared_mergesAndExposesSatellite() {
        val json = """
        {
          "version": "2.0",
          "generated": 1795368323,
          "host": "https://tilecache.rainviewer.com",
          "radar": {
            "past": [
              { "time": 1795367400, "path": "/v2/radar/past1" }
            ],
            "nowcast": [
              { "time": 1795368000, "path": "/v2/radar/nowcast1" }
            ]
          },
          "satellite": {
            "infrared": [
              { "time": 1795367400, "path": "/v2/satellite/ir1" }
            ]
          }
        }
        """.trimIndent()

        val data = RainViewerParser.parseJson(json)
        assertTrue(data.hasSatelliteInfrared)
        assertEquals(2, data.allRadarFrames.size)
        assertFalse(data.allRadarFrames[0].isNowcast)
        assertTrue(data.allRadarFrames[1].isNowcast)
        assertEquals("/v2/satellite/ir1", data.satelliteInfrared.single().path)
    }

    @Test
    fun buildTileUrlTemplate_andClampZoom_enforceFormatAndMaxZoom7() {
        val url = RainViewerParser.buildTileUrlTemplate(
            host = "https://tilecache.rainviewer.com/",
            path = "v2/radar/12bb08bab40c/"
        )
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/12bb08bab40c/256/{z}/{x}/{y}/2/1_1.png",
            url
        )

        assertEquals(0, RainViewerParser.clampZoom(-3))
        assertEquals(5, RainViewerParser.clampZoom(5))
        assertEquals(7, RainViewerParser.clampZoom(7))
        assertEquals(7, RainViewerParser.clampZoom(16))
        assertEquals(7, RainViewerParser.MAX_ZOOM)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseJson_blankInput_throwsIllegalArgumentException() {
        RainViewerParser.parseJson("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseJson_missingHost_throwsIllegalArgumentException() {
        RainViewerParser.parseJson("""{"version":"2.0","generated":123,"host":""}""")
    }
}

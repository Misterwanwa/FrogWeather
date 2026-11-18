package com.frogweather

import com.frogweather.service.DwdWarningFetcher
import com.frogweather.service.WarningLatLon
import com.frogweather.service.WarningSeverity
import com.frogweather.service.WarningSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningMapParserTest {

    @Test
    fun regionDetection_identifiesUsAndDwdRegions() {
        // Germany (Berlin, Munich, Kiel)
        assertTrue(DwdWarningFetcher.isDwdRegion(52.52, 13.405))
        assertTrue(DwdWarningFetcher.isDwdRegion(48.137, 11.576))
        assertFalse(DwdWarningFetcher.isUsRegion(52.52, 13.405))

        // USA (San Francisco, Oklahoma City, New York, Anchorage, Honolulu)
        assertTrue(DwdWarningFetcher.isUsRegion(37.7749, -122.4194))
        assertTrue(DwdWarningFetcher.isUsRegion(35.4676, -97.5164))
        assertTrue(DwdWarningFetcher.isUsRegion(40.7128, -74.0060))
        assertTrue(DwdWarningFetcher.isUsRegion(61.2181, -149.9003))
        assertTrue(DwdWarningFetcher.isUsRegion(21.3069, -157.8583))
        assertFalse(DwdWarningFetcher.isDwdRegion(37.7749, -122.4194))

        // Outside both (Tokyo)
        assertFalse(DwdWarningFetcher.isUsRegion(35.6762, 139.6503))
        assertFalse(DwdWarningFetcher.isDwdRegion(35.6762, 139.6503))
    }

    @Test
    fun resolveUsStateCode_mapsMajorUsCitiesToCorrectState() {
        assertEquals("CA", DwdWarningFetcher.resolveUsStateCode(37.7749, -122.4194))
        assertEquals("OK", DwdWarningFetcher.resolveUsStateCode(35.4676, -97.5164))
        assertEquals("TX", DwdWarningFetcher.resolveUsStateCode(30.2672, -97.7431))
        assertEquals("FL", DwdWarningFetcher.resolveUsStateCode(25.7617, -80.1918))
        assertEquals("AK", DwdWarningFetcher.resolveUsStateCode(61.2181, -149.9003))
        assertEquals("HI", DwdWarningFetcher.resolveUsStateCode(21.3069, -157.8583))
        assertNull(DwdWarningFetcher.resolveUsStateCode(52.52, 13.405))
    }

    @Test
    fun pointInPolygon_detectsInsideAndOutsideCorrectly() {
        val square = listOf(
            WarningLatLon(50.0, 10.0),
            WarningLatLon(50.0, 11.0),
            WarningLatLon(51.0, 11.0),
            WarningLatLon(51.0, 10.0),
            WarningLatLon(50.0, 10.0)
        )
        assertTrue(DwdWarningFetcher.isPointInPolygon(50.5, 10.5, square))
        assertFalse(DwdWarningFetcher.isPointInPolygon(49.5, 10.5, square))
        assertFalse(DwdWarningFetcher.isPointInPolygon(50.5, 11.5, square))
    }

    @Test
    fun simplifyPolygonRing_decimatesDenseRingsUnderLimit() {
        val denseRing = (0 until 500).map { idx ->
            WarningLatLon(50.0 + idx * 0.005, 10.0 + (idx % 10) * 0.005)
        }
        val simplified = DwdWarningFetcher.simplifyPolygonRing(denseRing, maxPoints = 80, minDistanceDeg = 0.001)
        assertTrue("Expected <= 80 points, got ${simplified.size}", simplified.size <= 80)
        assertTrue("Expected >= 3 points", simplified.size >= 3)
        assertEquals(denseRing.first(), simplified.first())
        assertEquals(denseRing.last(), simplified.last())
    }

    @Test
    fun parseDwdWfsGeoJson_extractsMultiPolygonAndMergesDuplicateAreaWarnings() {
        val sampleDwdGeoJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {
                "type": "MultiPolygon",
                "coordinates": [
                  [[[10.0, 54.0], [10.5, 54.0], [10.5, 54.3], [10.0, 54.3], [10.0, 54.0]]],
                  [[[10.6, 54.1], [10.9, 54.1], [10.9, 54.4], [10.6, 54.4], [10.6, 54.1]]]
                ]
              },
              "properties": {
                "IDENTIFIER": "dwd-1",
                "EVENT": "STURMBÖEN",
                "SEVERITY": "Moderate",
                "EC_AREA_COLOR": "251 140 0",
                "ONSET": "2026-11-18T12:00:00Z",
                "EXPIRES": "2026-11-18T23:00:00Z",
                "HEADLINE": "Amtliche WARNUNG vor STURMBÖEN",
                "DESCRIPTION": "Es treten Sturmböen auf.",
                "INSTRUCTION": "Lose Gegenstände sichern.",
                "AREADESC": "Kreis Ostholstein"
              }
            }
          ]
        }
        """.trimIndent()

        val warnings = DwdWarningFetcher.parseDwdWfsGeoJson(sampleDwdGeoJson)
        assertEquals(1, warnings.size)
        val w = warnings[0]
        assertEquals("STURMBÖEN", w.title)
        assertEquals(WarningSource.DWD, w.source)
        assertEquals(WarningSeverity.MODERATE, w.severity)
        assertEquals("Kreis Ostholstein", w.area)
        assertEquals(2, w.polygons.size)
        assertEquals(54.0, w.polygons[0][0].lat, 0.0001)
        assertEquals(10.0, w.polygons[0][0].lon, 0.0001)
        assertEquals(0xFFFB8C00L, w.customColorRgb)
        assertNotNull(w.onsetInstant)
        assertNotNull(w.expiresInstant)
    }

    @Test
    fun parseBrightSkyJson_extractsAlertsCorrectly() {
        val sampleBrightSky = """
        {
          "alerts": [
            {
              "id": 3058630,
              "alert_id": "2.49.0.0.276.0.DWD.PVW.1",
              "onset": "2026-11-18T12:00:00+00:00",
              "expires": "2026-11-18T23:00:00+00:00",
              "severity": "moderate",
              "event_de": "STURMBÖEN",
              "headline_de": "Amtliche WARNUNG vor STURMBÖEN",
              "description_de": "Sturmböen bis 70 km/h",
              "instruction_de": "Vorsicht im Freien"
            }
          ],
          "location": {
            "name": "Gemeinde Schönwalde"
          }
        }
        """.trimIndent()

        val warnings = DwdWarningFetcher.parseBrightSkyJson(sampleBrightSky)
        assertEquals(1, warnings.size)
        assertEquals("STURMBÖEN", warnings[0].title)
        assertEquals("Gemeinde Schönwalde", warnings[0].area)
        assertEquals(WarningSeverity.MODERATE, warnings[0].severity)
        assertEquals(WarningSource.DWD, warnings[0].source)
        assertNotNull(warnings[0].onsetInstant)
    }

    @Test
    fun parseNwsGeoJson_extractsDirectPolygonAndOffsetTimestamps() {
        val sampleNwsGeoJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {
                "type": "Polygon",
                "coordinates": [
                  [[-121.6, 37.1], [-121.8, 37.3], [-122.0, 37.4], [-121.6, 37.1]]
                ]
              },
              "properties": {
                "id": "urn:oid:2.49.0.1.840.0.123",
                "areaDesc": "Santa Clara, CA",
                "affectedZones": ["https://api.weather.gov/zones/county/CAC085"],
                "onset": "2026-11-18T13:23:00-08:00",
                "expires": "2026-11-18T16:30:00-08:00",
                "ends": "2026-11-18T16:30:00-08:00",
                "status": "Actual",
                "severity": "Severe",
                "event": "Tornado Warning",
                "headline": "Tornado Warning issued by NWS San Francisco CA",
                "description": "Take cover now.",
                "instruction": "Move to an interior room."
              }
            }
          ]
        }
        """.trimIndent()

        val parsed = DwdWarningFetcher.parseNwsGeoJson(sampleNwsGeoJson)
        assertEquals(1, parsed.size)
        val alert = parsed[0]
        assertEquals("Tornado Warning", alert.warning.title)
        assertEquals(WarningSource.NWS, alert.warning.source)
        assertEquals(WarningSeverity.SEVERE, alert.warning.severity)
        assertEquals("Santa Clara, CA", alert.warning.area)
        assertEquals(1, alert.warning.polygons.size)
        assertEquals(37.1, alert.warning.polygons[0][0].lat, 0.0001)
        assertEquals(-121.6, alert.warning.polygons[0][0].lon, 0.0001)
        assertEquals(listOf("https://api.weather.gov/zones/county/CAC085"), alert.affectedZones)
        assertNotNull(alert.warning.onsetInstant)
        assertNotNull(alert.warning.expiresInstant)
    }
}

package com.frogweather.phase2

import com.frogweather.analysis.ForecastConfidenceEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastConfidenceEngineTest {

    @Test
    fun `high agreement across models produces Hoch tier`() {
        val temps = mapOf(
            "ecmwf_ifs025" to listOf(14.0, 15.0, 16.0),
            "gfs_global" to listOf(14.2, 15.1, 15.8),
            "icon_d2" to listOf(13.9, 14.9, 16.1),
            "owm" to listOf(14.1, 15.0, 16.0)
        )
        val precips = mapOf(
            "ecmwf_ifs025" to listOf(0.0, 0.0, 0.0),
            "gfs_global" to listOf(0.0, 0.0, 0.0),
            "icon_d2" to listOf(0.0, 0.0, 0.0),
            "owm" to listOf(0.0, 0.0, 0.0)
        )
        val winds = mapOf(
            "ecmwf_ifs025" to listOf(12.0, 14.0, 13.0),
            "gfs_global" to listOf(12.5, 13.5, 13.0),
            "icon_d2" to listOf(11.8, 14.2, 13.4),
            "owm" to listOf(12.0, 14.0, 13.0)
        )

        val result = ForecastConfidenceEngine.computeConfidence(temps, precips, winds)
        assertTrue(result.hasEnoughModels)
        assertTrue(result.percent >= 75)
        assertEquals("Hoch", result.tierLabel)
        assertEquals("${result.percent} % · Hoch", result.formattedLabel)
        assertEquals(3, result.hourlyConfidences.size)
        assertTrue(result.divergenceExplanation.contains("Übereinstimmung"))
    }

    @Test
    fun `strong divergence produces Unsicher tier and names diverging models and parameters`() {
        val temps = mapOf(
            "ecmwf_ifs025" to listOf(10.0, 11.0),
            "gfs_global" to listOf(22.0, 23.0),
            "icon_d2" to listOf(15.0, 16.0)
        )
        val precips = mapOf(
            "ecmwf_ifs025" to listOf(0.0, 0.0),
            "gfs_global" to listOf(6.0, 7.0),
            "icon_d2" to listOf(0.5, 0.2)
        )
        val winds = mapOf(
            "ecmwf_ifs025" to listOf(10.0, 12.0),
            "gfs_global" to listOf(45.0, 50.0),
            "icon_d2" to listOf(20.0, 22.0)
        )

        val result = ForecastConfidenceEngine.computeConfidence(temps, precips, winds)
        assertTrue(result.hasEnoughModels)
        assertTrue(result.percent < 50)
        assertEquals("Unsicher", result.tierLabel)
        assertTrue(result.divergenceExplanation.contains("ECMWF"))
        assertTrue(result.divergenceExplanation.contains("GFS"))
        assertTrue(
            result.divergenceExplanation.contains("Temperatur") ||
                result.divergenceExplanation.contains("Niederschlag")
        )
    }

    @Test
    fun `all-null or empty icon_d2 is excluded dynamically without crashing or distorting confidence`() {
        val temps = mapOf(
            "ecmwf_ifs025" to listOf(20.0, 21.0, 22.0),
            "gfs_global" to listOf(20.3, 21.2, 21.8),
            "icon_d2" to listOf(null, null, null)
        )
        val precips = mapOf(
            "ecmwf_ifs025" to listOf(0.0, 0.0, 0.0),
            "gfs_global" to listOf(0.0, 0.0, 0.0),
            "icon_d2" to listOf(null, null, null)
        )
        val winds = mapOf(
            "ecmwf_ifs025" to listOf(10.0, 10.0, 10.0),
            "gfs_global" to listOf(11.0, 10.5, 10.0),
            "icon_d2" to emptyList()
        )

        val result = ForecastConfidenceEngine.computeConfidence(temps, precips, winds)
        assertTrue(result.hasEnoughModels)
        assertEquals(listOf("ecmwf_ifs025", "gfs_global"), result.activeModels)
        assertEquals(listOf("icon_d2"), result.excludedModels)
        assertTrue(result.percent >= 75)
        assertTrue(result.divergenceExplanation.contains("ICON-D2"))
    }

    @Test
    fun `single valid model or zero valid models never falsely reports 100 percent Hoch`() {
        val oneModel = ForecastConfidenceEngine.computeConfidence(
            modelTemps = mapOf("ecmwf_ifs025" to listOf(15.0, 16.0), "icon_d2" to listOf(null, null)),
            modelPrecips = mapOf("ecmwf_ifs025" to listOf(0.0, 0.0)),
            modelWinds = mapOf("ecmwf_ifs025" to listOf(10.0, 12.0))
        )
        assertFalse(oneModel.hasEnoughModels)
        assertEquals("Unsicher", oneModel.tierLabel)
        assertEquals("45 % · Unsicher", oneModel.formattedLabel)

        val zeroModels = ForecastConfidenceEngine.computeConfidence(emptyMap(), emptyMap(), emptyMap())
        assertFalse(zeroModels.hasEnoughModels)
        assertEquals(0, zeroModels.percent)
        assertEquals("0 % · Unsicher", zeroModels.formattedLabel)
    }
}

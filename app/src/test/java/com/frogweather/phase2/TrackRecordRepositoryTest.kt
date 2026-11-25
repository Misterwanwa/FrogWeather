package com.frogweather.phase2

import com.frogweather.data.ForecastSnapshot
import com.frogweather.data.ObservedWeatherPoint
import com.frogweather.data.TrackRecordRepository
import com.frogweather.phase1.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRecordRepositoryTest {

    private fun snapshot(
        id: String = "snap_1",
        lat: Double = 52.52,
        lon: Double = 13.41,
        capturedAt: Long = 1_700_000_000_000L,
        targetAt: Long = 1_700_003_600_000L,
        tempC: Double = 15.0,
        precipMm: Double = 0.0,
        precipProb: Int = 10,
        windKmh: Double = 12.0,
        drySport: Boolean = true,
        modelTemps: Map<String, Double> = mapOf("ecmwf_ifs025" to 15.2, "gfs_global" to 17.5)
    ) = ForecastSnapshot(
        id = id,
        locationKey = "52.52,13.41",
        cityName = "Berlin",
        latitude = lat,
        longitude = lon,
        capturedAtMillis = capturedAt,
        targetEpochMillis = targetAt,
        predictedTempC = tempC,
        predictedPrecipMm = precipMm,
        predictedPrecipProb = precipProb,
        predictedWindKmh = windKmh,
        predictedDrySportWindow = drySport,
        modelPredictedTemps = modelTemps
    )

    @Test
    fun `empty store returns zero verified and German empty status label`() {
        val repo = TrackRecordRepository(InMemoryKeyValueStore())
        val summary = repo.getTrackRecordSummary(52.52, 13.41)
        assertEquals(0, summary.verifiedCount)
        assertEquals(0, summary.pendingCount)
        assertNull(summary.meanTempErrorC)
        assertNull(summary.rainHitRatePct)
        assertNull(summary.drySportWindowAccuracyPct)
        assertNull(summary.bestModel)
        assertEquals("Noch keine Verlaufsdaten", summary.statusLabel)
    }

    @Test
    fun `defends against Moshi null arrays and corrupt JSON`() {
        val store = InMemoryKeyValueStore()
        store.putString("track_record_snapshots_json", "[null, null]")
        val repoNull = TrackRecordRepository(store)
        assertTrue(repoNull.getAllSnapshots().isEmpty())

        store.putString("track_record_snapshots_json", "{not_valid_json")
        assertTrue(repoNull.getAllSnapshots().isEmpty())
    }

    @Test
    fun `verifies past-due snapshots against actuals, computes metrics and bestModel, and never overwrites verified`() {
        val store = InMemoryKeyValueStore()
        val repo = TrackRecordRepository(store)
        val t1 = 1_700_010_800_000L
        val t2 = 1_700_014_400_000L

        repo.recordForecastSnapshots(
            listOf(
                snapshot(
                    id = "s1",
                    capturedAt = t1 - 3_600_000L,
                    targetAt = t1,
                    tempC = 14.0,
                    precipMm = 0.0,
                    precipProb = 10,
                    drySport = true,
                    modelTemps = mapOf("ecmwf_ifs025" to 15.0, "gfs_global" to 18.0)
                ),
                snapshot(
                    id = "s2",
                    capturedAt = t2 - 3_600_000L,
                    targetAt = t2,
                    tempC = 12.0,
                    precipMm = 2.0,
                    precipProb = 80,
                    drySport = false,
                    modelTemps = mapOf("ecmwf_ifs025" to 11.5, "gfs_global" to 15.0)
                )
            )
        )

        val verifiedCount = repo.verifyWithObservations(
            latitude = 52.52,
            longitude = 13.41,
            observations = listOf(
                ObservedWeatherPoint(t1, tempC = 15.0, precipMm = 0.0, windKmh = 10.0), // |14-15|=1.0, dry hit, rain TN
                ObservedWeatherPoint(t2, tempC = 11.0, precipMm = 1.5, windKmh = 14.0)  // |12-11|=1.0, rain TP
            )
        )
        assertEquals(2, verifiedCount)

        // Attempting to re-record same bucket must NOT overwrite verified snapshot
        repo.recordForecastSnapshot(
            snapshot(id = "s1_overwrite", capturedAt = t1 - 1800_000L, targetAt = t1, tempC = 99.0)
        )

        val reloaded = TrackRecordRepository(store)
        val summary = reloaded.getTrackRecordSummary(52.52, 13.41)
        assertEquals(2, summary.verifiedCount)
        assertEquals(0, summary.pendingCount)
        assertEquals(1.0, summary.meanTempErrorC!!, 1e-6)
        assertEquals(100, summary.rainHitRatePct)
        assertEquals(100, summary.drySportWindowAccuracyPct)
        assertEquals("ECMWF", summary.bestModel)
        assertNotNull(summary.bestModelMaeC)
        assertEquals(0.25, summary.bestModelMaeC!!, 1e-6)
    }

    @Test
    fun `verifyCurrentObservation matches 2h past window and isolates distant cities`() {
        val store = InMemoryKeyValueStore()
        val repo = TrackRecordRepository(store)
        val target = 1_700_050_000_000L

        repo.recordForecastSnapshot(
            snapshot(id = "berlin", lat = 52.52, lon = 13.41, capturedAt = target - 7200_000L, targetAt = target)
        )
        repo.recordForecastSnapshot(
            snapshot(id = "tokyo", lat = 35.68, lon = 139.77, capturedAt = target - 7200_000L, targetAt = target)
        )

        val count = repo.verifyCurrentObservation(
            latitude = 52.53,
            longitude = 13.40,
            observed = ObservedWeatherPoint(target + 1_800_000L, tempC = 16.0, precipMm = 0.0, windKmh = 10.0)
        )
        assertEquals(1, count)
        assertEquals(1, repo.getTrackRecordSummary(52.52, 13.41).verifiedCount)
        assertEquals(0, repo.getTrackRecordSummary(35.68, 139.77).verifiedCount)
        assertEquals(1, repo.getTrackRecordSummary(35.68, 139.77).pendingCount)
    }

    @Test
    fun `preserves valid snapshots when array has corrupt entries or null values inside modelPredictedTemps`() {
        val store = InMemoryKeyValueStore()
        val mixedJson = """
            [
              {
                "id": "valid_1",
                "locationKey": "52.52,13.41",
                "cityName": "Berlin",
                "latitude": 52.52,
                "longitude": 13.41,
                "capturedAtMillis": 1700000000000,
                "targetEpochMillis": 1700003600000,
                "predictedTempC": 14.0,
                "predictedPrecipMm": 0.0,
                "predictedPrecipProb": 10,
                "predictedWindKmh": 12.0,
                "predictedDrySportWindow": true,
                "modelPredictedTemps": {"ecmwf_ifs025": 14.2, "gfs_global": 15.0}
              },
              null,
              "corrupt_string_entry",
              {"id": "incomplete_entry"},
              {
                "id": "valid_with_null_model_temp",
                "locationKey": "52.52,13.41",
                "cityName": "Berlin",
                "latitude": 52.52,
                "longitude": 13.41,
                "capturedAtMillis": 1700000000000,
                "targetEpochMillis": 1700007200000,
                "predictedTempC": 12.0,
                "predictedPrecipMm": 0.1,
                "predictedPrecipProb": 20,
                "predictedWindKmh": 10.0,
                "predictedDrySportWindow": true,
                "modelPredictedTemps": {"ecmwf_ifs025": 12.0, "gfs_global": null}
              }
            ]
        """.trimIndent()
        store.putString("track_record_snapshots_json", mixedJson)

        val repo = TrackRecordRepository(store)
        val loaded = repo.getAllSnapshots()
        assertEquals(2, loaded.size)
        assertEquals("valid_1", loaded[0].id)
        assertEquals(mapOf("ecmwf_ifs025" to 14.2, "gfs_global" to 15.0), loaded[0].modelPredictedTemps)
        assertEquals("valid_with_null_model_temp", loaded[1].id)
        assertEquals(mapOf("ecmwf_ifs025" to 12.0), loaded[1].modelPredictedTemps)
    }
}

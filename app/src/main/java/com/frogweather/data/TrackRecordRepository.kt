package com.frogweather.data

import com.frogweather.analysis.ForecastConfidenceEngine
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportType
import com.frogweather.network.ForecastResponse
import com.frogweather.network.OpenMeteoForecastData
import com.frogweather.network.OpenMeteoModelIds
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class ForecastSnapshot(
    val id: String,
    val locationKey: String,
    val cityName: String,
    val latitude: Double,
    val longitude: Double,
    val capturedAtMillis: Long,
    val targetEpochMillis: Long,
    val predictedTempC: Double,
    val predictedPrecipMm: Double,
    val predictedPrecipProb: Int,
    val predictedWindKmh: Double,
    val predictedDrySportWindow: Boolean,
    val modelPredictedTemps: Map<String, Double> = emptyMap(),
    val verified: Boolean = false,
    val verifiedAtMillis: Long? = null,
    val observedTempC: Double? = null,
    val observedPrecipMm: Double? = null,
    val observedWindKmh: Double? = null
)

data class ObservedWeatherPoint(
    val epochMillis: Long,
    val tempC: Double,
    val precipMm: Double,
    val windKmh: Double
)

data class TrackRecordSummary(
    val verifiedCount: Int,
    val pendingCount: Int,
    val meanTempErrorC: Double?,
    val rainHitRatePct: Int?,
    val drySportWindowAccuracyPct: Int?,
    val dryWindowsPredictedCount: Int,
    val dryWindowsKeptCount: Int,
    val bestModel: String?,
    val bestModelMaeC: Double?,
    val modelMaeByModel: Map<String, Double>,
    val statusLabel: String
)

private data class RawForecastSnapshot(
    val id: String? = null,
    val locationKey: String? = null,
    val cityName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val capturedAtMillis: Long? = null,
    val targetEpochMillis: Long? = null,
    val predictedTempC: Double? = null,
    val predictedPrecipMm: Double? = null,
    val predictedPrecipProb: Int? = null,
    val predictedWindKmh: Double? = null,
    val predictedDrySportWindow: Boolean? = null,
    val modelPredictedTemps: Map<String, Any?>? = null,
    val verified: Boolean? = null,
    val verifiedAtMillis: Long? = null,
    val observedTempC: Double? = null,
    val observedPrecipMm: Double? = null,
    val observedWindKmh: Double? = null
) {
    fun toForecastSnapshotOrNull(): ForecastSnapshot? {
        val safeId = id ?: return null
        val safeLocationKey = locationKey ?: return null
        val safeCityName = cityName ?: return null
        val safeLat = latitude?.takeIf { it.isFinite() } ?: return null
        val safeLon = longitude?.takeIf { it.isFinite() } ?: return null
        val safeCaptured = capturedAtMillis ?: return null
        val safeTarget = targetEpochMillis ?: return null
        val safeTemp = predictedTempC?.takeIf { it.isFinite() } ?: return null
        val safePrecipMm = predictedPrecipMm?.takeIf { it.isFinite() } ?: return null
        val safePrecipProb = predictedPrecipProb ?: return null
        val safeWind = predictedWindKmh?.takeIf { it.isFinite() } ?: return null
        val safeDrySport = predictedDrySportWindow ?: return null

        val sanitizedModelTemps = modelPredictedTemps.orEmpty()
            .entries
            .mapNotNull { (modelId, rawVal) ->
                val num = (rawVal as? Number)?.toDouble()?.takeIf { it.isFinite() }
                if (num != null) modelId to num else null
            }
            .toMap()

        return ForecastSnapshot(
            id = safeId,
            locationKey = safeLocationKey,
            cityName = safeCityName,
            latitude = safeLat,
            longitude = safeLon,
            capturedAtMillis = safeCaptured,
            targetEpochMillis = safeTarget,
            predictedTempC = safeTemp,
            predictedPrecipMm = safePrecipMm,
            predictedPrecipProb = safePrecipProb,
            predictedWindKmh = safeWind,
            predictedDrySportWindow = safeDrySport,
            modelPredictedTemps = sanitizedModelTemps,
            verified = verified ?: false,
            verifiedAtMillis = verifiedAtMillis,
            observedTempC = observedTempC?.takeIf { it.isFinite() },
            observedPrecipMm = observedPrecipMm?.takeIf { it.isFinite() },
            observedWindKmh = observedWindKmh?.takeIf { it.isFinite() }
        )
    }
}

class TrackRecordRepository(
    private val storage: KeyValueStore
) {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val snapshotListType = Types.newParameterizedType(
        List::class.java,
        ForecastSnapshot::class.java
    )
    private val adapter: JsonAdapter<List<ForecastSnapshot?>> = moshi.adapter(snapshotListType)

    private val rawListType = Types.newParameterizedType(
        List::class.java,
        Any::class.java
    )
    private val rawListAdapter: JsonAdapter<List<Any?>> = moshi.adapter(rawListType)
    private val rawSnapshotAdapter: JsonAdapter<RawForecastSnapshot> =
        moshi.adapter(RawForecastSnapshot::class.java)

    fun getAllSnapshots(): List<ForecastSnapshot> {
        val raw = storage.getString(STORAGE_KEY, null)
        if (raw.isNullOrBlank()) return emptyList()
        val rawList = try {
            rawListAdapter.fromJson(raw)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return rawList.mapNotNull { element ->
            if (element == null) {
                null
            } else {
                runCatching {
                    rawSnapshotAdapter.fromJsonValue(element)?.toForecastSnapshotOrNull()
                }.getOrNull()?.let { sanitizeSnapshot(it) }
            }
        }
    }

    fun recordForecastSnapshot(snapshot: ForecastSnapshot): List<ForecastSnapshot> =
        recordForecastSnapshots(listOf(snapshot))

    fun recordForecastSnapshots(snapshots: List<ForecastSnapshot>): List<ForecastSnapshot> {
        val existing = getAllSnapshots().toMutableList()
        var changed = false

        for (rawSnap in snapshots) {
            if (!isValidNewSnapshot(rawSnap)) continue
            val normalizedKey = locationKey(rawSnap.latitude, rawSnap.longitude)
            val snap = sanitizeSnapshot(rawSnap.copy(locationKey = normalizedKey))
            val bucket = hourBucket(snap.targetEpochMillis)

            val matchIndex = existing.indexOfFirst { old ->
                sameLocation(old.latitude, old.longitude, snap.latitude, snap.longitude) &&
                    hourBucket(old.targetEpochMillis) == bucket
            }

            if (matchIndex >= 0) {
                val prev = existing[matchIndex]
                // Never overwrite an already-verified snapshot
                if (!prev.verified) {
                    existing[matchIndex] = snap
                    changed = true
                }
            } else {
                existing.add(snap)
                changed = true
            }
        }

        if (!changed) return existing
        val bounded = enforceRetention(existing)
        saveSnapshots(bounded)
        return bounded
    }

    fun saveForecastSnapshotsFromForecast(
        latitude: Double,
        longitude: Double,
        cityName: String,
        forecast: OpenMeteoForecastData,
        owmForecast: ForecastResponse? = null,
        sport: SportType = SportType.RUNNING,
        capturedAtMillis: Long = System.currentTimeMillis(),
        leadHours: List<Int> = listOf(1, 3, 6, 12, 24)
    ): List<ForecastSnapshot> {
        val points = forecast.toHourlyWeatherPoints()
        if (points.isEmpty()) return getAllSnapshots()

        val evaluationsByMillis = SportAdvisor.evaluateHoursToSkip(sport, points)
            .allEvaluations
            .associateBy { it.hour.epochMillis }

        val alignedOwm = owmForecast?.let { forecast.alignOwmForecast(it) }
        val normalizedKey = locationKey(latitude, longitude)
        val snapshots = mutableListOf<ForecastSnapshot>()

        for (leadHour in leadHours) {
            if (leadHour <= 0) continue
            val leadMillis = leadHour.toLong() * ONE_HOUR_MILLIS
            val targetApprox = if (capturedAtMillis > Long.MAX_VALUE - leadMillis) {
                Long.MAX_VALUE
            } else {
                capturedAtMillis + leadMillis
            }
            val idx = forecast.timestampsEpochMillis.indices.minByOrNull { i ->
                absDiffULong(forecast.timestampsEpochMillis[i], targetApprox)
            } ?: continue
            val targetMillis = forecast.timestampsEpochMillis[idx]
            if (targetMillis <= capturedAtMillis) continue
            val eval = evaluationsByMillis[targetMillis]
            val point = eval?.hour ?: points.getOrNull(idx) ?: continue
            val drySport = eval != null && !eval.shouldSkip && point.precipMm < RAIN_THRESHOLD_MM

            val modelTemps = linkedMapOf<String, Double>()
            for (modelId in OpenMeteoModelIds.COMPARISON_MODELS) {
                val value = forecast.models[modelId]?.temperature2m?.getOrNull(idx)
                if (value != null && value.isFinite()) {
                    modelTemps[modelId] = value
                }
            }
            val owmTemp = alignedOwm?.temps?.getOrNull(idx)
            if (owmTemp != null && owmTemp.isFinite()) {
                modelTemps[OpenMeteoModelIds.OWM] = owmTemp
            }

            snapshots.add(
                ForecastSnapshot(
                    id = "${normalizedKey}_${hourBucket(targetMillis)}",
                    locationKey = normalizedKey,
                    cityName = cityName,
                    latitude = latitude,
                    longitude = longitude,
                    capturedAtMillis = capturedAtMillis,
                    targetEpochMillis = targetMillis,
                    predictedTempC = point.tempC,
                    predictedPrecipMm = point.precipMm,
                    predictedPrecipProb = point.precipProb,
                    predictedWindKmh = point.windKmh,
                    predictedDrySportWindow = drySport,
                    modelPredictedTemps = modelTemps
                )
            )
        }

        return recordForecastSnapshots(snapshots)
    }

    fun verifyCurrentObservation(
        latitude: Double,
        longitude: Double,
        observed: ObservedWeatherPoint
    ): Int {
        if (!isValidObservation(latitude, longitude, observed)) return 0
        val all = getAllSnapshots().toMutableList()
        var newlyVerified = 0

        for (i in all.indices) {
            val snap = all[i]
            if (snap.verified) continue
            if (!sameLocation(snap.latitude, snap.longitude, latitude, longitude)) continue

            val withinObsWindow = observed.epochMillis >= snap.targetEpochMillis &&
                (snap.targetEpochMillis > Long.MAX_VALUE - CURRENT_OBS_WINDOW_MILLIS ||
                    observed.epochMillis <= snap.targetEpochMillis + CURRENT_OBS_WINDOW_MILLIS)
            if (withinObsWindow) {
                all[i] = snap.copy(
                    verified = true,
                    verifiedAtMillis = observed.epochMillis,
                    observedTempC = observed.tempC,
                    observedPrecipMm = observed.precipMm.coerceAtLeast(0.0),
                    observedWindKmh = observed.windKmh.coerceAtLeast(0.0)
                )
                newlyVerified++
            }
        }

        if (newlyVerified > 0) {
            saveSnapshots(all)
        }
        return newlyVerified
    }

    fun verifyWithObservations(
        latitude: Double,
        longitude: Double,
        observations: List<ObservedWeatherPoint>
    ): Int {
        val validObs = observations.filter { isValidObservation(latitude, longitude, it) }
        if (validObs.isEmpty()) return 0

        val all = getAllSnapshots().toMutableList()
        var newlyVerified = 0

        for (i in all.indices) {
            val snap = all[i]
            if (snap.verified) continue
            if (!sameLocation(snap.latitude, snap.longitude, latitude, longitude)) continue

            val match = validObs
                .filter { absDiffULong(it.epochMillis, snap.targetEpochMillis) <= HOURLY_MATCH_TOLERANCE_MILLIS.toULong() }
                .minByOrNull { absDiffULong(it.epochMillis, snap.targetEpochMillis) }
                ?: continue

            all[i] = snap.copy(
                verified = true,
                verifiedAtMillis = match.epochMillis,
                observedTempC = match.tempC,
                observedPrecipMm = match.precipMm.coerceAtLeast(0.0),
                observedWindKmh = match.windKmh.coerceAtLeast(0.0)
            )
            newlyVerified++
        }

        if (newlyVerified > 0) {
            saveSnapshots(all)
        }
        return newlyVerified
    }

    fun getTrackRecordSummary(
        latitude: Double? = null,
        longitude: Double? = null
    ): TrackRecordSummary {
        val all = getAllSnapshots()
        val scoped = if (latitude != null && longitude != null) {
            all.filter { sameLocation(it.latitude, it.longitude, latitude, longitude) }
        } else {
            all
        }

        val verified = scoped.filter {
            it.verified && it.observedTempC != null && it.observedTempC.isFinite() &&
                it.observedPrecipMm != null && it.observedPrecipMm.isFinite()
        }
        val pendingCount = scoped.count { !it.verified }

        if (verified.isEmpty()) {
            val status = if (pendingCount > 0) {
                "$pendingCount Prognose(n) ausstehend"
            } else {
                "Noch keine Verlaufsdaten"
            }
            return TrackRecordSummary(
                verifiedCount = 0,
                pendingCount = pendingCount,
                meanTempErrorC = null,
                rainHitRatePct = null,
                drySportWindowAccuracyPct = null,
                dryWindowsPredictedCount = 0,
                dryWindowsKeptCount = 0,
                bestModel = null,
                bestModelMaeC = null,
                modelMaeByModel = emptyMap(),
                statusLabel = status
            )
        }

        val meanTempErrorC = verified.map { abs(it.predictedTempC - it.observedTempC!!) }.average()

        val rainHits = verified.count { snap ->
            val predictedRain = snap.predictedPrecipMm >= RAIN_THRESHOLD_MM || snap.predictedPrecipProb >= 50
            val observedRain = snap.observedPrecipMm!! >= RAIN_THRESHOLD_MM
            predictedRain == observedRain
        }
        val rainHitRatePct = ((rainHits * 100.0) / verified.size).roundToInt().coerceIn(0, 100)

        val dryPredicted = verified.filter { it.predictedDrySportWindow }
        val dryKept = dryPredicted.count { it.observedPrecipMm!! < RAIN_THRESHOLD_MM }
        val dryAccuracyPct = if (dryPredicted.isEmpty()) {
            null
        } else {
            ((dryKept * 100.0) / dryPredicted.size).roundToInt().coerceIn(0, 100)
        }

        val modelErrors = linkedMapOf<String, MutableList<Double>>()
        for (snap in verified) {
            val actualTemp = snap.observedTempC ?: continue
            for ((modelId, predictedModelTemp) in snap.modelPredictedTemps) {
                if (predictedModelTemp.isFinite()) {
                    modelErrors.getOrPut(modelId) { mutableListOf() }
                        .add(abs(predictedModelTemp - actualTemp))
                }
            }
        }

        val modelMaeByModel = modelErrors.mapValues { (_, errors) -> errors.average() }
        val bestEntry = modelMaeByModel.entries
            .minWithOrNull { a, b ->
                val cmp = a.value.compareTo(b.value)
                if (cmp != 0) cmp else a.key.compareTo(b.key)
            }

        val bestModelName = bestEntry?.key?.let { ForecastConfidenceEngine.modelDisplayName(it) }
        val statusLabel = "${verified.size} Prognosen geprüft • $pendingCount ausstehend"

        return TrackRecordSummary(
            verifiedCount = verified.size,
            pendingCount = pendingCount,
            meanTempErrorC = meanTempErrorC,
            rainHitRatePct = rainHitRatePct,
            drySportWindowAccuracyPct = dryAccuracyPct,
            dryWindowsPredictedCount = dryPredicted.size,
            dryWindowsKeptCount = dryKept,
            bestModel = bestModelName,
            bestModelMaeC = bestEntry?.value,
            modelMaeByModel = modelMaeByModel,
            statusLabel = statusLabel
        )
    }

    private fun saveSnapshots(snapshots: List<ForecastSnapshot>) {
        storage.putString(STORAGE_KEY, adapter.toJson(snapshots))
    }

    private fun enforceRetention(snapshots: List<ForecastSnapshot>): List<ForecastSnapshot> {
        if (snapshots.size <= MAX_SNAPSHOTS) return snapshots
        val verified = snapshots.filter { it.verified }.sortedByDescending { it.targetEpochMillis }
        val pending = snapshots.filter { !it.verified }.sortedByDescending { it.targetEpochMillis }
        val keptVerified = verified.take(MAX_SNAPSHOTS / 2)
        val remainingSlots = (MAX_SNAPSHOTS - keptVerified.size).coerceAtLeast(0)
        val keptPending = pending.take(remainingSlots)
        val combined = (keptVerified + keptPending).toMutableList()
        if (combined.size < MAX_SNAPSHOTS && verified.size > keptVerified.size) {
            combined.addAll(verified.drop(keptVerified.size).take(MAX_SNAPSHOTS - combined.size))
        }
        return combined.sortedBy { it.targetEpochMillis }
    }

    private fun sanitizeSnapshot(snap: ForecastSnapshot): ForecastSnapshot = snap.copy(
        modelPredictedTemps = snap.modelPredictedTemps.filterValues { it.isFinite() }
    )

    private fun isValidNewSnapshot(snap: ForecastSnapshot): Boolean =
        snap.latitude.isFinite() &&
            snap.longitude.isFinite() &&
            snap.predictedTempC.isFinite() &&
            snap.predictedPrecipMm.isFinite() &&
            snap.predictedWindKmh.isFinite() &&
            snap.targetEpochMillis > snap.capturedAtMillis

    private fun isValidObservation(
        latitude: Double,
        longitude: Double,
        observed: ObservedWeatherPoint
    ): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            observed.tempC.isFinite() &&
            observed.precipMm.isFinite() &&
            observed.windKmh.isFinite()

    private fun sameLocation(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean =
        abs(lat1 - lat2) <= COORD_TOLERANCE_DEG && abs(lon1 - lon2) <= COORD_TOLERANCE_DEG

    private fun locationKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.2f,%.2f", lat, lon)

    private fun absDiffULong(a: Long, b: Long): ULong =
        if (a >= b) a.toULong() - b.toULong() else b.toULong() - a.toULong()

    private fun hourBucket(epochMillis: Long): Long = Math.floorDiv(epochMillis, ONE_HOUR_MILLIS)

    companion object {
        private const val STORAGE_KEY = "track_record_snapshots_json"
        const val MAX_SNAPSHOTS = 200
        private const val COORD_TOLERANCE_DEG = 0.05
        private const val ONE_HOUR_MILLIS = 3_600_000L
        private const val CURRENT_OBS_WINDOW_MILLIS = 2L * ONE_HOUR_MILLIS
        private const val HOURLY_MATCH_TOLERANCE_MILLIS = 30L * 60_000L
        private const val RAIN_THRESHOLD_MM = 0.1
    }
}

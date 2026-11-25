package com.frogweather.e2e

import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.data.FavoriteLocation
import com.frogweather.data.KeyValueStore
import com.frogweather.data.WeatherScenario
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Shared test fixtures for Stage A & Stage B opaque-box E2E tests (Phases 1-4, Tiers 1-4).
 * Pure-Kotlin, deterministic, and isolated — no Android runtime or network calls required.
 */
object E2ETestFixtures {

    const val HOUR_MILLIS: Long = 3_600_000L
    const val DAY_MILLIS: Long = 86_400_000L

    /** 2026-07-05T00:00:00Z in epoch milliseconds. */
    const val BASE_EPOCH_MILLIS: Long = 1_783_209_600_000L

    // -------------------------------------------------------------------------
    // Shared In-Memory KeyValueStore
    // -------------------------------------------------------------------------

    class InMemoryKeyValueStore(
        initialEntries: Map<String, Any> = emptyMap()
    ) : KeyValueStore {
        private val entries = LinkedHashMap<String, Any>(initialEntries)

        override fun getString(key: String, defaultValue: String?): String? {
            return (entries[key] as? String) ?: defaultValue
        }

        override fun putString(key: String, value: String?) {
            if (value == null) {
                entries.remove(key)
            } else {
                entries[key] = value
            }
        }

        fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return (entries[key] as? Boolean) ?: defaultValue
        }

        fun putBoolean(key: String, value: Boolean) {
            entries[key] = value
        }

        fun getInt(key: String, defaultValue: Int): Int {
            return (entries[key] as? Number)?.toInt() ?: defaultValue
        }

        fun putInt(key: String, value: Int) {
            entries[key] = value
        }

        fun getLong(key: String, defaultValue: Long): Long {
            return (entries[key] as? Number)?.toLong() ?: defaultValue
        }

        fun putLong(key: String, value: Long) {
            entries[key] = value
        }

        fun getDouble(key: String, defaultValue: Double): Double {
            return (entries[key] as? Number)?.toDouble() ?: defaultValue
        }

        fun putDouble(key: String, value: Double) {
            entries[key] = value
        }

        override fun remove(key: String) {
            entries.remove(key)
        }

        fun contains(key: String): Boolean {
            return entries.containsKey(key)
        }

        fun getFloat(key: String, defaultValue: Float): Float {
            return (entries[key] as? Number)?.toFloat() ?: defaultValue
        }

        fun putFloat(key: String, value: Float) {
            entries[key] = value
        }

        fun allKeys(): Set<String> = entries.keys.toSet()

        fun clearAll() {
            entries.clear()
        }
    }

    // -------------------------------------------------------------------------
    // FavoriteLocation & City Factories
    // -------------------------------------------------------------------------

    fun sampleFavorite(
        id: String = "berlin_de",
        name: String = "Berlin",
        country: String = "Deutschland",
        latitude: Double = 52.5200,
        longitude: Double = 13.4050,
        addedAtMillis: Long = BASE_EPOCH_MILLIS
    ): FavoriteLocation = FavoriteLocation(
        id = id,
        name = name,
        country = country,
        latitude = latitude,
        longitude = longitude,
        addedAtMillis = addedAtMillis
    )

    fun berlinFavorite(addedAtMillis: Long = BASE_EPOCH_MILLIS): FavoriteLocation =
        sampleFavorite(
            id = "berlin_de",
            name = "Berlin",
            country = "Deutschland",
            latitude = 52.5200,
            longitude = 13.4050,
            addedAtMillis = addedAtMillis
        )

    fun munichFavorite(addedAtMillis: Long = BASE_EPOCH_MILLIS + 1_000L): FavoriteLocation =
        sampleFavorite(
            id = "munich_de",
            name = "München",
            country = "Deutschland",
            latitude = 48.1351,
            longitude = 11.5820,
            addedAtMillis = addedAtMillis
        )

    fun tokyoFavorite(addedAtMillis: Long = BASE_EPOCH_MILLIS + 2_000L): FavoriteLocation =
        sampleFavorite(
            id = "tokyo_jp",
            name = "Tokyo",
            country = "Japan",
            latitude = 35.6762,
            longitude = 139.6503,
            addedAtMillis = addedAtMillis
        )

    fun hamburgFavorite(addedAtMillis: Long = BASE_EPOCH_MILLIS + 3_000L): FavoriteLocation =
        sampleFavorite(
            id = "hamburg_de",
            name = "Hamburg",
            country = "Deutschland",
            latitude = 53.5511,
            longitude = 9.9937,
            addedAtMillis = addedAtMillis
        )

    fun allWeatherScenarios(): List<WeatherScenario> = WeatherScenario.values().toList()

    // -------------------------------------------------------------------------
    // HourlyWeatherPoint & 24-Hour Series Factories
    // -------------------------------------------------------------------------

    fun sampleHourlyPoint(
        epochMillis: Long = BASE_EPOCH_MILLIS,
        tempC: Double = 18.0,
        precipMm: Double = 0.0,
        precipProb: Int = 10,
        windKmh: Double = 12.0,
        gustKmh: Double = 18.0,
        uvIndex: Double = 3.0,
        capeJkg: Double = 50.0,
        weatherCode: Int = 1,
        humidityPct: Int = 55,
        cloudCoverPct: Int = 20,
        pressureHpa: Double = 1015.0
    ): HourlyWeatherPoint = HourlyWeatherPoint(
        epochMillis = epochMillis,
        tempC = tempC,
        precipMm = precipMm,
        precipProb = precipProb,
        windKmh = windKmh,
        gustKmh = gustKmh,
        uvIndex = uvIndex,
        capeJkg = capeJkg,
        weatherCode = weatherCode,
        humidityPct = humidityPct,
        cloudCoverPct = cloudCoverPct,
        pressureHpa = pressureHpa
    )

    /**
     * Generates a deterministic 24-hour forecast starting at [startEpochMillis].
     * Diurnal temperature reaches [minTempC] around 05:00 and [maxTempC] around 14:00-15:00.
     * UV peaks at [peakUv] near solar noon (13:00) and is 0.0 during night hours.
     */
    fun sample24HourSeries(
        startEpochMillis: Long = BASE_EPOCH_MILLIS,
        minTempC: Double = 11.0,
        maxTempC: Double = 22.0,
        peakUv: Double = 5.0,
        rainHours: Set<Int> = emptySet(),
        thunderstormHours: Set<Int> = emptySet(),
        snowHours: Set<Int> = emptySet(),
        windyHours: Set<Int> = emptySet(),
        highUvHours: Set<Int> = emptySet(),
        windKmhBase: Double = 10.0,
        gustKmhBase: Double = 16.0
    ): List<HourlyWeatherPoint> {
        return (0 until 24).map { hour ->
            // Cosine diurnal wave: minimum at hour 5, maximum at hour 17/14 approx (phase shifted to min=5)
            val phase = ((hour - 5.0) / 24.0) * 2.0 * PI
            val diurnalFactor = (1.0 - cos(phase)) / 2.0 // 0 at hour 5, 1 at hour 17
            val temp = minTempC + (maxTempC - minTempC) * diurnalFactor

            val baseUv = if (hour in 7..19) {
                max(0.0, peakUv * sin(((hour - 7.0) / 12.0) * PI))
            } else {
                0.0
            }

            val isStorm = hour in thunderstormHours
            val isRain = hour in rainHours
            val isSnow = hour in snowHours
            val isWindy = hour in windyHours
            val isHighUv = hour in highUvHours

            val uv = if (isHighUv) max(baseUv, 8.5) else if (isStorm || isRain) baseUv * 0.3 else baseUv
            val precipMm = when {
                isStorm -> 8.5
                isRain -> 3.2
                isSnow -> 2.4
                else -> 0.0
            }
            val precipProb = when {
                isStorm -> 95
                isRain || isSnow -> 85
                else -> 10
            }
            val wind = when {
                isStorm -> max(windKmhBase, 42.0)
                isWindy -> max(windKmhBase, 38.0)
                else -> windKmhBase
            }
            val gust = when {
                isStorm -> max(gustKmhBase, 70.0)
                isWindy -> max(gustKmhBase, 58.0)
                else -> gustKmhBase
            }
            val cape = if (isStorm) 1800.0 else 40.0
            val code = when {
                isStorm -> 95
                isSnow -> 73
                isRain -> 63
                else -> 1
            }
            val clouds = when {
                isStorm -> 95
                isRain || isSnow -> 85
                else -> 20
            }
            val humidity = when {
                isStorm || isRain || isSnow -> 88
                else -> 52
            }

            sampleHourlyPoint(
                epochMillis = startEpochMillis + hour * HOUR_MILLIS,
                tempC = temp,
                precipMm = precipMm,
                precipProb = precipProb,
                windKmh = wind,
                gustKmh = gust,
                uvIndex = uv,
                capeJkg = cape,
                weatherCode = code,
                humidityPct = humidity,
                cloudCoverPct = clouds,
                pressureHpa = if (isStorm) 1004.0 else 1016.0
            )
        }
    }

    // -------------------------------------------------------------------------
    // Historical Daily Series & Multi-Model Forecast Fixtures (Phases 2, T3, T4)
    // -------------------------------------------------------------------------

    data class DailyHistoryFixture(
        val dates: List<String>,
        val mins: List<Double>,
        val maxs: List<Double>,
        val means: List<Double>
    )

    fun sampleDailyHistoryData(
        days: Int = 30,
        baseMinC: Double = 11.0,
        baseMaxC: Double = 22.0
    ): DailyHistoryFixture {
        val dates = (0 until days).map { offset ->
            val dayOfMonth = (offset % 28) + 1
            val month = 6 + (offset / 28)
            "2026-%02d-%02d".format(month, dayOfMonth)
        }
        val mins = (0 until days).map { i -> baseMinC + sin(i * 0.4) * 2.0 }
        val maxs = (0 until days).map { i -> baseMaxC + sin(i * 0.4) * 2.5 }
        val means = mins.zip(maxs) { mn, mx -> (mn + mx) / 2.0 }
        return DailyHistoryFixture(dates = dates, mins = mins, maxs = maxs, means = means)
    }

    data class MultiModelSeriesFixture(
        val modelTemps: Map<String, List<Double?>>,
        val modelPrecips: Map<String, List<Double?>>,
        val modelWinds: Map<String, List<Double?>>
    )

    fun sampleMultiModelSeries(
        hours: Int = 24,
        includeIconD2: Boolean = true,
        tempSpreadC: Double = 0.4,
        precipSpreadMm: Double = 0.0,
        windSpreadKmh: Double = 1.0
    ): MultiModelSeriesFixture {
        val baseTemps = List(hours) { h -> 16.0 + 4.0 * sin(h / 24.0 * 2.0 * PI) }
        val basePrecips = List(hours) { 0.0 }
        val baseWinds = List(hours) { 12.0 }

        val temps = linkedMapOf<String, List<Double?>>(
            "ecmwf_ifs025" to baseTemps,
            "gfs_global" to baseTemps.map { it + tempSpreadC },
            "owm" to baseTemps.map { it - tempSpreadC },
            "icon_d2" to if (includeIconD2) baseTemps.map { it + tempSpreadC * 0.5 } else List(hours) { null }
        )
        val precips = linkedMapOf<String, List<Double?>>(
            "ecmwf_ifs025" to basePrecips,
            "gfs_global" to basePrecips.map { it + precipSpreadMm },
            "owm" to basePrecips,
            "icon_d2" to if (includeIconD2) basePrecips else List(hours) { null }
        )
        val winds = linkedMapOf<String, List<Double?>>(
            "ecmwf_ifs025" to baseWinds,
            "gfs_global" to baseWinds.map { it + windSpreadKmh },
            "owm" to baseWinds.map { max(0.0, it - windSpreadKmh) },
            "icon_d2" to if (includeIconD2) baseWinds else List(hours) { null }
        )
        return MultiModelSeriesFixture(
            modelTemps = temps,
            modelPrecips = precips,
            modelWinds = winds
        )
    }

    // -------------------------------------------------------------------------
    // Calendar & Route Shared Specs (Phase 4, T3, T4)
    // -------------------------------------------------------------------------

    data class SampleCalendarEventSpec(
        val id: String,
        val title: String,
        val description: String,
        val location: String?,
        val startMillis: Long,
        val endMillis: Long,
        val isAllDay: Boolean = false
    )

    fun sampleOutdoorSportEvent(
        startMillis: Long = BASE_EPOCH_MILLIS + 10 * HOUR_MILLIS,
        durationHours: Int = 2
    ): SampleCalendarEventSpec = SampleCalendarEventSpec(
        id = "evt_outdoor_1",
        title = "Fußball im Park",
        description = "Freundschaftsspiel draußen",
        location = "Tiergarten, Berlin",
        startMillis = startMillis,
        endMillis = startMillis + durationHours * HOUR_MILLIS,
        isAllDay = false
    )

    fun sampleIndoorMeetingEvent(
        startMillis: Long = BASE_EPOCH_MILLIS + 10 * HOUR_MILLIS,
        durationHours: Int = 1
    ): SampleCalendarEventSpec = SampleCalendarEventSpec(
        id = "evt_indoor_1",
        title = "Architektur-Meeting",
        description = "Video-Call im Büro",
        location = "Konferenzraum 3",
        startMillis = startMillis,
        endMillis = startMillis + durationHours * HOUR_MILLIS,
        isAllDay = false
    )

    data class SampleRouteSpec(
        val originName: String,
        val originLat: Double,
        val originLon: Double,
        val destinationName: String,
        val destinationLat: Double,
        val destinationLon: Double,
        val mode: String,
        val waypoints: List<Pair<Double, Double>>
    )

    fun berlinCommuteRoute(mode: String = "cycling"): SampleRouteSpec = SampleRouteSpec(
        originName = "Berlin Alexanderplatz",
        originLat = 52.5219,
        originLon = 13.4132,
        destinationName = "Berlin Charlottenburg",
        destinationLat = 52.5163,
        destinationLon = 13.3041,
        mode = mode,
        waypoints = listOf(
            52.5219 to 13.4132,
            52.5186 to 13.3761,
            52.5145 to 13.3501,
            52.5163 to 13.3041
        )
    )

    fun munichAlpineRoute(mode: String = "driving"): SampleRouteSpec = SampleRouteSpec(
        originName = "München Hauptbahnhof",
        originLat = 48.1402,
        originLon = 11.5583,
        destinationName = "Garmisch-Partenkirchen",
        destinationLat = 47.4917,
        destinationLon = 11.0955,
        mode = mode,
        waypoints = listOf(
            48.1402 to 11.5583,
            47.8266 to 11.3514,
            47.4917 to 11.0955
        )
    )
}

package com.frogweather.service

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// DWD Warning API Models
data class DwdWarningsResponse(
    val features: List<DwdWarningFeature> = emptyList()
)

data class DwdWarningFeature(
    val properties: DwdWarningProperties
)

data class DwdWarningProperties(
    val event: String,
    val description: String,
    val severity: String, // Minor, Moderate, Severe, Extreme
    val onset: String,
    val expires: String,
    val headline: String,
    val instruction: String?,
    val areaDesc: String
)

enum class WarningSource(val badgeText: String) {
    DWD("DWD"),
    NWS("US-NWS")
}

data class WarningLatLon(
    val lat: Double,
    val lon: Double
)

enum class WarningSeverity(val level: Int, val color: Long, val germanName: String) {
    MINOR(1, 0xFF4FC3F7, "Gering"),      // Blue
    MODERATE(2, 0xFFFFA726, "Mittel"),   // Orange
    SEVERE(3, 0xFFEF5350, "Schwer"),     // Red
    EXTREME(4, 0xFFAB47BC, "Extrem"),    // Purple
    UNKNOWN(0, 0xFF9E9E9E, "Unbekannt"); // Gray

    companion object {
        fun fromString(severity: String?): WarningSeverity {
            return when (severity?.uppercase(Locale.ROOT)) {
                "MINOR" -> MINOR
                "MODERATE" -> MODERATE
                "SEVERE" -> SEVERE
                "EXTREME" -> EXTREME
                else -> UNKNOWN
            }
        }
    }
}

// Warning display data
data class WeatherWarning(
    val title: String,
    val description: String,
    val severity: WarningSeverity,
    val area: String,
    val startTime: String,
    val endTime: String,
    val instruction: String?,
    val onsetInstant: Instant? = null,
    val expiresInstant: Instant? = null,
    val id: String = "${title}_${area}_${startTime}",
    val source: WarningSource = WarningSource.DWD,
    val polygons: List<List<WarningLatLon>> = emptyList(),
    val customColorRgb: Long? = null
)

interface DwdWarningService {
    @GET("alerts.json")
    suspend fun getWarningsByCoords(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double
    ): DwdWarningsResponse
}

object DwdWarningClient {
    private val logging by lazy {
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    private val moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.brightsky.dev/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val service: DwdWarningService by lazy { retrofit.create(DwdWarningService::class.java) }
}

/**
 * Fetches real-time weather warnings (text + map polygons) from DWD (Germany/Central Europe)
 * and US-NWS (api.weather.gov for the United States).
 */
object DwdWarningFetcher {

    private const val DWD_WFS_BASE =
        "https://maps.dwd.de/geoserver/dwd/ows?service=WFS&version=2.0.0&request=GetFeature&typeName=dwd:Warnungen_Landkreise&outputFormat=application/json&srsName=EPSG:4326"
    private const val NWS_USER_AGENT = "FrogWeather/1.0 (Android; contact@frogweather.app)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val zoneClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // In-memory caches to keep live map polling fast
    private val zoneGeometryCache = ConcurrentHashMap<String, List<List<WarningLatLon>>>()
    private val pointStateCache = ConcurrentHashMap<String, String>()

    internal data class NwsParsedAlert(
        val warning: WeatherWarning,
        val affectedZones: List<String>
    )

    private data class UsStateBounds(
        val code: String,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    ) {
        val centerLat: Double get() = (minLat + maxLat) / 2.0
        val centerLon: Double get() = (minLon + maxLon) / 2.0
        fun contains(lat: Double, lon: Double): Boolean =
            lat in minLat..maxLat && lon in minLon..maxLon
    }

    private val usStateBounds = listOf(
        UsStateBounds("AL", 30.14, 35.01, -88.47, -84.89),
        UsStateBounds("AK", 51.21, 71.39, -179.15, -129.98),
        UsStateBounds("AZ", 31.33, 37.00, -114.81, -109.04),
        UsStateBounds("AR", 33.00, 36.50, -94.62, -89.64),
        UsStateBounds("CA", 32.53, 42.01, -124.48, -114.13),
        UsStateBounds("CO", 36.99, 41.00, -109.06, -102.04),
        UsStateBounds("CT", 40.98, 42.05, -73.73, -71.79),
        UsStateBounds("DE", 38.45, 39.84, -75.79, -75.05),
        UsStateBounds("DC", 38.79, 38.99, -77.12, -76.91),
        UsStateBounds("FL", 24.40, 31.00, -87.63, -80.03),
        UsStateBounds("GA", 30.36, 35.00, -85.61, -80.84),
        UsStateBounds("HI", 18.91, 22.24, -160.25, -154.81),
        UsStateBounds("ID", 41.99, 49.00, -117.24, -111.04),
        UsStateBounds("IL", 36.97, 42.51, -91.51, -87.02),
        UsStateBounds("IN", 37.77, 41.76, -88.10, -84.78),
        UsStateBounds("IA", 40.38, 43.50, -96.64, -90.14),
        UsStateBounds("KS", 36.99, 40.00, -102.05, -94.59),
        UsStateBounds("KY", 36.50, 39.15, -89.57, -81.96),
        UsStateBounds("LA", 28.93, 33.02, -94.04, -88.82),
        UsStateBounds("ME", 42.98, 47.46, -71.08, -66.95),
        UsStateBounds("MD", 37.91, 39.72, -79.49, -75.05),
        UsStateBounds("MA", 41.24, 42.89, -73.51, -69.93),
        UsStateBounds("MI", 41.70, 48.31, -90.42, -82.12),
        UsStateBounds("MN", 43.50, 49.38, -97.24, -89.49),
        UsStateBounds("MS", 30.17, 35.00, -91.66, -88.10),
        UsStateBounds("MO", 35.99, 40.61, -95.77, -89.10),
        UsStateBounds("MT", 44.36, 49.00, -116.05, -104.04),
        UsStateBounds("NE", 40.00, 43.00, -104.05, -95.31),
        UsStateBounds("NV", 35.00, 42.00, -120.00, -114.04),
        UsStateBounds("NH", 42.70, 45.31, -72.56, -70.61),
        UsStateBounds("NJ", 38.93, 41.36, -75.56, -73.89),
        UsStateBounds("NM", 31.33, 37.00, -109.05, -103.00),
        UsStateBounds("NY", 40.50, 45.02, -79.76, -71.86),
        UsStateBounds("NC", 33.84, 36.59, -84.32, -75.46),
        UsStateBounds("ND", 45.94, 49.00, -104.05, -96.55),
        UsStateBounds("OH", 38.40, 42.33, -84.82, -80.52),
        UsStateBounds("OK", 33.62, 37.00, -103.00, -94.43),
        UsStateBounds("OR", 41.99, 46.29, -124.57, -116.46),
        UsStateBounds("PA", 39.72, 42.27, -80.52, -74.69),
        UsStateBounds("PR", 17.88, 18.52, -67.95, -65.22),
        UsStateBounds("RI", 41.15, 42.02, -71.86, -71.12),
        UsStateBounds("SC", 32.03, 35.22, -83.35, -78.54),
        UsStateBounds("SD", 42.48, 45.95, -104.06, -96.44),
        UsStateBounds("TN", 34.98, 36.68, -90.31, -81.65),
        UsStateBounds("TX", 25.84, 36.50, -106.65, -93.51),
        UsStateBounds("UT", 37.00, 42.00, -114.05, -109.04),
        UsStateBounds("VT", 42.73, 45.02, -73.44, -71.46),
        UsStateBounds("VA", 36.54, 39.47, -83.68, -75.24),
        UsStateBounds("WA", 45.54, 49.00, -124.85, -116.92),
        UsStateBounds("WV", 37.20, 40.64, -82.64, -77.72),
        UsStateBounds("WI", 42.49, 47.08, -92.89, -86.25),
        UsStateBounds("WY", 40.99, 45.01, -111.06, -104.05)
    )

    fun isUsRegion(lat: Double, lon: Double): Boolean {
        val inContiguousUs = lat in 23.0..50.5 && lon in -127.0..-65.0
        val inAlaska = lat in 51.0..72.5 && lon in -180.0..-129.0
        val inHawaii = lat in 18.0..23.5 && lon in -161.5..-154.0
        val inPuertoRico = lat in 17.0..19.0 && lon in -68.5..-64.0
        return inContiguousUs || inAlaska || inHawaii || inPuertoRico
    }

    fun isDwdRegion(lat: Double, lon: Double): Boolean {
        return lat in 46.5..55.8 && lon in 5.0..16.0
    }

    fun resolveUsStateCode(lat: Double, lon: Double): String? {
        if (!isUsRegion(lat, lon)) return null
        val matches = usStateBounds.filter { it.contains(lat, lon) }
        val pool = if (matches.isNotEmpty()) matches else usStateBounds
        return pool.minByOrNull { state ->
            val dLat = lat - state.centerLat
            val dLon = lon - state.centerLon
            dLat * dLat + dLon * dLon
        }?.code
    }

    /**
     * Fetches location-specific warnings for the banner & prediction model.
     * Automatically selects US-NWS for US coordinates and DWD for Germany/Europe.
     */
    suspend fun fetchWarnings(lat: Double, lon: Double): List<WeatherWarning> = withContext(Dispatchers.IO) {
        if (isUsRegion(lat, lon)) {
            fetchUsNwsPointWarnings(lat, lon)
        } else {
            fetchDwdPointWarnings(lat, lon)
        }
    }

    /**
     * Fetches warnings with polygon geometries around [centerLat], [centerLon] for real-time map plotting.
     */
    suspend fun fetchMapWarnings(centerLat: Double, centerLon: Double): List<WeatherWarning> = withContext(Dispatchers.IO) {
        when {
            isUsRegion(centerLat, centerLon) -> fetchUsNwsMapWarnings(centerLat, centerLon)
            isDwdRegion(centerLat, centerLon) -> fetchDwdMapWarnings(centerLat, centerLon)
            else -> emptyList()
        }
    }

    private suspend fun fetchDwdPointWarnings(lat: Double, lon: Double): List<WeatherWarning> = coroutineScope {
        val brightSkyDeferred = async { fetchBrightSkyAlerts(lat, lon) }
        val wfsDeferred = async {
            val wfsList = fetchDwdMapWarnings(lat, lon, latDelta = 0.25, lonDelta = 0.35)
            wfsList.filter { warning ->
                warning.polygons.any { ring -> isPointInPolygon(lat, lon, ring) }
            }
        }

        val brightSky = brightSkyDeferred.await()
        val wfsMatches = wfsDeferred.await()

        val merged = LinkedHashMap<String, WeatherWarning>()
        for (w in wfsMatches) {
            val key = "${w.title.uppercase(Locale.ROOT)}|${w.startTime}"
            merged[key] = w
        }
        for (bs in brightSky) {
            val key = "${bs.title.uppercase(Locale.ROOT)}|${bs.startTime}"
            val existing = merged[key]
            merged[key] = if (existing != null) {
                bs.copy(polygons = existing.polygons, customColorRgb = existing.customColorRgb)
            } else {
                bs
            }
        }
        merged.values.sortedByDescending { it.severity.level }
    }

    private fun fetchBrightSkyAlerts(lat: Double, lon: Double): List<WeatherWarning> {
        return try {
            val url = "https://api.brightsky.dev/alerts?lat=$lat&lon=$lon"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseBrightSkyJson(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseBrightSkyJson(jsonBody: String): List<WeatherWarning> {
        val root = try {
            mapAdapter.fromJson(jsonBody)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val locationMap = root["location"] as? Map<*, *>
        val areaName = (locationMap?.get("name") as? String)
            ?: (locationMap?.get("district") as? String)
            ?: "Deutschland"

        val alerts = root["alerts"] as? List<*> ?: return emptyList()
        return alerts.mapNotNull { item ->
            val alert = item as? Map<*, *> ?: return@mapNotNull null
            val title = (alert["event_de"] as? String)?.takeIf { it.isNotBlank() }
                ?: (alert["event_en"] as? String)?.takeIf { it.isNotBlank() }
                ?: (alert["headline_de"] as? String)
                ?: return@mapNotNull null

            val description = (alert["description_de"] as? String)?.takeIf { it.isNotBlank() }
                ?: (alert["description_en"] as? String)
                ?: (alert["headline_de"] as? String)
                ?: ""

            val instruction = (alert["instruction_de"] as? String)?.takeIf { it.isNotBlank() }
                ?: (alert["instruction_en"] as? String)?.takeIf { it.isNotBlank() }

            val severity = WarningSeverity.fromString(alert["severity"] as? String)
            val onsetInstant = parseIsoInstant(alert["onset"] as? String ?: alert["effective"] as? String)
            val expiresInstant = parseIsoInstant(alert["expires"] as? String)

            WeatherWarning(
                id = (alert["alert_id"] as? String) ?: "bs_${alert["id"]}",
                title = title,
                description = description,
                severity = severity,
                area = areaName,
                startTime = formatLocalTime(onsetInstant),
                endTime = formatLocalTime(expiresInstant),
                instruction = instruction,
                onsetInstant = onsetInstant,
                expiresInstant = expiresInstant,
                source = WarningSource.DWD
            )
        }.sortedByDescending { it.severity.level }
    }

    fun fetchDwdMapWarnings(
        centerLat: Double,
        centerLon: Double,
        latDelta: Double = 2.2,
        lonDelta: Double = 3.2
    ): List<WeatherWarning> {
        return try {
            val minLon = (centerLon - lonDelta).coerceIn(-180.0, 180.0)
            val maxLon = (centerLon + lonDelta).coerceIn(-180.0, 180.0)
            val minLat = (centerLat - latDelta).coerceIn(-90.0, 90.0)
            val maxLat = (centerLat + latDelta).coerceIn(-90.0, 90.0)
            val bbox = String.format(
                Locale.US,
                "%.4f,%.4f,%.4f,%.4f,EPSG:4326",
                minLon,
                minLat,
                maxLon,
                maxLat
            )
            val url = "$DWD_WFS_BASE&bbox=$bbox"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NWS_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseDwdWfsGeoJson(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseDwdWfsGeoJson(jsonBody: String): List<WeatherWarning> {
        val root = try {
            mapAdapter.fromJson(jsonBody)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val features = root["features"] as? List<*> ?: return emptyList()
        val grouped = LinkedHashMap<String, WeatherWarning>()

        for (rawFeature in features) {
            val feature = rawFeature as? Map<*, *> ?: continue
            val props = feature["properties"] as? Map<*, *> ?: continue
            val geometry = feature["geometry"] as? Map<*, *>

            val event = (props["EVENT"] as? String)?.takeIf { it.isNotBlank() }
                ?: (props["HEADLINE"] as? String)
                ?: continue
            val areaDesc = (props["AREADESC"] as? String)?.takeIf { it.isNotBlank() } ?: "Warngebiet"
            val severity = WarningSeverity.fromString(props["SEVERITY"] as? String)
            val onsetInstant = parseIsoInstant(props["ONSET"] as? String ?: props["EFFECTIVE"] as? String)
            val expiresInstant = parseIsoInstant(props["EXPIRES"] as? String)
            val polygons = parseGeoJsonPolygons(geometry)
            if (polygons.isEmpty()) continue

            val identifier = (props["IDENTIFIER"] as? String)?.takeIf { it.isNotBlank() }
                ?: "${event}_${areaDesc}_${onsetInstant?.epochSecond ?: 0L}"
            val dedupKey = "${event}|${severity.name}|${areaDesc}|${onsetInstant?.epochSecond ?: 0L}"

            val existing = grouped[dedupKey]
            if (existing != null) {
                grouped[dedupKey] = existing.copy(polygons = existing.polygons + polygons)
            } else {
                grouped[dedupKey] = WeatherWarning(
                    id = identifier,
                    title = event,
                    description = (props["DESCRIPTION"] as? String)
                        ?: (props["HEADLINE"] as? String)
                        ?: event,
                    severity = severity,
                    area = areaDesc,
                    startTime = formatLocalTime(onsetInstant),
                    endTime = formatLocalTime(expiresInstant),
                    instruction = (props["INSTRUCTION"] as? String)?.takeIf { it.isNotBlank() },
                    onsetInstant = onsetInstant,
                    expiresInstant = expiresInstant,
                    source = WarningSource.DWD,
                    polygons = polygons,
                    customColorRgb = parseRgbColor(props["EC_AREA_COLOR"] as? String)
                )
            }
        }

        return grouped.values.sortedByDescending { it.severity.level }
    }

    private fun fetchUsNwsPointWarnings(lat: Double, lon: Double): List<WeatherWarning> {
        return try {
            val url = String.format(Locale.US, "https://api.weather.gov/alerts/active?point=%.4f,%.4f", lat, lon)
            fetchNwsAlertsFromUrl(url)
                .map { it.warning }
                .sortedByDescending { it.severity.level }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchUsNwsMapWarnings(centerLat: Double, centerLon: Double): List<WeatherWarning> = coroutineScope {
        val pointUrl = String.format(Locale.US, "https://api.weather.gov/alerts/active?point=%.4f,%.4f", centerLat, centerLon)
        val pointAlertsDeferred = async(Dispatchers.IO) { fetchNwsAlertsFromUrl(pointUrl) }

        val stateCode = resolveOrFetchNwsStateCode(centerLat, centerLon)
        val stateAlertsDeferred = async(Dispatchers.IO) {
            if (stateCode != null) {
                fetchNwsAlertsFromUrl("https://api.weather.gov/alerts/active?area=$stateCode")
            } else {
                emptyList()
            }
        }

        val pointAlerts = pointAlertsDeferred.await()
        val stateAlerts = stateAlertsDeferred.await()

        val pointAlertIds = pointAlerts.map { it.warning.id }.toSet()
        val combinedById = LinkedHashMap<String, NwsParsedAlert>()
        for (alert in pointAlerts) {
            combinedById[alert.warning.id] = alert
        }
        for (alert in stateAlerts) {
            combinedById.putIfAbsent(alert.warning.id, alert)
        }

        // Resolve uncached zone geometries concurrently (prioritizing local point alerts + highest severity)
        val alertsNeedingZones = combinedById.values
            .filter { it.warning.polygons.isEmpty() && it.affectedZones.isNotEmpty() }
            .sortedWith(
                compareByDescending<NwsParsedAlert> { it.warning.id in pointAlertIds }
                    .thenByDescending { it.warning.severity.level }
            )

        val uncachedZoneUrls = LinkedHashSet<String>()
        for (alert in alertsNeedingZones) {
            for (zoneUrl in alert.affectedZones) {
                if (!zoneGeometryCache.containsKey(zoneUrl)) {
                    uncachedZoneUrls.add(zoneUrl)
                    if (uncachedZoneUrls.size >= 12) break
                }
            }
            if (uncachedZoneUrls.size >= 12) break
        }

        if (uncachedZoneUrls.isNotEmpty()) {
            uncachedZoneUrls.map { zoneUrl ->
                async(Dispatchers.IO) {
                    val polys = fetchNwsZonePolygons(zoneUrl)
                    if (polys.isNotEmpty()) {
                        zoneGeometryCache[zoneUrl] = polys
                    }
                }
            }.awaitAll()
        }

        val resolved = combinedById.values.map { parsed ->
            if (parsed.warning.polygons.isNotEmpty()) {
                parsed.warning
            } else {
                val zonePolygons = parsed.affectedZones
                    .flatMap { zoneGeometryCache[it].orEmpty() }
                parsed.warning.copy(polygons = zonePolygons)
            }
        }

        resolved
            .filter { it.polygons.isNotEmpty() || it.id in pointAlertIds }
            .sortedByDescending { it.severity.level }
    }

    private fun resolveOrFetchNwsStateCode(lat: Double, lon: Double): String? {
        val cacheKey = String.format(Locale.US, "%.2f,%.2f", Math.round(lat * 4.0) / 4.0, Math.round(lon * 4.0) / 4.0)
        pointStateCache[cacheKey]?.let { return it }

        val fetchedState = try {
            val url = String.format(Locale.US, "https://api.weather.gov/points/%.4f,%.4f", lat, lon)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NWS_USER_AGENT)
                .header("Accept", "application/geo+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val root = mapAdapter.fromJson(body) ?: return@use null
                val props = root["properties"] as? Map<*, *>
                val relLoc = props?.get("relativeLocation") as? Map<*, *>
                val relProps = relLoc?.get("properties") as? Map<*, *>
                (relProps?.get("state") as? String)?.takeIf { it.length == 2 }
            }
        } catch (_: Exception) {
            null
        }

        val resolved = fetchedState ?: resolveUsStateCode(lat, lon)
        if (resolved != null) {
            pointStateCache[cacheKey] = resolved
        }
        return resolved
    }

    private fun fetchNwsAlertsFromUrl(url: String): List<NwsParsedAlert> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NWS_USER_AGENT)
                .header("Accept", "application/geo+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseNwsGeoJson(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchNwsZonePolygons(zoneUrl: String): List<List<WarningLatLon>> {
        zoneGeometryCache[zoneUrl]?.let { return it }
        return try {
            val request = Request.Builder()
                .url(zoneUrl)
                .header("User-Agent", NWS_USER_AGENT)
                .header("Accept", "application/geo+json")
                .build()
            zoneClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = mapAdapter.fromJson(body) ?: return emptyList()
                val geometry = root["geometry"] as? Map<*, *>
                parseGeoJsonPolygons(geometry)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseNwsGeoJson(jsonBody: String): List<NwsParsedAlert> {
        val root = try {
            mapAdapter.fromJson(jsonBody)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val features = root["features"] as? List<*> ?: return emptyList()
        return features.mapNotNull { rawFeature ->
            val feature = rawFeature as? Map<*, *> ?: return@mapNotNull null
            val props = feature["properties"] as? Map<*, *> ?: return@mapNotNull null
            val status = props["status"] as? String
            if (status != null && !status.equals("Actual", ignoreCase = true)) {
                return@mapNotNull null
            }

            val event = (props["event"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val areaDesc = (props["areaDesc"] as? String)?.takeIf { it.isNotBlank() } ?: "US Alert Area"
            val severity = WarningSeverity.fromString(props["severity"] as? String)
            val onsetInstant = parseIsoInstant(props["onset"] as? String ?: props["effective"] as? String)
            val expiresInstant = parseIsoInstant(props["ends"] as? String ?: props["expires"] as? String)

            val description = (props["description"] as? String)?.takeIf { it.isNotBlank() }
                ?: (props["headline"] as? String)
                ?: event
            val instruction = (props["instruction"] as? String)?.takeIf { it.isNotBlank() }

            val id = (props["id"] as? String)?.takeIf { it.isNotBlank() }
                ?: (feature["id"] as? String)?.takeIf { it.isNotBlank() }
                ?: "${event}_${areaDesc}_${onsetInstant?.epochSecond ?: 0L}"

            val geometry = feature["geometry"] as? Map<*, *>
            val polygons = parseGeoJsonPolygons(geometry)
            val affectedZones = (props["affectedZones"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?: emptyList()

            NwsParsedAlert(
                warning = WeatherWarning(
                    id = id,
                    title = event,
                    description = description,
                    severity = severity,
                    area = areaDesc,
                    startTime = formatLocalTime(onsetInstant),
                    endTime = formatLocalTime(expiresInstant),
                    instruction = instruction,
                    onsetInstant = onsetInstant,
                    expiresInstant = expiresInstant,
                    source = WarningSource.NWS,
                    polygons = polygons
                ),
                affectedZones = affectedZones
            )
        }
    }

    internal fun parseGeoJsonPolygons(geometry: Map<*, *>?): List<List<WarningLatLon>> {
        if (geometry == null) return emptyList()
        val type = geometry["type"] as? String ?: return emptyList()
        val coordinates = geometry["coordinates"] as? List<*> ?: return emptyList()

        return when (type) {
            "Polygon" -> {
                val outerRing = extractRing(coordinates.firstOrNull() as? List<*>)
                if (outerRing.size >= 3) listOf(simplifyPolygonRing(outerRing)) else emptyList()
            }
            "MultiPolygon" -> {
                coordinates.mapNotNull { polyRaw ->
                    val polyRings = polyRaw as? List<*> ?: return@mapNotNull null
                    val outerRing = extractRing(polyRings.firstOrNull() as? List<*>)
                    if (outerRing.size >= 3) simplifyPolygonRing(outerRing) else null
                }
            }
            else -> emptyList()
        }
    }

    private fun extractRing(rawPoints: List<*>?): List<WarningLatLon> {
        if (rawPoints == null) return emptyList()
        val points = ArrayList<WarningLatLon>(rawPoints.size)
        for (rawPoint in rawPoints) {
            val pair = rawPoint as? List<*> ?: continue
            if (pair.size < 2) continue
            val lon = (pair[0] as? Number)?.toDouble() ?: continue
            val lat = (pair[1] as? Number)?.toDouble() ?: continue
            if (lat.isFinite() && lon.isFinite()) {
                points.add(WarningLatLon(lat = lat, lon = lon))
            }
        }
        return points
    }

    /**
     * Simplifies dense GeoJSON polygon rings so osmdroid renders smoothly at 60fps.
     */
    fun simplifyPolygonRing(
        ring: List<WarningLatLon>,
        maxPoints: Int = 120,
        minDistanceDeg: Double = 0.0025
    ): List<WarningLatLon> {
        if (ring.size <= maxPoints && ring.size <= 12) return ring

        val filtered = ArrayList<WarningLatLon>(minOf(ring.size, maxPoints + 2))
        var lastKept: WarningLatLon? = null
        for (idx in ring.indices) {
            val pt = ring[idx]
            if (idx == 0 || idx == ring.lastIndex) {
                filtered.add(pt)
                lastKept = pt
                continue
            }
            val prev = lastKept ?: pt
            if (abs(pt.lat - prev.lat) >= minDistanceDeg || abs(pt.lon - prev.lon) >= minDistanceDeg) {
                filtered.add(pt)
                lastKept = pt
            }
        }

        val base = if (filtered.size >= 3) filtered else ring
        if (base.size <= maxPoints) return base

        val sampled = ArrayList<WarningLatLon>(maxPoints)
        val step = (base.size - 1).toDouble() / (maxPoints - 1).toDouble()
        for (i in 0 until maxPoints - 1) {
            val index = (i * step).toInt().coerceIn(0, base.lastIndex)
            sampled.add(base[index])
        }
        sampled.add(base.last())
        return sampled
    }

    /**
     * Ray-casting point-in-polygon check.
     */
    fun isPointInPolygon(lat: Double, lon: Double, ring: List<WarningLatLon>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val xi = ring[i].lon
            val yi = ring[i].lat
            val xj = ring[j].lon
            val yj = ring[j].lat

            val intersects = ((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / ((yj - yi).takeIf { it != 0.0 } ?: 1e-12) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    internal fun parseIsoInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value.trim()).toInstant()
        } catch (_: Exception) {
            try {
                Instant.parse(value.trim())
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun formatLocalTime(instant: Instant?): String {
        if (instant == null) return "--:--"
        return try {
            instant.atZone(ZoneId.systemDefault()).format(timeFormatter)
        } catch (_: Exception) {
            "--:--"
        }
    }

    internal fun parseRgbColor(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size != 3) return null
        val r = parts[0].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val g = parts[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val b = parts[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
        return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }
}

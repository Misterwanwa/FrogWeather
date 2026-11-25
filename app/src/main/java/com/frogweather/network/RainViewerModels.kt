package com.frogweather.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class RainViewerRawResponse(
    val version: String = "2.0",
    val generated: Long = 0L,
    val host: String = "",
    val radar: RainViewerRadarRaw? = null,
    val satellite: RainViewerSatelliteRaw? = null
)

data class RainViewerRadarRaw(
    val past: List<RainViewerFrameRaw?>? = emptyList(),
    val nowcast: List<RainViewerFrameRaw?>? = emptyList()
)

data class RainViewerSatelliteRaw(
    val infrared: List<RainViewerFrameRaw?>? = emptyList()
)

data class RainViewerFrameRaw(
    val time: Long = 0L,
    val path: String = ""
)

data class RainViewerFrame(
    val epochMillis: Long,
    val path: String,
    val isNowcast: Boolean
)

data class RainViewerMapsData(
    val version: String,
    val generatedEpochMillis: Long,
    val host: String,
    val radarPast: List<RainViewerFrame>,
    val radarNowcast: List<RainViewerFrame>,
    val satelliteInfrared: List<RainViewerFrame>
) {
    val allRadarFrames: List<RainViewerFrame>
        get() = (radarPast + radarNowcast).sortedBy { it.epochMillis }

    val hasSatelliteInfrared: Boolean
        get() = satelliteInfrared.isNotEmpty()
}

object RainViewerParser {
    const val MAX_ZOOM = 7
    const val DEFAULT_TILE_SIZE = 256
    const val DEFAULT_COLOR_SCHEME = 2
    const val DEFAULT_OPTIONS = "1_1"
    private const val MAX_EPOCH_SECONDS = Long.MAX_VALUE / 1000L

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val responseAdapter by lazy {
        moshi.adapter(RainViewerRawResponse::class.java)
    }

    fun parseJson(json: String): RainViewerMapsData {
        if (json.isBlank()) {
            throw IllegalArgumentException("Empty RainViewer JSON")
        }
        val raw = try {
            responseAdapter.fromJson(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid RainViewer JSON: ${e.message}", e)
        } ?: throw IllegalArgumentException("Empty or invalid RainViewer JSON")
        return parseResponse(raw)
    }

    fun parseResponse(raw: RainViewerRawResponse): RainViewerMapsData {
        val normalizedHost = raw.host.trim().trimEnd('/')
        if (normalizedHost.isEmpty()) {
            throw IllegalArgumentException("RainViewer response missing host")
        }

        val pastFrames = raw.radar?.past.orEmpty()
            .mapNotNull { frame -> frame?.let { toFrameOrNull(it, isNowcast = false) } }
            .sortedBy { it.epochMillis }

        val nowcastFrames = raw.radar?.nowcast.orEmpty()
            .mapNotNull { frame -> frame?.let { toFrameOrNull(it, isNowcast = true) } }
            .sortedBy { it.epochMillis }

        val infraredFrames = raw.satellite?.infrared.orEmpty()
            .mapNotNull { frame -> frame?.let { toFrameOrNull(it, isNowcast = false) } }
            .sortedBy { it.epochMillis }

        return RainViewerMapsData(
            version = raw.version.ifBlank { "2.0" },
            generatedEpochMillis = raw.generated.coerceIn(0L, MAX_EPOCH_SECONDS) * 1000L,
            host = normalizedHost,
            radarPast = pastFrames,
            radarNowcast = nowcastFrames,
            satelliteInfrared = infraredFrames
        )
    }

    fun buildTileUrlTemplate(
        host: String,
        path: String,
        tileSize: Int = DEFAULT_TILE_SIZE,
        colorScheme: Int = DEFAULT_COLOR_SCHEME,
        options: String = DEFAULT_OPTIONS
    ): String {
        val normalizedHost = host.trim().trimEnd('/')
        val cleanPath = path.trim().trim('/')
        val normalizedPath = if (cleanPath.isEmpty()) "" else "/$cleanPath"
        return "$normalizedHost$normalizedPath/$tileSize/{z}/{x}/{y}/$colorScheme/$options.png"
    }

    fun clampZoom(zoom: Int): Int = zoom.coerceIn(0, MAX_ZOOM)

    private fun toFrameOrNull(raw: RainViewerFrameRaw, isNowcast: Boolean): RainViewerFrame? {
        val trimmedPath = raw.path.trim()
        if (raw.time <= 0L || trimmedPath.isEmpty()) return null
        val safeSec = raw.time.coerceAtMost(MAX_EPOCH_SECONDS)
        val normalizedPath = if (trimmedPath.startsWith("/")) trimmedPath else "/$trimmedPath"
        return RainViewerFrame(
            epochMillis = safeSec * 1000L,
            path = normalizedPath.trimEnd('/'),
            isNowcast = isNowcast
        )
    }
}

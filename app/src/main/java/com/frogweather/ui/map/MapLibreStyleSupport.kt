package com.frogweather.ui.map

import android.content.Context
import com.frogweather.service.DwdWarningFetcher
import com.frogweather.service.WeatherWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.util.concurrent.TimeUnit

internal object MapLibreStyleLoader {
    const val LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    private const val CACHE_FILE_NAME = "openfreemap_liberty_v1.json"
    private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    @Volatile
    private var cachedLibertyJson: String? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun loadStyleJson(
        context: Context,
        isGlobe: Boolean,
        preferDarkFallback: Boolean = isGlobe
    ): String {
        val baseJson = fetchOrLoadLibertyJson(context)
        return if (baseJson != null) {
            injectProjection(baseJson, isGlobe)
        } else {
            buildFallbackRasterStyleJson(isGlobe = isGlobe, darkTheme = preferDarkFallback)
        }
    }

    private suspend fun fetchOrLoadLibertyJson(context: Context): String? {
        cachedLibertyJson?.let { return it }

        return withContext(Dispatchers.IO) {
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < CACHE_MAX_AGE_MS) {
                val disk = runCatching { cacheFile.readText() }.getOrNull()
                if (!disk.isNullOrBlank() && disk.trimStart().startsWith("{")) {
                    cachedLibertyJson = disk
                    return@withContext disk
                }
            }

            val remote = runCatching {
                val req = Request.Builder()
                    .url(LIBERTY_STYLE_URL)
                    .header("User-Agent", "${context.packageName}/1.0 (Android; FrogWeather)")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }.getOrNull()

            if (!remote.isNullOrBlank() && remote.trimStart().startsWith("{")) {
                cachedLibertyJson = remote
                runCatching { cacheFile.writeText(remote) }
                return@withContext remote
            }

            // Stale disk cache is better than failing completely if offline
            if (cacheFile.exists()) {
                val stale = runCatching { cacheFile.readText() }.getOrNull()
                if (!stale.isNullOrBlank() && stale.trimStart().startsWith("{")) {
                    cachedLibertyJson = stale
                    return@withContext stale
                }
            }
            null
        }
    }

    internal fun injectProjection(rawJson: String, isGlobe: Boolean): String {
        return try {
            val root = JSONObject(rawJson)
            root.put(
                "projection",
                JSONObject().put("type", if (isGlobe) "globe" else "mercator")
            )
            root.toString()
        } catch (_: Exception) {
            rawJson
        }
    }

    fun buildFallbackRasterStyleJson(
        isGlobe: Boolean = false,
        darkTheme: Boolean = false
    ): String {
        val tiles = if (darkTheme) {
            listOf(
                "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png",
                "https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png",
                "https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png"
            )
        } else {
            listOf(
                "https://a.tile.openstreetmap.de/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.de/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.de/{z}/{x}/{y}.png"
            )
        }
        val attribution = if (darkTheme) {
            "© OpenStreetMap contributors © CARTO"
        } else {
            "© OpenStreetMap contributors"
        }

        val root = JSONObject()
        root.put("version", 8)
        root.put("name", if (darkTheme) "FrogWeather Fallback Dark" else "FrogWeather Fallback OSM")
        root.put("projection", JSONObject().put("type", if (isGlobe) "globe" else "mercator"))

        val sources = JSONObject()
        val rasterSource = JSONObject()
            .put("type", "raster")
            .put("tiles", JSONArray(tiles))
            .put("tileSize", 256)
            .put("maxzoom", 18)
            .put("attribution", attribution)
        sources.put("osm-raster", rasterSource)
        root.put("sources", sources)

        val layers = JSONArray()
        layers.put(
            JSONObject()
                .put("id", "background")
                .put("type", "background")
                .put("paint", JSONObject().put("background-color", if (darkTheme) "#0A111A" else "#1C2733"))
        )
        layers.put(
            JSONObject()
                .put("id", "osm-raster-layer")
                .put("type", "raster")
                .put("source", "osm-raster")
                .put("minzoom", 0)
                .put("maxzoom", 19)
        )
        root.put("layers", layers)
        return root.toString()
    }
}

internal object MapLibreWarningOverlay {
    const val WARNING_SOURCE_ID = "frog-warnings-source"
    const val WARNING_FILL_LAYER_ID = "frog-warnings-fill"
    const val WARNING_LINE_LAYER_ID = "frog-warnings-outline"

    fun applyWarnings(
        style: Style,
        warnings: List<WeatherWarning>,
        showWarnings: Boolean,
        selectedWarningId: String?
    ) {
        val geoJson = if (!showWarnings || warnings.isEmpty()) {
            EMPTY_FEATURE_COLLECTION
        } else {
            buildWarningsGeoJson(warnings, selectedWarningId)
        }

        val existingSource = style.getSourceAs<GeoJsonSource>(WARNING_SOURCE_ID)
        if (existingSource != null) {
            existingSource.setGeoJson(geoJson)
        } else {
            style.addSource(GeoJsonSource(WARNING_SOURCE_ID, geoJson))
        }

        if (style.getLayer(WARNING_FILL_LAYER_ID) == null) {
            val fillLayer = FillLayer(WARNING_FILL_LAYER_ID, WARNING_SOURCE_ID).withProperties(
                PropertyFactory.fillColor(Expression.get("fill_color")),
                PropertyFactory.fillOpacity(Expression.get("fill_opacity"))
            )
            style.addLayer(fillLayer)
        }

        if (style.getLayer(WARNING_LINE_LAYER_ID) == null) {
            val lineLayer = LineLayer(WARNING_LINE_LAYER_ID, WARNING_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Expression.get("fill_color")),
                PropertyFactory.lineWidth(Expression.get("line_width")),
                PropertyFactory.lineOpacity(Expression.get("line_opacity"))
            )
            style.addLayer(lineLayer)
        }
    }

    fun findClickedWarning(
        mapLibreMap: MapLibreMap,
        lat: Double,
        lon: Double,
        warnings: List<WeatherWarning>
    ): WeatherWarning? {
        if (warnings.isEmpty()) return null
        val screenPoint = mapLibreMap.projection.toScreenLocation(
            org.maplibre.android.geometry.LatLng(lat, lon)
        )
        val rendered = runCatching {
            mapLibreMap.queryRenderedFeatures(screenPoint, WARNING_FILL_LAYER_ID)
        }.getOrNull().orEmpty()

        for (feature in rendered) {
            val warningId = feature.getStringProperty("warning_id") ?: continue
            val match = warnings.firstOrNull { it.id == warningId }
            if (match != null) return match
        }

        return warnings.firstOrNull { warning ->
            warning.polygons.any { ring ->
                DwdWarningFetcher.isPointInPolygon(lat, lon, ring)
            }
        }
    }

    private fun buildWarningsGeoJson(
        warnings: List<WeatherWarning>,
        selectedWarningId: String?
    ): String {
        val ordered = warnings.sortedWith(
            compareBy<WeatherWarning> { it.id == selectedWarningId }
                .thenBy { it.severity.level }
        )

        val features = JSONArray()
        for (warning in ordered) {
            if (warning.polygons.isEmpty()) continue
            val isSelected = warning.id == selectedWarningId
            val rawColor = (warning.customColorRgb ?: warning.severity.color).toInt() and 0x00FFFFFF
            val hexColor = String.format(java.util.Locale.US, "#%06X", rawColor)
            val fillOpacity = if (isSelected) 0.50 else 0.30
            val lineWidth = if (isSelected) 3.5 else 2.0
            val lineOpacity = if (isSelected) 1.0 else 0.90

            for (rawRing in warning.polygons) {
                val simplified = DwdWarningFetcher.simplifyPolygonRing(rawRing)
                if (simplified.size < 3) continue
                val ringArray = JSONArray()
                for (pt in simplified) {
                    ringArray.put(JSONArray().put(pt.lon).put(pt.lat))
                }
                val first = simplified.first()
                val last = simplified.last()
                if (first.lat != last.lat || first.lon != last.lon) {
                    ringArray.put(JSONArray().put(first.lon).put(first.lat))
                }

                val geometry = JSONObject()
                    .put("type", "Polygon")
                    .put("coordinates", JSONArray().put(ringArray))

                val properties = JSONObject()
                    .put("warning_id", warning.id)
                    .put("fill_color", hexColor)
                    .put("fill_opacity", fillOpacity)
                    .put("line_width", lineWidth)
                    .put("line_opacity", lineOpacity)

                features.put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("geometry", geometry)
                        .put("properties", properties)
                )
            }
        }

        return JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
            .toString()
    }

    private const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""
}

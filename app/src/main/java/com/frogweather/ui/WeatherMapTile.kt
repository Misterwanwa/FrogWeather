package com.frogweather.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.frogweather.BuildConfig
import com.frogweather.service.DwdWarningFetcher
import com.frogweather.service.WarningSource
import com.frogweather.service.WeatherWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// Available weather map layers from OpenWeatherMap (fast _new tile endpoints)
enum class WeatherMapLayer(
    val displayName: String,
    val layerCode: String,
    val legendUrl: String?
) {
    NONE("Kein Overlay", "", null),
    PRECIPITATION("Niederschlag", "precipitation_new", "https://openweathermap.org/img/legend/precipitation.png"),
    RAIN("Regen", "rain_new", "https://openweathermap.org/img/legend/rain.png"),
    CLOUDS("Wolken", "clouds_new", "https://openweathermap.org/img/legend/clouds.png"),
    TEMP("Temperatur", "temp_new", "https://openweathermap.org/img/legend/temp.png"),
    WIND("Wind", "wind_new", "https://openweathermap.org/img/legend/wind.png"),
    PRESSURE("Luftdruck", "pressure_new", "https://openweathermap.org/img/legend/pressure.png"),
    SNOW("Schnee", "snow_new", "https://openweathermap.org/img/legend/snow.png")
}

private const val WARNING_POLL_INTERVAL_MS = 10_000L

@Volatile
private var isOsmdroidConfigured = false

/**
 * Configures osmdroid synchronously BEFORE any MapView instance is created so disk cache,
 * parallel download threads, and proper User-Agent take effect immediately.
 */
private fun ensureOsmdroidConfigured(context: Context) {
    if (isOsmdroidConfigured) return
    synchronized(Configuration.getInstance()) {
        if (isOsmdroidConfigured) return
        val appContext = context.applicationContext
        val baseDir = File(appContext.cacheDir, "osmdroid").apply { mkdirs() }
        val tileDir = File(baseDir, "tiles").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = "${appContext.packageName}/1.0 (Android; FrogWeather)"
            osmdroidBasePath = baseDir
            osmdroidTileCache = tileDir
            cacheMapTileCount = 96.toShort()
            cacheMapTileOvershoot = 24.toShort()
            tileDownloadThreads = 8.toShort()
            tileFileSystemThreads = 8.toShort()
            expirationExtendedDuration = 7L * 24L * 60L * 60L * 1000L
        }
        isOsmdroidConfigured = true
    }
}

private fun createFastBaseTileSource(): XYTileSource {
    return XYTileSource(
        "OSM_DE_FAST",
        0,
        18,
        256,
        ".png",
        arrayOf(
            "https://a.tile.openstreetmap.de/",
            "https://b.tile.openstreetmap.de/",
            "https://c.tile.openstreetmap.de/"
        ),
        "© OpenStreetMap contributors"
    )
}

private class MapOverlayController(val mapView: MapView) {
    var currentLayer: WeatherMapLayer? = null
    var weatherOverlay: TilesOverlay? = null
    var lastCenteredLat: Double? = null
    var lastCenteredLon: Double? = null
    var warningOverlays: MutableList<Polygon> = mutableListOf()
    var lastWarningsSignature: String? = null
    var lastFocusToken: Int = 0
}

@Composable
fun WeatherMapTile(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    ensureOsmdroidConfigured(context)

    var selectedLayer by remember { mutableStateOf(WeatherMapLayer.PRECIPITATION) }
    var showWarnings by remember { mutableStateOf(true) }
    var mapCenterLat by remember { mutableDoubleStateOf(latitude) }
    var mapCenterLon by remember { mutableDoubleStateOf(longitude) }
    var mapWarnings by remember { mutableStateOf<List<WeatherWarning>>(emptyList()) }
    var selectedWarning by remember { mutableStateOf<WeatherWarning?>(null) }
    var isLoadingWarnings by remember { mutableStateOf(false) }
    var lastUpdatedText by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var focusTarget by remember { mutableStateOf<GeoPoint?>(null) }
    var focusToken by remember { mutableIntStateOf(0) }

    // Sync map polling center when user picks a new city / GPS location
    LaunchedEffect(latitude, longitude) {
        mapCenterLat = latitude
        mapCenterLon = longitude
        selectedWarning = null
        focusTarget = null
    }

    val isUs = DwdWarningFetcher.isUsRegion(mapCenterLat, mapCenterLon)
    val isDwd = DwdWarningFetcher.isDwdRegion(mapCenterLat, mapCenterLon)
    val providerLabel = when {
        isUs -> WarningSource.NWS.badgeText
        isDwd -> WarningSource.DWD.badgeText
        else -> "GLOBAL"
    }

    // Real-time warning polling (immediate fetch + 10s interval)
    LaunchedEffect(mapCenterLat, mapCenterLon, showWarnings, refreshTick) {
        if (!showWarnings) {
            isLoadingWarnings = false
            return@LaunchedEffect
        }
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        while (isActive) {
            isLoadingWarnings = mapWarnings.isEmpty()
            val fetched = DwdWarningFetcher.fetchMapWarnings(mapCenterLat, mapCenterLon)
            mapWarnings = fetched
            selectedWarning = selectedWarning?.let { current ->
                fetched.firstOrNull { it.id == current.id }
            }
            lastUpdatedText = timeFmt.format(Date())
            isLoadingWarnings = false
            delay(WARNING_POLL_INTERVAL_MS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1C2733), shape = RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // Header with live indicator and layer selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WETTERKARTE",
                    color = Color.White,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { refreshTick++ }
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (showWarnings) Color(0xFF4CAF50) else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = buildString {
                            append("LIVE • ")
                            append(providerLabel)
                            lastUpdatedText?.let { append(" • $it") }
                        },
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Warnungen aktualisieren",
                        tint = Color(0xFF4FC3F7),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Layer selector dropdown
            LayerSelector(
                selectedLayer = selectedLayer,
                onLayerSelected = { selectedLayer = it }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Warning overlay toggle chip & status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val chipBg = if (showWarnings) Color(0xFFEF5350).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f)
            val chipBorder = if (showWarnings) Color(0xFFEF5350).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.2f)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(chipBg)
                    .border(1.dp, chipBorder, RoundedCornerShape(10.dp))
                    .clickable {
                        showWarnings = !showWarnings
                        if (!showWarnings) selectedWarning = null
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (showWarnings) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (showWarnings) {
                        "Warnungen (${mapWarnings.size})"
                    } else {
                        "Warnungen aus"
                    },
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            if (isLoadingWarnings) {
                Text(
                    text = "Lade Warngebiete...",
                    color = Color(0xFF4FC3F7),
                    fontSize = 11.sp
                )
            } else if (showWarnings && mapWarnings.isEmpty()) {
                Text(
                    text = if (isUs || isDwd) "Keine aktiven Warnungen" else "Warngebiete: DE & USA",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Map with legend overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            WeatherMap(
                latitude = latitude,
                longitude = longitude,
                layer = selectedLayer,
                warnings = mapWarnings,
                showWarnings = showWarnings,
                selectedWarningId = selectedWarning?.id,
                focusTarget = focusTarget,
                focusToken = focusToken,
                onMapCenterChanged = { newLat, newLon ->
                    if (abs(newLat - mapCenterLat) > 0.5 || abs(newLon - mapCenterLon) > 0.5) {
                        mapCenterLat = newLat
                        mapCenterLon = newLon
                    }
                },
                onWarningClicked = { warning ->
                    selectedWarning = if (selectedWarning?.id == warning.id) null else warning
                }
            )

            selectedLayer.legendUrl?.let { url ->
                LegendImage(
                    legendUrl = url,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }
        }

        // Quick-select warning chips below map
        if (showWarnings && mapWarnings.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                mapWarnings.take(12).forEach { warning ->
                    val isSelected = selectedWarning?.id == warning.id
                    val accentColor = Color(warning.customColorRgb ?: warning.severity.color)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) accentColor.copy(alpha = 0.28f)
                                else Color.White.copy(alpha = 0.08f)
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else Color.White.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                if (isSelected) {
                                    selectedWarning = null
                                } else {
                                    selectedWarning = warning
                                    computePolygonCentroid(warning)?.let { centroid ->
                                        focusTarget = centroid
                                        focusToken++
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${warning.title} • ${warning.area}",
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
            }
        }

        // Interactive detail card for tapped warning polygon/chip
        selectedWarning?.let { warning ->
            Spacer(modifier = Modifier.height(10.dp))
            SelectedWarningCard(
                warning = warning,
                onDismiss = { selectedWarning = null }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Description text
        Text(
            text = when (selectedLayer) {
                WeatherMapLayer.PRECIPITATION -> "Niederschlag in mm/h"
                WeatherMapLayer.RAIN -> "Regen in mm/h"
                WeatherMapLayer.CLOUDS -> "Wolkenbedeckung in %"
                WeatherMapLayer.TEMP -> "Temperatur in °C"
                WeatherMapLayer.WIND -> "Windgeschwindigkeit in m/s"
                WeatherMapLayer.PRESSURE -> "Luftdruck in hPa"
                WeatherMapLayer.SNOW -> "Schneefall in mm/h"
                WeatherMapLayer.NONE -> "OpenStreetMap Basis"
            },
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SelectedWarningCard(
    warning: WeatherWarning,
    onDismiss: () -> Unit
) {
    val accent = Color(warning.customColorRgb ?: warning.severity.color)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF233242))
            .border(1.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.25f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${warning.source.badgeText} • ${warning.severity.germanName}",
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = warning.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Schließen",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${warning.area} • ${warning.startTime} - ${warning.endTime}",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp
        )
        if (warning.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = warning.description,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
        warning.instruction?.takeIf { it.isNotBlank() }?.let { instruction ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Hinweis: $instruction",
                color = Color(0xFFFFCC80),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LegendImage(legendUrl: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(4.dp)
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(legendUrl)
                    .crossfade(true)
                    .build()
            ),
            contentDescription = "Legende",
            modifier = Modifier
                .width(60.dp)
                .wrapContentHeight()
        )
    }
}

@Composable
private fun LayerSelector(
    selectedLayer: WeatherMapLayer,
    onLayerSelected: (WeatherMapLayer) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedLayer.displayName,
                color = Color(0xFF4FC3F7),
                fontSize = 13.sp
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = Color(0xFF4FC3F7),
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF2A3A4A))
        ) {
            WeatherMapLayer.entries.forEach { layer ->
                DropdownMenuItem(
                    onClick = {
                        onLayerSelected(layer)
                        expanded = false
                    }
                ) {
                    Text(
                        text = layer.displayName,
                        color = if (layer == selectedLayer) Color(0xFF4FC3F7) else Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun WeatherMap(
    latitude: Double,
    longitude: Double,
    layer: WeatherMapLayer,
    warnings: List<WeatherWarning>,
    showWarnings: Boolean,
    selectedWarningId: String?,
    focusTarget: GeoPoint?,
    focusToken: Int,
    onMapCenterChanged: (Double, Double) -> Unit,
    onWarningClicked: (WeatherWarning) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnMapCenterChanged by rememberUpdatedState(onMapCenterChanged)
    val latestOnWarningClicked by rememberUpdatedState(onWarningClicked)

    val mapView = remember {
        ensureOsmdroidConfigured(context)
        MapView(context).apply {
            setTileSource(createFastBaseTileSource())
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            minZoomLevel = 3.0
            maxZoomLevel = 16.0
            controller.setZoom(7.5)
            controller.setCenter(GeoPoint(latitude, longitude))

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }

            addMapListener(
                DelayedMapListener(
                    object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            val center = mapCenter
                            latestOnMapCenterChanged(center.latitude, center.longitude)
                            return false
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            val center = mapCenter
                            latestOnMapCenterChanged(center.latitude, center.longitude)
                            return false
                        }
                    },
                    450L
                )
            )

            tag = MapOverlayController(this)
            onResume()
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        update = { view ->
            val controller = (view.tag as? MapOverlayController)
                ?: MapOverlayController(view).also { view.tag = it }

            var needsInvalidate = false

            if (controller.lastCenteredLat != latitude || controller.lastCenteredLon != longitude) {
                view.controller.setCenter(GeoPoint(latitude, longitude))
                controller.lastCenteredLat = latitude
                controller.lastCenteredLon = longitude
                needsInvalidate = true
            }

            if (focusTarget != null && controller.lastFocusToken != focusToken) {
                view.controller.animateTo(focusTarget)
                controller.lastFocusToken = focusToken
            }

            if (controller.currentLayer != layer) {
                updateWeatherOverlay(controller, layer)
                needsInvalidate = true
            }

            val signature = buildWarningsSignature(warnings, showWarnings, selectedWarningId)
            if (controller.lastWarningsSignature != signature) {
                updateWarningPolygons(
                    controller = controller,
                    warnings = warnings,
                    showWarnings = showWarnings,
                    selectedWarningId = selectedWarningId,
                    onWarningClicked = { latestOnWarningClicked(it) }
                )
                controller.lastWarningsSignature = signature
                needsInvalidate = true
            }

            if (needsInvalidate) {
                view.invalidate()
            }
        }
    )
}

private fun updateWeatherOverlay(controller: MapOverlayController, layer: WeatherMapLayer) {
    val mapView = controller.mapView
    controller.weatherOverlay?.let { oldOverlay ->
        mapView.overlays.remove(oldOverlay)
        oldOverlay.onDetach(mapView)
        controller.weatherOverlay = null
    }

    if (layer != WeatherMapLayer.NONE) {
        val weatherTileSource = object : OnlineTileSourceBase(
            "OpenWeatherMap_${layer.name}",
            0,
            16,
            256,
            ".png",
            arrayOf("https://tile.openweathermap.org/map/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val z = MapTileIndex.getZoom(pMapTileIndex)
                return "https://tile.openweathermap.org/map/${layer.layerCode}/$z/$x/$y.png?appid=${BuildConfig.OPENWEATHER_API_KEY}"
            }
        }

        val tileProvider = MapTileProviderBasic(mapView.context, weatherTileSource)
        val tilesOverlay = TilesOverlay(tileProvider, mapView.context).apply {
            loadingBackgroundColor = android.graphics.Color.TRANSPARENT
            loadingLineColor = android.graphics.Color.TRANSPARENT
            setColorFilter(null)
        }

        // Insert at index 0 so warning polygons stay rendered on top of the weather overlay
        mapView.overlays.add(0, tilesOverlay)
        controller.weatherOverlay = tilesOverlay
    }
    controller.currentLayer = layer
}

private fun updateWarningPolygons(
    controller: MapOverlayController,
    warnings: List<WeatherWarning>,
    showWarnings: Boolean,
    selectedWarningId: String?,
    onWarningClicked: (WeatherWarning) -> Unit
) {
    val mapView = controller.mapView
    controller.warningOverlays.forEach { poly ->
        mapView.overlays.remove(poly)
        poly.onDetach(mapView)
    }
    controller.warningOverlays.clear()

    if (!showWarnings || warnings.isEmpty()) return

    val orderedWarnings = warnings.sortedWith(
        compareBy<WeatherWarning> { it.id == selectedWarningId }
            .thenBy { it.severity.level }
    )

    for (warning in orderedWarnings) {
        if (warning.polygons.isEmpty()) continue
        val isSelected = warning.id == selectedWarningId
        val baseRgb = (warning.customColorRgb ?: warning.severity.color).toInt() and 0x00FFFFFF
        val fillColor = ((if (isSelected) 0x80 else 0x4D) shl 24) or baseRgb
        val strokeColor = ((if (isSelected) 0xFF else 0xF0) shl 24) or baseRgb
        val strokeWidth = if (isSelected) 7f else 4f

        for (ring in warning.polygons) {
            if (ring.size < 3) continue
            val polygon = Polygon(mapView).apply {
                setPoints(ring.map { GeoPoint(it.lat, it.lon) })
                setFillColor(fillColor)
                setStrokeColor(strokeColor)
                setStrokeWidth(strokeWidth)
                title = warning.title
                setOnClickListener { _, _, _ ->
                    onWarningClicked(warning)
                    true
                }
            }
            mapView.overlays.add(polygon)
            controller.warningOverlays.add(polygon)
        }
    }
}

private fun buildWarningsSignature(
    warnings: List<WeatherWarning>,
    showWarnings: Boolean,
    selectedWarningId: String?
): String {
    if (!showWarnings) return "HIDDEN"
    return buildString {
        append(selectedWarningId.orEmpty())
        append('|')
        warnings.forEach { w ->
            append(w.id)
            append(':')
            append(w.polygons.size)
            append(';')
        }
    }
}

private fun computePolygonCentroid(warning: WeatherWarning): GeoPoint? {
    val ring = warning.polygons.firstOrNull { it.isNotEmpty() } ?: return null
    var sumLat = 0.0
    var sumLon = 0.0
    for (pt in ring) {
        sumLat += pt.lat
        sumLon += pt.lon
    }
    return GeoPoint(sumLat / ring.size, sumLon / ring.size)
}

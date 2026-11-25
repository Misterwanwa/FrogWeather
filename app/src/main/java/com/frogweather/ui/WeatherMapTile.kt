package com.frogweather.ui

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.frogweather.map.MapLayerDataProvider
import com.frogweather.service.DwdWarningFetcher
import com.frogweather.service.WarningSource
import com.frogweather.service.WeatherWarning
import com.frogweather.ui.map.MapLibreStyleLoader
import com.frogweather.ui.map.MapLibreWarningOverlay
import com.frogweather.ui.theme.FrogTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

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

data class LocalBoundingBox(
    val latNorth: Double,
    val lonEast: Double,
    val latSouth: Double,
    val lonWest: Double
)

private const val WARNING_POLL_INTERVAL_MS = 10_000L
private const val OVERVIEW_ZOOM = 6.2
private const val LOCAL_DIAMETER_KM = 15.0
private const val COMPACT_WEATHER_SOURCE_ID = "frog-compact-weather-source"
private const val COMPACT_WEATHER_LAYER_ID = "frog-compact-weather-layer"

internal fun createLocalBoundingBox(
    lat: Double,
    lon: Double,
    diameterKm: Double = LOCAL_DIAMETER_KM
): LocalBoundingBox {
    val radiusKm = diameterKm / 2.0
    val deltaLat = radiusKm / 111.32
    val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
    val deltaLon = radiusKm / (111.32 * cosLat)
    val north = (lat + deltaLat).coerceIn(-85.0, 85.0)
    val south = (lat - deltaLat).coerceIn(-85.0, 85.0)
    val east = (lon + deltaLon).coerceIn(-180.0, 180.0)
    val west = (lon - deltaLon).coerceIn(-180.0, 180.0)
    return LocalBoundingBox(
        latNorth = north,
        lonEast = east,
        latSouth = south,
        lonWest = west
    )
}

private class CompactMapController {
    var mapLibreMap: MapLibreMap? = null
    var styleReady: Boolean = false
    var loadedStyleJson: String? = null
    var libertyApplied: Boolean = false
    var currentLayer: WeatherMapLayer? = null
    var lastCenteredLat: Double? = null
    var lastCenteredLon: Double? = null
    var lastWarningsSignature: String? = null
    var lastFocusToken: Int = 0
    var lastLocalizeToken: Int = 0
    var latestWarnings: List<WeatherWarning> = emptyList()
    var latestShowWarnings: Boolean = true
    var latestSelectedWarningId: String? = null
    var latestLayer: WeatherMapLayer = WeatherMapLayer.PRECIPITATION
}

@Composable
fun WeatherMapTile(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
    expandMapToFill: Boolean = false,
    onOpenFullMap: (() -> Unit)? = null
) {
    val palette = FrogTheme.palette

    var selectedLayer by remember { mutableStateOf(WeatherMapLayer.PRECIPITATION) }
    var showWarnings by remember { mutableStateOf(true) }
    var mapCenterLat by remember { mutableDoubleStateOf(latitude) }
    var mapCenterLon by remember { mutableDoubleStateOf(longitude) }
    var mapWarnings by remember { mutableStateOf<List<WeatherWarning>>(emptyList()) }
    var selectedWarning by remember { mutableStateOf<WeatherWarning?>(null) }
    var isLoadingWarnings by remember { mutableStateOf(false) }
    var lastUpdatedText by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var focusTarget by remember { mutableStateOf<LatLng?>(null) }
    var focusToken by remember { mutableIntStateOf(0) }
    var isLocalizedView by remember { mutableStateOf(false) }
    var localizeToLocalMode by remember { mutableStateOf(true) }
    var localizeToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(latitude, longitude) {
        mapCenterLat = latitude
        mapCenterLon = longitude
        selectedWarning = null
        focusTarget = null
        isLocalizedView = false
    }

    val isUs = DwdWarningFetcher.isUsRegion(mapCenterLat, mapCenterLon)
    val isDwd = DwdWarningFetcher.isDwdRegion(mapCenterLat, mapCenterLon)
    val providerLabel = when {
        isUs -> WarningSource.NWS.badgeText
        isDwd -> WarningSource.DWD.badgeText
        else -> "GLOBAL"
    }

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
        modifier = (if (expandMapToFill) modifier.fillMaxSize() else modifier.fillMaxWidth())
            .background(palette.tileSurface, shape = RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WETTERKARTE",
                    color = palette.textPrimary,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold
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
                            .background(if (showWarnings) Color(0xFF4CAF50) else palette.textSecondary)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = buildString {
                            append("LIVE • ")
                            append(providerLabel)
                            lastUpdatedText?.let { append(" • $it") }
                        },
                        color = palette.textSecondary,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Warnungen aktualisieren",
                        tint = palette.accent,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LayerSelector(
                    selectedLayer = selectedLayer,
                    onLayerSelected = { selectedLayer = it }
                )
                if (onOpenFullMap != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.tileSurfaceElevated)
                            .clickable(onClick = onOpenFullMap),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Vollbildkarte & 3D-Globus öffnen",
                            tint = palette.accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val chipBg = if (showWarnings) Color(0xFFEF5350).copy(alpha = 0.2f) else palette.tileSurfaceElevated
            val chipBorder = if (showWarnings) Color(0xFFEF5350).copy(alpha = 0.7f) else palette.dividerColor
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
                    tint = if (showWarnings) Color(0xFFFFB74D) else palette.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (showWarnings) {
                        "Warnungen (${mapWarnings.size})"
                    } else {
                        "Warnungen aus"
                    },
                    color = palette.textPrimary,
                    fontSize = 12.sp
                )
            }

            if (isLoadingWarnings) {
                Text(
                    text = "Lade Warngebiete...",
                    color = palette.accent,
                    fontSize = 11.sp
                )
            } else if (showWarnings && mapWarnings.isEmpty()) {
                Text(
                    text = if (isUs || isDwd) "Keine aktiven Warnungen" else "Warngebiete: DE & USA",
                    color = palette.textSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = (if (expandMapToFill) {
                Modifier.fillMaxWidth().weight(1f)
            } else {
                Modifier.fillMaxWidth().aspectRatio(1f)
            }).clip(RoundedCornerShape(12.dp))
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
                localizeToLocalMode = localizeToLocalMode,
                localizeToken = localizeToken,
                onMapCenterChanged = { newLat, newLon ->
                    if (abs(newLat - mapCenterLat) > 0.5 || abs(newLon - mapCenterLon) > 0.5) {
                        mapCenterLat = newLat
                        mapCenterLon = newLon
                    }
                    if (isLocalizedView && (abs(newLat - latitude) > 0.05 || abs(newLon - longitude) > 0.05)) {
                        isLocalizedView = false
                    }
                },
                onWarningClicked = { warning ->
                    selectedWarning = if (selectedWarning?.id == warning.id) null else warning
                }
            )

            if (MapLayerDataProvider.isValidOwmKey(BuildConfig.OPENWEATHER_API_KEY)) {
                selectedLayer.legendUrl?.let { url ->
                    LegendImage(
                        legendUrl = url,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (isLocalizedView) palette.accent
                        else palette.tileSurface.copy(alpha = 0.94f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isLocalizedView) palette.screenBackground.copy(alpha = 0.85f) else palette.accent.copy(alpha = 0.7f),
                        shape = CircleShape
                    )
                    .clickable {
                        val shouldZoomLocal = !isLocalizedView
                        isLocalizedView = shouldZoomLocal
                        localizeToLocalMode = shouldZoomLocal
                        mapCenterLat = latitude
                        mapCenterLon = longitude
                        localizeToken++
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = if (isLocalizedView) "Zur Übersichtskarte" else "Auf 15-km-Umkreis lokalisieren",
                    tint = if (isLocalizedView) palette.screenBackground else palette.accent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

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
                                else palette.tileSurfaceElevated
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else palette.dividerColor,
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
                            color = palette.textPrimary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
            }
        }

        selectedWarning?.let { warning ->
            Spacer(modifier = Modifier.height(10.dp))
            SelectedWarningCard(
                warning = warning,
                onDismiss = { selectedWarning = null }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when (selectedLayer) {
                WeatherMapLayer.PRECIPITATION -> "Niederschlag in mm/h"
                WeatherMapLayer.RAIN -> "Regen in mm/h"
                WeatherMapLayer.CLOUDS -> "Wolkenbedeckung in %"
                WeatherMapLayer.TEMP -> "Temperatur in °C"
                WeatherMapLayer.WIND -> "Windgeschwindigkeit in m/s"
                WeatherMapLayer.PRESSURE -> "Luftdruck in hPa"
                WeatherMapLayer.SNOW -> "Schneefall in mm/h"
                WeatherMapLayer.NONE -> "MapLibre Basiskarte"
            },
            color = palette.textSecondary,
            fontSize = 12.sp
        )
    }
}

@Composable
internal fun SelectedWarningCard(
    warning: WeatherWarning,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = FrogTheme.palette
    val accent = Color(warning.customColorRgb ?: warning.severity.color)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.tileSurfaceElevated)
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
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.tileSurface)
                        .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${warning.source.badgeText} • ${warning.severity.germanName}",
                        color = palette.textPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = warning.title,
                    color = palette.textPrimary,
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
                    tint = palette.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${warning.area} • ${warning.startTime} - ${warning.endTime}",
            color = palette.textSecondary,
            fontSize = 11.sp
        )
        if (warning.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = warning.description,
                color = palette.textPrimary,
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
    val palette = FrogTheme.palette
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(palette.tileSurfaceElevated)
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedLayer.displayName,
                color = palette.accent,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(palette.tileSurfaceElevated)
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
                        color = if (layer == selectedLayer) palette.accent else palette.textPrimary,
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
    focusTarget: LatLng?,
    focusToken: Int,
    localizeToLocalMode: Boolean,
    localizeToken: Int,
    onMapCenterChanged: (Double, Double) -> Unit,
    onWarningClicked: (WeatherWarning) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnMapCenterChanged by rememberUpdatedState(onMapCenterChanged)
    val latestOnWarningClicked by rememberUpdatedState(onWarningClicked)
    val controller = remember { CompactMapController() }

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
        MapView(context, options).apply {
            onCreate(null)
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            getMapAsync { mapLibreMap ->
                controller.mapLibreMap = mapLibreMap
                mapLibreMap.uiSettings.isAttributionEnabled = false
                mapLibreMap.uiSettings.isLogoEnabled = false
                mapLibreMap.setMinPitchPreference(0.0)
                mapLibreMap.setMaxPitchPreference(45.0)
                mapLibreMap.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(latitude, longitude))
                            .zoom(OVERVIEW_ZOOM)
                            .tilt(0.0)
                            .bearing(0.0)
                            .build()
                    )
                )
                controller.lastCenteredLat = latitude
                controller.lastCenteredLon = longitude

                mapLibreMap.addOnCameraIdleListener {
                    val target = mapLibreMap.cameraPosition.target ?: return@addOnCameraIdleListener
                    latestOnMapCenterChanged(target.latitude, target.longitude)
                }

                mapLibreMap.addOnMapClickListener { point ->
                    val warningsList = controller.latestWarnings
                    if (!controller.latestShowWarnings || warningsList.isEmpty()) {
                        return@addOnMapClickListener false
                    }
                    val match = MapLibreWarningOverlay.findClickedWarning(
                        mapLibreMap = mapLibreMap,
                        lat = point.latitude,
                        lon = point.longitude,
                        warnings = warningsList
                    )
                    if (match != null) {
                        latestOnWarningClicked(match)
                        true
                    } else {
                        false
                    }
                }

                val initialStyleJson = controller.loadedStyleJson
                if (initialStyleJson != null) {
                    controller.libertyApplied = true
                    mapLibreMap.setStyle(Style.Builder().fromJson(initialStyleJson)) { style ->
                        controller.styleReady = true
                        applyCompactWeatherLayer(style, controller.latestLayer)
                        MapLibreWarningOverlay.applyWarnings(
                            style = style,
                            warnings = controller.latestWarnings,
                            showWarnings = controller.latestShowWarnings,
                            selectedWarningId = controller.latestSelectedWarningId
                        )
                    }
                } else {
                    val fallbackStyleJson = MapLibreStyleLoader.buildFallbackRasterStyleJson(
                        isGlobe = false,
                        darkTheme = false
                    )
                    mapLibreMap.setStyle(Style.Builder().fromJson(fallbackStyleJson)) { style ->
                        controller.styleReady = true
                        applyCompactWeatherLayer(style, controller.latestLayer)
                        MapLibreWarningOverlay.applyWarnings(
                            style = style,
                            warnings = controller.latestWarnings,
                            showWarnings = controller.latestShowWarnings,
                            selectedWarningId = controller.latestSelectedWarningId
                        )
                    }
                }
            }
        }
    }

    // Upgrade base style to OpenFreeMap Liberty if reachable
    LaunchedEffect(mapView) {
        val styleJson = MapLibreStyleLoader.loadStyleJson(
            context = context,
            isGlobe = false,
            preferDarkFallback = false
        )
        controller.loadedStyleJson = styleJson
        var map = controller.mapLibreMap
        var attempts = 0
        while (map == null && attempts < 40) {
            delay(50)
            map = controller.mapLibreMap
            attempts++
        }
        val activeMap = map ?: return@LaunchedEffect
        if (controller.libertyApplied) return@LaunchedEffect
        controller.libertyApplied = true
        activeMap.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            controller.styleReady = true
            applyCompactWeatherLayer(style, controller.latestLayer)
            MapLibreWarningOverlay.applyWarnings(
                style = style,
                warnings = controller.latestWarnings,
                showWarnings = controller.latestShowWarnings,
                selectedWarningId = controller.latestSelectedWarningId
            )
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var isDestroyed = false
        val lowMemoryCallback = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !isDestroyed) {
                    mapView.onLowMemory()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                if (!isDestroyed) mapView.onLowMemory()
            }
        }
        context.applicationContext.registerComponentCallbacks(lowMemoryCallback)

        val observer = LifecycleEventObserver { _, event ->
            if (isDestroyed) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    isDestroyed = true
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mapView.onStart()
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(lowMemoryCallback)
            if (!isDestroyed) {
                isDestroyed = true
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    AndroidView(
        factory = { mapView },
        update = { view ->
            controller.latestLayer = layer
            controller.latestWarnings = warnings
            controller.latestShowWarnings = showWarnings
            controller.latestSelectedWarningId = selectedWarningId

            val map = controller.mapLibreMap ?: return@AndroidView

            if (controller.lastCenteredLat != latitude || controller.lastCenteredLon != longitude) {
                val isCitySwitch = controller.lastCenteredLat != null
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(latitude, longitude))
                            .zoom(if (isCitySwitch) OVERVIEW_ZOOM else map.cameraPosition.zoom)
                            .tilt(0.0)
                            .build()
                    )
                )
                controller.lastCenteredLat = latitude
                controller.lastCenteredLon = longitude
            }

            if (controller.lastLocalizeToken != localizeToken) {
                controller.lastLocalizeToken = localizeToken
                if (localizeToLocalMode) {
                    val box = createLocalBoundingBox(latitude, longitude, LOCAL_DIAMETER_KM)
                    val bounds = LatLngBounds.from(box.latNorth, box.lonEast, box.latSouth, box.lonWest)
                    val paddingPx = (24 * view.resources.displayMetrics.density).toInt()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx), 600)
                } else {
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(latitude, longitude))
                                .zoom(OVERVIEW_ZOOM)
                                .tilt(0.0)
                                .build()
                        ),
                        600
                    )
                }
            }

            if (focusTarget != null && controller.lastFocusToken != focusToken) {
                controller.lastFocusToken = focusToken
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(focusTarget, 8.5),
                    500
                )
            }

            val style = map.style
            if (style != null && style.isFullyLoaded && controller.styleReady) {
                if (controller.currentLayer != layer) {
                    applyCompactWeatherLayer(style, layer)
                    controller.currentLayer = layer
                }
                val signature = buildWarningsSignature(warnings, showWarnings, selectedWarningId)
                if (controller.lastWarningsSignature != signature) {
                    MapLibreWarningOverlay.applyWarnings(
                        style = style,
                        warnings = warnings,
                        showWarnings = showWarnings,
                        selectedWarningId = selectedWarningId
                    )
                    controller.lastWarningsSignature = signature
                }
            }
        }
    )
}

private fun applyCompactWeatherLayer(style: Style, layer: WeatherMapLayer) {
    style.removeLayer(COMPACT_WEATHER_LAYER_ID)
    style.removeSource(COMPACT_WEATHER_SOURCE_ID)

    if (layer == WeatherMapLayer.NONE) return
    val apiKey = BuildConfig.OPENWEATHER_API_KEY
    if (!MapLayerDataProvider.isValidOwmKey(apiKey)) return

    val tileUrl = "https://tile.openweathermap.org/map/${layer.layerCode}/{z}/{x}/{y}.png?appid=${apiKey.trim()}"
    val tileSet = TileSet("2.2.0", tileUrl).apply {
        minZoom = 0f
        maxZoom = 12f
    }
    style.addSource(RasterSource(COMPACT_WEATHER_SOURCE_ID, tileSet, 256))
    val rasterLayer = RasterLayer(COMPACT_WEATHER_LAYER_ID, COMPACT_WEATHER_SOURCE_ID).withProperties(
        PropertyFactory.rasterOpacity(0.65f)
    )
    if (style.getLayer(MapLibreWarningOverlay.WARNING_FILL_LAYER_ID) != null) {
        style.addLayerBelow(rasterLayer, MapLibreWarningOverlay.WARNING_FILL_LAYER_ID)
    } else {
        style.addLayer(rasterLayer)
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

private fun computePolygonCentroid(warning: WeatherWarning): LatLng? {
    val ring = warning.polygons.firstOrNull { it.isNotEmpty() } ?: return null
    var sumLat = 0.0
    var sumLon = 0.0
    for (pt in ring) {
        sumLat += pt.lat
        sumLon += pt.lon
    }
    return LatLng(sumLat / ring.size, sumLon / ring.size)
}

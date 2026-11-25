package com.frogweather.ui.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frogweather.BuildConfig
import com.frogweather.map.GeoBounds
import com.frogweather.map.MapForecastModel
import com.frogweather.map.MapLayerData
import com.frogweather.map.MapLayerDataProvider
import com.frogweather.map.MapState
import com.frogweather.map.MapViewMode
import com.frogweather.map.MapWeatherLayer
import com.frogweather.map.PressureLevel
import com.frogweather.map.ResolvedLayerOverlay
import com.frogweather.map.ScalarGridField
import com.frogweather.map.TimelineFrameSource
import com.frogweather.map.WindGridField
import com.frogweather.service.DwdWarningFetcher
import com.frogweather.service.WarningSource
import com.frogweather.service.WeatherWarning
import com.frogweather.ui.SelectedWarningCard
import com.frogweather.ui.createLocalBoundingBox
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
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val OVERVIEW_ZOOM_2D = 6.2
private const val GLOBE_ZOOM_3D = 2.0
private const val GLOBE_TILT_3D = 15.0
private const val STREET_ZOOM_3D = 16.0
private const val STREET_TILT_3D = 60.0
private const val STREET_BEARING_3D = 25.0
private const val LOCAL_DIAMETER_KM = 15.0
private const val TIMELINE_STEP_INTERVAL_MS = 900L

private data class ProjectedSamplePoint(
    val lat: Double,
    val lon: Double,
    val screenX: Float,
    val screenY: Float
)

private data class MapProjectionSnapshot(
    val points: List<ProjectedSamplePoint> = emptyList(),
    val zoom: Double = OVERVIEW_ZOOM_2D,
    val isGlobe: Boolean = false,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val revision: Int = 0
)

private class FullMapController {
    var mapLibreMap: MapLibreMap? = null
    var styleReady: Boolean = false
    var loadedIsGlobe: Boolean? = null
    var usingFallbackRasterStyle: Boolean = false
    var triedLocalGlobeFallback: Boolean = false
    var isDisposed: Boolean = false
    var lastAppliedMode: MapViewMode? = null
    var lastCenteredLat: Double? = null
    var lastCenteredLon: Double? = null
    var lastLocalizeToken: Int = 0
    var lastWarningsSignature: String? = null
    var lastRasterSignature: String? = null
    var activeRasterIds: MutableList<Pair<String, String>> = mutableListOf()
    var latestWarnings: List<WeatherWarning> = emptyList()
    var latestShowWarnings: Boolean = true
    var latestSelectedWarningId: String? = null
    var latestRasterOverlays: List<ResolvedLayerOverlay.RasterTiles> = emptyList()
    var latestGridLats: List<Double> = emptyList()
    var latestGridLons: List<Double> = emptyList()
    var latestViewMode: MapViewMode = MapViewMode.MODE_2D
    var lastPublishedGridLats: List<Double> = emptyList()
    var lastPublishedGridLons: List<Double> = emptyList()
    var lastPublishedViewMode: MapViewMode? = null
    var lastEmittedProjection: MapProjectionSnapshot? = null
    var projectionRevision: Int = 0
}

@Composable
fun WeatherMapScreen(
    latitude: Double?,
    longitude: Double?,
    cityName: String?,
    country: String?,
    isLoadingLocation: Boolean,
    mapState: MapState,
    layerData: MapLayerData,
    warnings: List<WeatherWarning>,
    onSelectViewMode: (MapViewMode) -> Unit,
    onToggleLayer: (MapWeatherLayer) -> Unit,
    onClearLayers: () -> Unit,
    onSelectPressureLevel: (PressureLevel) -> Unit,
    onSelectModel: (MapForecastModel) -> Unit,
    onSelectTimelineIndex: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onStepTimeline: (Int) -> Unit,
    onToggleWarnings: () -> Unit,
    onRefreshData: () -> Unit,
    onViewportBoundsChanged: ((GeoBounds) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = FrogTheme.palette
    val safeLat = latitude ?: 51.1657
    val safeLon = longitude ?: 10.4515

    var showLayerDrawer by remember { mutableStateOf(false) }
    var selectedWarning by remember { mutableStateOf<WeatherWarning?>(null) }
    var localizeToken by remember { mutableIntStateOf(0) }
    var isLocalizedView by remember { mutableStateOf(false) }
    var globeFallbackActive by remember { mutableStateOf(false) }
    var projectionSnapshot by remember { mutableStateOf(MapProjectionSnapshot()) }
    var mapCenterLat by remember { mutableDoubleStateOf(safeLat) }
    var mapCenterLon by remember { mutableDoubleStateOf(safeLon) }

    LaunchedEffect(latitude, longitude) {
        if (latitude != null && longitude != null) {
            mapCenterLat = latitude
            mapCenterLon = longitude
            selectedWarning = null
            isLocalizedView = false
        }
    }

    LaunchedEffect(warnings) {
        selectedWarning = selectedWarning?.let { current ->
            warnings.firstOrNull { it.id == current.id }
        }
    }

    // Timeline player coroutine loop
    val latestOnStepTimeline by rememberUpdatedState(onStepTimeline)
    LaunchedEffect(mapState.isPlaying, mapState.timelineFrames.size) {
        if (!mapState.isPlaying || mapState.timelineFrames.size <= 1) return@LaunchedEffect
        while (isActive) {
            delay(TIMELINE_STEP_INTERVAL_MS)
            latestOnStepTimeline(1)
        }
    }

    // Resolve all active weather layers for the current model, pressure level, and timeline frame
    val resolvedOverlays = remember(
        mapState.activeLayers,
        mapState.pressureLevel,
        mapState.model,
        mapState.timelineIndex,
        mapState.timelineFrames,
        layerData
    ) {
        val frame = mapState.activeFrame
        MapWeatherLayer.entries
            .filter { it in mapState.activeLayers }
            .map { layer ->
                MapLayerDataProvider.resolveLayerOverlay(
                    layer = layer,
                    level = mapState.pressureLevel,
                    model = mapState.model,
                    frame = frame,
                    rainViewer = layerData.rainViewer,
                    gridForecasts = layerData.gridForecasts,
                    gridPlan = layerData.gridPlan,
                    owmApiKey = BuildConfig.OPENWEATHER_API_KEY
                )
            }
    }

    val rasterOverlays = remember(resolvedOverlays) {
        resolvedOverlays.filterIsInstance<ResolvedLayerOverlay.RasterTiles>()
    }

    val isUs = DwdWarningFetcher.isUsRegion(mapCenterLat, mapCenterLon)
    val isDwd = DwdWarningFetcher.isDwdRegion(mapCenterLat, mapCenterLon)
    val providerLabel = when {
        isUs -> WarningSource.NWS.badgeText
        isDwd -> WarningSource.DWD.badgeText
        else -> "GLOBAL"
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (latitude == null || longitude == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.screenBackground),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLoadingLocation) {
                        "Standort wird ermittelt..."
                    } else {
                        "Bitte zuerst einen Ort suchen oder GPS aktivieren."
                    },
                    color = palette.textSecondary,
                    fontSize = 14.sp
                )
            }
            return@Box
        }

        // Layer 0: MapLibre GL MapView
        MapLibreFullMapView(
            latitude = safeLat,
            longitude = safeLon,
            viewMode = mapState.viewMode,
            rasterOverlays = rasterOverlays,
            warnings = warnings,
            showWarnings = mapState.showWarnings,
            selectedWarningId = selectedWarning?.id,
            gridLats = layerData.gridPlan?.lats.orEmpty(),
            gridLons = layerData.gridPlan?.lons.orEmpty(),
            localizeToken = localizeToken,
            isLocalizedView = isLocalizedView,
            onGlobeFallbackChanged = { globeFallbackActive = it },
            onProjectionUpdated = { projectionSnapshot = it },
            onViewportIdle = { centerLat, centerLon, bounds ->
                mapCenterLat = centerLat
                mapCenterLon = centerLon
                if (isLocalizedView && (abs(centerLat - safeLat) > 0.05 || abs(centerLon - safeLon) > 0.05)) {
                    isLocalizedView = false
                }
                if (bounds != null) {
                    onViewportBoundsChanged?.invoke(bounds)
                }
            },
            onWarningClicked = { warning ->
                selectedWarning = if (selectedWarning?.id == warning.id) null else warning
            }
        )

        // Layer 1: Touch-transparent animated Canvas overlay for all 10 weather layers
        AnimatedMapLayersCanvas(
            activeLayers = mapState.activeLayers,
            resolvedOverlays = resolvedOverlays,
            projection = projectionSnapshot,
            viewMode = mapState.viewMode,
            globeFallbackActive = globeFallbackActive,
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
        )

        // Layer 2: Top HUD (360dp-safe)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: View mode segmented pill + GFS/ECMWF model switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeSegmentedPill(
                    selectedMode = mapState.viewMode,
                    onSelectMode = { mode ->
                        isLocalizedView = false
                        onSelectViewMode(mode)
                    },
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(6.dp))

                ModelSwitchPill(
                    selectedModel = mapState.model,
                    onSelectModel = onSelectModel
                )
            }

            // Row 2: Pressure levels + Warnings toggle + Live badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PressureLevel.entries.forEach { level ->
                    val selected = mapState.pressureLevel == level
                    HudChip(
                        label = level.hpaLabel,
                        selected = selected,
                        onClick = { onSelectPressureLevel(level) }
                    )
                }

                val warningActive = mapState.showWarnings
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (warningActive) Color(0xFFB71C1C).copy(alpha = 0.96f)
                            else palette.tileSurface.copy(alpha = 0.94f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (warningActive) Color(0xFFFFCDD2) else palette.dividerColor,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            onToggleWarnings()
                            if (warningActive) selectedWarning = null
                        }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (warningActive) Color.White else palette.textSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Warnungen (${warnings.size})",
                        color = if (warningActive) Color.White else palette.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    color = palette.tileSurface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(10.dp),
                    elevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = buildString {
                                append("LIVE · ")
                                append(providerLabel)
                                if (!cityName.isNullOrBlank()) {
                                    append(" · ")
                                    append(cityName)
                                }
                            },
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Active layers horizontal strip
            if (mapState.activeLayers.isNotEmpty() && !showLayerDrawer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    resolvedOverlays.forEach { overlay ->
                        val layer = overlay.layer
                        val meta = layerDisplayMeta(layer)
                        val isSfcFallback = overlay is ResolvedLayerOverlay.ScalarGrid && overlay.field.isSurfaceFallback
                        val subtitle = when (overlay) {
                            is ResolvedLayerOverlay.RasterTiles -> overlay.sourceLabel
                            is ResolvedLayerOverlay.ScalarGrid -> if (isSfcFallback) "Sfc" else overlay.sourceLabel
                            is ResolvedLayerOverlay.WindVectors -> overlay.sourceLabel
                            is ResolvedLayerOverlay.Unavailable -> "k.A."
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.tileSurface.copy(alpha = 0.94f))
                                .border(1.dp, meta.color.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                                .clickable { onToggleLayer(layer) }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(meta.color)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "${meta.label} · $subtitle",
                                color = palette.textPrimary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "${meta.label} entfernen",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    if (mapState.activeLayers.size > 1) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.tileSurfaceElevated.copy(alpha = 0.94f))
                                .clickable(onClick = onClearLayers)
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "Alle aus",
                                color = palette.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            if (globeFallbackActive && mapState.viewMode == MapViewMode.GLOBE_3D) {
                Surface(
                    color = palette.tileSurface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(8.dp),
                    elevation = 2.dp
                ) {
                    Text(
                        text = "2D-Fallback (GPU ohne Globe-Shader)",
                        color = palette.accent,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Layer 3 & 4: Bottom stack (Action FABs + Warning Card + Timeline Player)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Action buttons aligned bottom-right above timeline player
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Refresh data button
                Surface(
                    color = palette.tileSurface.copy(alpha = 0.94f),
                    shape = CircleShape,
                    elevation = 4.dp,
                    modifier = Modifier
                        .size(38.dp)
                        .border(1.dp, palette.dividerColor, CircleShape)
                        .clickable(onClick = onRefreshData)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Kartendaten aktualisieren",
                            tint = palette.accent,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Layer drawer trigger
                Surface(
                    color = if (showLayerDrawer) palette.accent else palette.tileSurface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(20.dp),
                    elevation = 4.dp,
                    modifier = Modifier
                        .height(40.dp)
                        .border(
                            width = 1.dp,
                            color = if (showLayerDrawer) palette.accent else palette.accent.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { showLayerDrawer = !showLayerDrawer }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Karten-Layer wählen",
                            tint = if (showLayerDrawer) palette.screenBackground else palette.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Layer (${mapState.activeLayers.size}/10)",
                            color = if (showLayerDrawer) palette.screenBackground else palette.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 15km localize button
                Surface(
                    color = if (isLocalizedView) palette.accent else palette.tileSurface.copy(alpha = 0.94f),
                    shape = CircleShape,
                    elevation = 4.dp,
                    modifier = Modifier
                        .size(42.dp)
                        .border(
                            width = 1.dp,
                            color = if (isLocalizedView) palette.screenBackground else palette.accent.copy(alpha = 0.7f),
                            shape = CircleShape
                        )
                        .clickable {
                            isLocalizedView = !isLocalizedView
                            localizeToken++
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Auf 15-km-Umkreis lokalisieren",
                            tint = if (isLocalizedView) palette.screenBackground else palette.accent,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }

            selectedWarning?.let { warning ->
                SelectedWarningCard(
                    warning = warning,
                    onDismiss = { selectedWarning = null }
                )
            }

            TimelinePlayerCard(
                mapState = mapState,
                isLoadingData = layerData.isLoading,
                errorMessage = layerData.errorMessage,
                onSelectTimelineIndex = onSelectTimelineIndex,
                onTogglePlay = onTogglePlay,
                onStepTimeline = onStepTimeline
            )
        }

        // Layer 5: Expandable 10-Layer Combiner Drawer
        if (showLayerDrawer) {
            LayerCombinerDrawer(
                activeLayers = mapState.activeLayers,
                pressureLevel = mapState.pressureLevel,
                model = mapState.model,
                onToggleLayer = onToggleLayer,
                onClearLayers = onClearLayers,
                onDismiss = { showLayerDrawer = false }
            )
        }
    }
}

@Composable
private fun ModeSegmentedPill(
    selectedMode: MapViewMode,
    onSelectMode: (MapViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = FrogTheme.palette
    val modes = listOf(
        MapViewMode.MODE_2D to "2D",
        MapViewMode.GLOBE_3D to "Globus",
        MapViewMode.STREET_3D to "3D"
    )
    Surface(
        color = palette.tileSurface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(12.dp),
        elevation = 4.dp,
        modifier = modifier.border(1.dp, palette.dividerColor, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEach { (mode, label) ->
                val isSelected = selectedMode == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) palette.accent else Color.Transparent)
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) palette.screenBackground else palette.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSwitchPill(
    selectedModel: MapForecastModel,
    onSelectModel: (MapForecastModel) -> Unit
) {
    val palette = FrogTheme.palette
    Surface(
        color = palette.tileSurface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(12.dp),
        elevation = 4.dp,
        modifier = Modifier.border(1.dp, palette.dividerColor, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MapForecastModel.entries.forEach { model ->
                val isSelected = selectedModel == model
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) palette.accent else Color.Transparent)
                        .clickable { onSelectModel(model) }
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = model.displayName,
                        color = if (isSelected) palette.screenBackground else palette.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun HudChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = FrogTheme.palette
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) palette.accent else palette.tileSurface.copy(alpha = 0.94f)
            )
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else palette.dividerColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) palette.screenBackground else palette.textPrimary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TimelinePlayerCard(
    mapState: MapState,
    isLoadingData: Boolean,
    errorMessage: String?,
    onSelectTimelineIndex: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onStepTimeline: (Int) -> Unit
) {
    val palette = FrogTheme.palette
    val frames = mapState.timelineFrames
    val activeFrame = mapState.activeFrame
    val maxIndex = (frames.size - 1).coerceAtLeast(0)

    Surface(
        color = palette.tileSurface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        elevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.dividerColor, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onStepTimeline(-1) },
                        enabled = frames.size > 1,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Vorheriger Zeitschritt",
                            tint = if (frames.size > 1) palette.textPrimary else palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (frames.size > 1) palette.accent else palette.tileSurfaceElevated)
                            .clickable(enabled = frames.size > 1, onClick = onTogglePlay),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (mapState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (mapState.isPlaying) "Pause" else "Abspielen",
                            tint = if (frames.size > 1) palette.screenBackground else palette.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { onStepTimeline(1) },
                        enabled = frames.size > 1,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Nächster Zeitschritt",
                            tint = if (frames.size > 1) palette.textPrimary else palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    val frameLabel = when {
                        activeFrame != null -> activeFrame.label
                        isLoadingData -> "Lade Zeitachse..."
                        errorMessage != null -> errorMessage
                        else -> "Keine Zeitachse"
                    }
                    Text(
                        text = frameLabel,
                        color = palette.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sourceTag = when (activeFrame?.source) {
                        TimelineFrameSource.RADAR_PAST -> "Radar (Archiv)"
                        TimelineFrameSource.RADAR_NOWCAST -> "Radar Nowcast"
                        TimelineFrameSource.FORECAST_MODEL -> "${mapState.model.displayName} · ${mapState.pressureLevel.hpaLabel}"
                        null -> "${mapState.model.displayName} · ${mapState.pressureLevel.hpaLabel}"
                    }
                    Text(
                        text = sourceTag,
                        color = palette.accent,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (frames.size > 1) {
                Slider(
                    value = mapState.timelineIndex.coerceIn(0, maxIndex).toFloat(),
                    onValueChange = { value ->
                        onSelectTimelineIndex(value.roundToInt().coerceIn(0, maxIndex))
                    },
                    valueRange = 0f..maxIndex.toFloat(),
                    steps = if (maxIndex > 1) maxIndex - 1 else 0,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                        inactiveTrackColor = palette.dividerColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                )
            }
        }
    }
}

@Composable
private fun LayerCombinerDrawer(
    activeLayers: Set<MapWeatherLayer>,
    pressureLevel: PressureLevel,
    model: MapForecastModel,
    onToggleLayer: (MapWeatherLayer) -> Unit,
    onClearLayers: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = FrogTheme.palette
    val layers = MapWeatherLayer.entries

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = palette.tileSurface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            elevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Karten-Layer kombinieren (${activeLayers.size}/10)",
                            color = palette.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${model.displayName} · ${pressureLevel.hpaLabel}",
                            color = palette.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeLayers.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.tileSurfaceElevated)
                                    .clickable(onClick = onClearLayers)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Zurücksetzen",
                                    color = palette.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.accent)
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Fertig",
                                color = palette.screenBackground,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2-column grid of all 10 combinable layers
                layers.chunked(2).forEach { rowLayers ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowLayers.forEach { layer ->
                            val isActive = layer in activeLayers
                            val meta = layerDisplayMeta(layer)
                            val spec = MapLayerDataProvider.resolveLayerVariables(layer, pressureLevel)
                            val levelInfo = if (!spec.isPressureLevelSupported && pressureLevel != PressureLevel.SFC) {
                                "${meta.sourceHint} · Sfc"
                            } else {
                                "${meta.sourceHint} (${spec.unit})"
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isActive) palette.tileSurfaceElevated else palette.screenBackground.copy(alpha = 0.6f)
                                    )
                                    .border(
                                        width = if (isActive) 1.5.dp else 1.dp,
                                        color = if (isActive) meta.color else palette.dividerColor,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onToggleLayer(layer) }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(meta.color)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = meta.label,
                                        color = palette.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = levelInfo,
                                        color = palette.textSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isActive) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Aktiv",
                                        tint = meta.color,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        if (rowLayers.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapLibreFullMapView(
    latitude: Double,
    longitude: Double,
    viewMode: MapViewMode,
    rasterOverlays: List<ResolvedLayerOverlay.RasterTiles>,
    warnings: List<WeatherWarning>,
    showWarnings: Boolean,
    selectedWarningId: String?,
    gridLats: List<Double>,
    gridLons: List<Double>,
    localizeToken: Int,
    isLocalizedView: Boolean,
    onGlobeFallbackChanged: (Boolean) -> Unit,
    onProjectionUpdated: (MapProjectionSnapshot) -> Unit,
    onViewportIdle: (Double, Double, GeoBounds?) -> Unit,
    onWarningClicked: (WeatherWarning) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { FullMapController() }
    val latestOnProjectionUpdated by rememberUpdatedState(onProjectionUpdated)
    val latestOnViewportIdle by rememberUpdatedState(onViewportIdle)
    val latestOnWarningClicked by rememberUpdatedState(onWarningClicked)
    val latestOnGlobeFallbackChanged by rememberUpdatedState(onGlobeFallbackChanged)

    fun publishProjectionIfChanged(map: MapLibreMap, view: MapView) {
        val lats = controller.latestGridLats
        val lons = controller.latestGridLons
        val isGlobe = controller.latestViewMode == MapViewMode.GLOBE_3D
        val proj = map.projection
        val pts = ArrayList<ProjectedSamplePoint>(lats.size * lons.size)
        for (lat in lats) {
            for (lon in lons) {
                if (!lat.isFinite() || !lon.isFinite()) continue
                val latLng = LatLng(lat, lon)
                if (isGlobe) {
                    val occluded = runCatching { proj.isLocationOccluded(latLng) }.getOrDefault(false)
                    if (occluded) continue
                }
                val screen = proj.toScreenLocation(latLng)
                if (screen.x.isFinite() && screen.y.isFinite()) {
                    pts.add(ProjectedSamplePoint(lat, lon, screen.x, screen.y))
                }
            }
        }
        val zoom = map.cameraPosition.zoom
        val centerX = view.width / 2f
        val centerY = view.height / 2f
        val prev = controller.lastEmittedProjection
        if (prev != null &&
            prev.points == pts &&
            prev.zoom == zoom &&
            prev.isGlobe == isGlobe &&
            prev.centerX == centerX &&
            prev.centerY == centerY
        ) {
            return
        }
        controller.projectionRevision++
        val next = MapProjectionSnapshot(
            points = pts,
            zoom = zoom,
            isGlobe = isGlobe,
            centerX = centerX,
            centerY = centerY,
            revision = controller.projectionRevision
        )
        controller.lastEmittedProjection = next
        latestOnProjectionUpdated(next)
    }

    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
        MapView(context, options).apply {
            onCreate(null)
            addOnDidFailLoadingMapListener { errorMsg ->
                if (controller.latestViewMode == MapViewMode.GLOBE_3D && controller.loadedIsGlobe == true) {
                    val map = controller.mapLibreMap ?: return@addOnDidFailLoadingMapListener
                    if (!controller.usingFallbackRasterStyle && !controller.triedLocalGlobeFallback) {
                        controller.triedLocalGlobeFallback = true
                        controller.usingFallbackRasterStyle = true
                        val localGlobeJson = MapLibreStyleLoader.buildFallbackRasterStyleJson(
                            isGlobe = true,
                            darkTheme = true
                        )
                        try {
                            map.setStyle(Style.Builder().fromJson(localGlobeJson)) { style ->
                                controller.styleReady = true
                                controller.loadedIsGlobe = true
                                latestOnGlobeFallbackChanged(false)
                                applyFullScreenRasterOverlays(style, controller, controller.latestRasterOverlays)
                                MapLibreWarningOverlay.applyWarnings(
                                    style = style,
                                    warnings = controller.latestWarnings,
                                    showWarnings = controller.latestShowWarnings,
                                    selectedWarningId = controller.latestSelectedWarningId
                                )
                                publishProjectionIfChanged(map, this)
                            }
                        } catch (_: Throwable) {
                            applyMercatorFallbackStyle(map, this, controller, latestOnGlobeFallbackChanged, ::publishProjectionIfChanged)
                        }
                    } else if (isNetworkOrTileLoadError(errorMsg)) {
                        // Transient network/tile HTTP error on local globe raster style: stay in GLOBE_3D
                        return@addOnDidFailLoadingMapListener
                    } else {
                        applyMercatorFallbackStyle(map, this, controller, latestOnGlobeFallbackChanged, ::publishProjectionIfChanged)
                    }
                }
            }

            getMapAsync { mapLibreMap ->
                controller.mapLibreMap = mapLibreMap
                mapLibreMap.uiSettings.isAttributionEnabled = false
                mapLibreMap.uiSettings.isLogoEnabled = false
                mapLibreMap.setMinPitchPreference(0.0)
                mapLibreMap.setMaxPitchPreference(65.0)
                applyCameraPreset(mapLibreMap, viewMode, latitude, longitude, animate = false)
                controller.lastCenteredLat = latitude
                controller.lastCenteredLon = longitude
                controller.lastAppliedMode = viewMode

                mapLibreMap.addOnCameraMoveListener {
                    publishProjectionIfChanged(mapLibreMap, this)
                }

                mapLibreMap.addOnCameraIdleListener {
                    publishProjectionIfChanged(mapLibreMap, this)
                    val target = mapLibreMap.cameraPosition.target ?: return@addOnCameraIdleListener
                    val visibleBounds = runCatching {
                        val b = mapLibreMap.projection.visibleRegion.latLngBounds
                        GeoBounds(
                            south = b.latitudeSouth,
                            west = b.longitudeWest,
                            north = b.latitudeNorth,
                            east = b.longitudeEast
                        )
                    }.getOrNull()
                    latestOnViewportIdle(target.latitude, target.longitude, visibleBounds)
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
            }
        }
    }

    // Load or switch base style when viewMode globe vs mercator changes
    val wantGlobe = viewMode == MapViewMode.GLOBE_3D
    LaunchedEffect(mapView, wantGlobe) {
        val styleJson = MapLibreStyleLoader.loadStyleJson(
            context = context,
            isGlobe = wantGlobe,
            preferDarkFallback = wantGlobe
        )
        // Wait briefly if mapLibreMap hasn't resolved yet
        var map = controller.mapLibreMap
        var attempts = 0
        while (map == null && attempts < 30) {
            delay(50)
            map = controller.mapLibreMap
            attempts++
        }
        val activeMap = map ?: return@LaunchedEffect
        if (controller.loadedIsGlobe == wantGlobe && controller.styleReady) return@LaunchedEffect

        val isFallbackStyle = styleJson.contains("FrogWeather Fallback")
        controller.usingFallbackRasterStyle = isFallbackStyle
        controller.triedLocalGlobeFallback = isFallbackStyle && wantGlobe
        latestOnGlobeFallbackChanged(false)
        try {
            activeMap.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                controller.styleReady = true
                controller.loadedIsGlobe = wantGlobe
                applyFullScreenRasterOverlays(style, controller, controller.latestRasterOverlays)
                MapLibreWarningOverlay.applyWarnings(
                    style = style,
                    warnings = controller.latestWarnings,
                    showWarnings = controller.latestShowWarnings,
                    selectedWarningId = controller.latestSelectedWarningId
                )
                publishProjectionIfChanged(activeMap, mapView)
            }
        } catch (_: Throwable) {
            if (wantGlobe) {
                if (!isFallbackStyle && !controller.triedLocalGlobeFallback) {
                    controller.triedLocalGlobeFallback = true
                    controller.usingFallbackRasterStyle = true
                    val localGlobeJson = MapLibreStyleLoader.buildFallbackRasterStyleJson(
                        isGlobe = true,
                        darkTheme = true
                    )
                    try {
                        activeMap.setStyle(Style.Builder().fromJson(localGlobeJson)) { style ->
                            controller.styleReady = true
                            controller.loadedIsGlobe = true
                            applyFullScreenRasterOverlays(style, controller, controller.latestRasterOverlays)
                            MapLibreWarningOverlay.applyWarnings(
                                style = style,
                                warnings = controller.latestWarnings,
                                showWarnings = controller.latestShowWarnings,
                                selectedWarningId = controller.latestSelectedWarningId
                            )
                            publishProjectionIfChanged(activeMap, mapView)
                        }
                    } catch (_: Throwable) {
                        applyMercatorFallbackStyle(activeMap, mapView, controller, latestOnGlobeFallbackChanged, ::publishProjectionIfChanged)
                    }
                } else {
                    applyMercatorFallbackStyle(activeMap, mapView, controller, latestOnGlobeFallbackChanged, ::publishProjectionIfChanged)
                }
            }
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
                    controller.isDisposed = true
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
                controller.isDisposed = true
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            controller.latestRasterOverlays = rasterOverlays
            controller.latestWarnings = warnings
            controller.latestShowWarnings = showWarnings
            controller.latestSelectedWarningId = selectedWarningId
            controller.latestGridLats = gridLats
            controller.latestGridLons = gridLons
            controller.latestViewMode = viewMode

            val map = controller.mapLibreMap ?: return@AndroidView

            if (controller.lastAppliedMode != viewMode) {
                controller.lastAppliedMode = viewMode
                applyCameraPreset(map, viewMode, latitude, longitude, animate = true)
            } else if (controller.lastCenteredLat != latitude || controller.lastCenteredLon != longitude) {
                controller.lastCenteredLat = latitude
                controller.lastCenteredLon = longitude
                applyCameraPreset(map, viewMode, latitude, longitude, animate = false)
            }

            if (controller.lastLocalizeToken != localizeToken) {
                controller.lastLocalizeToken = localizeToken
                if (isLocalizedView) {
                    val box = createLocalBoundingBox(latitude, longitude, LOCAL_DIAMETER_KM)
                    val bounds = LatLngBounds.from(box.latNorth, box.lonEast, box.latSouth, box.lonWest)
                    val paddingPx = (36 * view.resources.displayMetrics.density).toInt()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx), 600)
                } else {
                    applyCameraPreset(map, viewMode, latitude, longitude, animate = true)
                }
            }

            val style = map.style
            if (style != null && style.isFullyLoaded && controller.styleReady) {
                val rasterSig = rasterOverlays.joinToString("|") {
                    "${it.layer.name}:${it.urlTemplate}:${it.maxZoom}:${it.opacity}"
                }
                if (controller.lastRasterSignature != rasterSig) {
                    applyFullScreenRasterOverlays(style, controller, rasterOverlays)
                    controller.lastRasterSignature = rasterSig
                }

                val warningSig = buildString {
                    append(showWarnings)
                    append('|')
                    append(selectedWarningId.orEmpty())
                    append('|')
                    warnings.forEach { append(it.id).append(';') }
                }
                if (controller.lastWarningsSignature != warningSig) {
                    MapLibreWarningOverlay.applyWarnings(
                        style = style,
                        warnings = warnings,
                        showWarnings = showWarnings,
                        selectedWarningId = selectedWarningId
                    )
                    controller.lastWarningsSignature = warningSig
                }

                val projectionInputsChanged = controller.lastPublishedGridLats != gridLats ||
                    controller.lastPublishedGridLons != gridLons ||
                    controller.lastPublishedViewMode != viewMode ||
                    controller.lastEmittedProjection == null
                if (projectionInputsChanged) {
                    controller.lastPublishedGridLats = gridLats
                    controller.lastPublishedGridLons = gridLons
                    controller.lastPublishedViewMode = viewMode
                    view.post {
                        if (!controller.isDisposed && controller.mapLibreMap === map && controller.styleReady) {
                            publishProjectionIfChanged(map, view)
                        }
                    }
                }
            }
        }
    )
}

private fun isNetworkOrTileLoadError(errorMsg: String?): Boolean {
    val msg = errorMsg?.lowercase(Locale.US).orEmpty()
    if (msg.isBlank()) return false
    return msg.contains("http") ||
        msg.contains("timeout") ||
        msg.contains("timed out") ||
        msg.contains("connect") ||
        msg.contains("resolve") ||
        msg.contains("network") ||
        msg.contains("socket") ||
        msg.contains("ssl") ||
        msg.contains("offline") ||
        msg.contains("unreachable") ||
        msg.contains("tile") ||
        msg.contains("source") ||
        msg.contains("sprite") ||
        msg.contains("glyph")
}

private inline fun applyMercatorFallbackStyle(
    map: MapLibreMap,
    view: MapView,
    controller: FullMapController,
    crossinline onGlobeFallbackChanged: (Boolean) -> Unit,
    crossinline onStyleApplied: (MapLibreMap, MapView) -> Unit
) {
    onGlobeFallbackChanged(true)
    controller.usingFallbackRasterStyle = true
    val fallbackJson = MapLibreStyleLoader.buildFallbackRasterStyleJson(
        isGlobe = false,
        darkTheme = true
    )
    runCatching {
        map.setStyle(Style.Builder().fromJson(fallbackJson)) { style ->
            controller.styleReady = true
            controller.loadedIsGlobe = false
            applyFullScreenRasterOverlays(style, controller, controller.latestRasterOverlays)
            MapLibreWarningOverlay.applyWarnings(
                style = style,
                warnings = controller.latestWarnings,
                showWarnings = controller.latestShowWarnings,
                selectedWarningId = controller.latestSelectedWarningId
            )
            onStyleApplied(map, view)
        }
    }
}

private fun applyCameraPreset(
    map: MapLibreMap,
    mode: MapViewMode,
    lat: Double,
    lon: Double,
    animate: Boolean
) {
    val (zoom, tilt, bearing, allowTilt) = when (mode) {
        MapViewMode.MODE_2D -> CameraPreset(OVERVIEW_ZOOM_2D, 0.0, 0.0, false)
        MapViewMode.GLOBE_3D -> CameraPreset(GLOBE_ZOOM_3D, GLOBE_TILT_3D, 0.0, true)
        MapViewMode.STREET_3D -> CameraPreset(STREET_ZOOM_3D, STREET_TILT_3D, STREET_BEARING_3D, true)
    }
    map.setMaxPitchPreference(65.0)
    map.uiSettings.isTiltGesturesEnabled = allowTilt
    val cameraPosition = CameraPosition.Builder()
        .target(LatLng(lat, lon))
        .zoom(zoom)
        .tilt(tilt)
        .bearing(bearing)
        .build()
    val update = CameraUpdateFactory.newCameraPosition(cameraPosition)
    if (animate) {
        map.animateCamera(update, 750)
    } else {
        map.moveCamera(update)
    }
}

private data class CameraPreset(
    val zoom: Double,
    val tilt: Double,
    val bearing: Double,
    val allowTilt: Boolean
)

private fun applyFullScreenRasterOverlays(
    style: Style,
    controller: FullMapController,
    overlays: List<ResolvedLayerOverlay.RasterTiles>
) {
    controller.activeRasterIds.forEach { (layerId, sourceId) ->
        style.removeLayer(layerId)
        style.removeSource(sourceId)
    }
    controller.activeRasterIds.clear()

    val opacityScale = if (overlays.size > 1) 0.78f else 1.0f
    overlays.forEach { overlay ->
        val sourceId = "frog-raster-src-${overlay.layer.name.lowercase(Locale.US)}"
        val layerId = "frog-raster-lyr-${overlay.layer.name.lowercase(Locale.US)}"
        val tileSet = TileSet("2.2.0", overlay.urlTemplate).apply {
            minZoom = overlay.minZoom.toFloat()
            maxZoom = overlay.maxZoom.toFloat()
        }
        style.addSource(RasterSource(sourceId, tileSet, 256))
        val rasterLayer = RasterLayer(layerId, sourceId).withProperties(
            PropertyFactory.rasterOpacity((overlay.opacity * opacityScale).coerceIn(0.15f, 0.9f))
        )
        if (style.getLayer(MapLibreWarningOverlay.WARNING_FILL_LAYER_ID) != null) {
            style.addLayerBelow(rasterLayer, MapLibreWarningOverlay.WARNING_FILL_LAYER_ID)
        } else {
            style.addLayer(rasterLayer)
        }
        controller.activeRasterIds.add(layerId to sourceId)
    }
}

private data class WindParticle(
    var normX: Float,
    var normY: Float,
    var phase: Float
)

@Composable
private fun AnimatedMapLayersCanvas(
    activeLayers: Set<MapWeatherLayer>,
    resolvedOverlays: List<ResolvedLayerOverlay>,
    projection: MapProjectionSnapshot,
    viewMode: MapViewMode,
    globeFallbackActive: Boolean,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    var phaseSeconds by remember { mutableFloatStateOf(0f) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val particles = remember {
        val rng = Random(42)
        List(72) {
            WindParticle(
                normX = rng.nextFloat(),
                normY = rng.nextFloat(),
                phase = rng.nextFloat() * 2f * PI.toFloat()
            )
        }
    }

    val shouldAnimate = isResumed && (activeLayers.isNotEmpty() || (globeFallbackActive && viewMode == MapViewMode.GLOBE_3D))

    LaunchedEffect(shouldAnimate) {
        if (!shouldAnimate) return@LaunchedEffect
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    phaseSeconds += dt
                }
                lastNanos = frameNanos
            }
        }
    }

    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Optional spherical horizon vignette if device GPU fell back from globe shaders
        if (globeFallbackActive && viewMode == MapViewMode.GLOBE_3D) {
            val radius = minOf(w, h) * 0.44f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0xFF4FC3F7).copy(alpha = 0.25f), Color(0xFF0A111A).copy(alpha = 0.72f)),
                    center = Offset(w / 2f, h / 2f),
                    radius = radius * 1.15f
                ),
                radius = radius * 1.15f,
                center = Offset(w / 2f, h / 2f)
            )
            drawCircle(
                color = Color(0xFF81D4FA).copy(alpha = 0.45f),
                radius = radius,
                center = Offset(w / 2f, h / 2f),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        val points = projection.points
        val pulse = 0.5f + 0.5f * sin(phaseSeconds * 2.4f)

        resolvedOverlays.forEach { overlay ->
            when (overlay) {
                is ResolvedLayerOverlay.WindVectors -> {
                    drawWindStreamlines(
                        field = overlay.field,
                        points = points,
                        particles = particles,
                        phaseSeconds = phaseSeconds,
                        width = w,
                        height = h
                    )
                }

                is ResolvedLayerOverlay.ScalarGrid -> {
                    when (overlay.layer) {
                        MapWeatherLayer.TEMPERATURE -> drawTemperatureField(overlay.field, points, pulse, textPaint)
                        MapWeatherLayer.HUMIDITY -> drawHumidityField(overlay.field, points, pulse)
                        MapWeatherLayer.CAPE -> drawCapeField(overlay.field, points, pulse)
                        MapWeatherLayer.GUSTS -> drawGustStreaks(overlay.field, points, phaseSeconds)
                        MapWeatherLayer.PRECIPITATION, MapWeatherLayer.RADAR -> drawPrecipCells(overlay.field, points, pulse)
                        MapWeatherLayer.CLOUDS, MapWeatherLayer.SATELLITE -> drawCloudPatches(overlay.field, points, phaseSeconds)
                        MapWeatherLayer.PRESSURE -> drawPressureIsobars(overlay.field, points, pulse, textPaint)
                        MapWeatherLayer.WIND -> Unit
                    }
                }

                is ResolvedLayerOverlay.RasterTiles -> {
                    // Subtle animated shimmer indicator for live raster layers (Radar / Satellite)
                    val accent = if (overlay.layer == MapWeatherLayer.RADAR) {
                        Color(0xFF4FC3F7)
                    } else {
                        Color(0xFFE0F7FA)
                    }
                    if (points.isNotEmpty()) {
                        val pt = points[points.size / 2]
                        drawCircle(
                            color = accent.copy(alpha = 0.10f + 0.10f * pulse),
                            radius = (26f + 16f * pulse).dp.toPx(),
                            center = Offset(pt.screenX, pt.screenY),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                is ResolvedLayerOverlay.Unavailable -> Unit
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWindStreamlines(
    field: WindGridField,
    points: List<ProjectedSamplePoint>,
    particles: List<WindParticle>,
    phaseSeconds: Float,
    width: Float,
    height: Float
) {
    // Draw vector streamlines at projected sample grid points
    points.forEach { pt ->
        val sample = MapLayerDataProvider.interpolateWind(field, pt.lat, pt.lon) ?: return@forEach
        val speedNorm = (sample.speedKmh / 65.0).coerceIn(0.15, 1.0).toFloat()
        val color = windColor(sample.speedKmh)
        val dirRad = Math.toRadians(sample.directionDeg)
        // Meteorological wind direction = direction FROM; flow goes TO (opposite)
        val flowX = -sin(dirRad).toFloat()
        val flowY = cos(dirRad).toFloat() // screen Y increases downward; north is -Y

        val travelOffset = ((phaseSeconds * (0.4f + speedNorm * 0.9f)) % 1f) * 28.dp.toPx()
        val tailLen = (14f + 22f * speedNorm).dp.toPx()
        val startX = pt.screenX + flowX * (travelOffset - tailLen * 0.5f)
        val startY = pt.screenY + flowY * (travelOffset - tailLen * 0.5f)
        val endX = startX + flowX * tailLen
        val endY = startY + flowY * tailLen

        drawLine(
            color = color.copy(alpha = 0.78f),
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = (2.0f + 1.5f * speedNorm).dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = 2.2.dp.toPx(),
            center = Offset(endX, endY)
        )
    }

    // Draw ambient flowing particles driven by center wind vector
    val centerSample = if (points.isNotEmpty()) {
        val mid = points[points.size / 2]
        MapLayerDataProvider.interpolateWind(field, mid.lat, mid.lon)
    } else {
        null
    }
    if (centerSample != null) {
        val dirRad = Math.toRadians(centerSample.directionDeg)
        val flowX = -sin(dirRad).toFloat()
        val flowY = cos(dirRad).toFloat()
        val speedScale = (centerSample.speedKmh / 40.0).coerceIn(0.25, 1.8).toFloat()
        val color = windColor(centerSample.speedKmh)

        particles.forEach { p ->
            val t = (phaseSeconds * 0.14f * speedScale + p.phase) % 1f
            val x = ((p.normX + flowX * t * 0.25f) % 1f + 1f) % 1f * width
            val y = ((p.normY + flowY * t * 0.25f) % 1f + 1f) % 1f * height
            val len = 14.dp.toPx() * speedScale.coerceAtMost(1.3f)
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(x, y),
                end = Offset(x + flowX * len, y + flowY * len),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTemperatureField(
    field: ScalarGridField,
    points: List<ProjectedSamplePoint>,
    pulse: Float,
    textPaint: Paint
) {
    points.forEachIndexed { idx, pt ->
        val tempC = MapLayerDataProvider.interpolateScalar(field, pt.lat, pt.lon) ?: return@forEachIndexed
        val color = tempColor(tempC)
        val radius = (34f + 6f * pulse).dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.42f), color.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(pt.screenX, pt.screenY),
                radius = radius
            ),
            radius = radius,
            center = Offset(pt.screenX, pt.screenY)
        )
        if (idx % 2 == 0) {
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.GERMAN, "%.0f°", tempC),
                pt.screenX,
                pt.screenY + 8f,
                textPaint
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHumidityField(
    field: ScalarGridField,
    points: List<ProjectedSamplePoint>,
    pulse: Float
) {
    points.forEach { pt ->
        val hum = MapLayerDataProvider.interpolateScalar(field, pt.lat, pt.lon) ?: return@forEach
        val norm = (hum / 100.0).coerceIn(0.1, 1.0).toFloat()
        val radius = (28f + 18f * norm + 5f * pulse).dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF26C6DA).copy(alpha = 0.38f * norm),
                    Color(0xFF00838F).copy(alpha = 0.14f * norm),
                    Color.Transparent
                ),
                center = Offset(pt.screenX, pt.screenY),
                radius = radius
            ),
            radius = radius,
            center = Offset(pt.screenX, pt.screenY)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCapeField(
    field: ScalarGridField,
    points: List<ProjectedSamplePoint>,
    pulse: Float
) {
    points.forEach { pt ->
        val cape = MapLayerDataProvider.interpolateScalar(field, pt.lat, pt.lon) ?: return@forEach
        val intensity = (cape / 1500.0).coerceIn(0.05, 1.0).toFloat()
        val color = when {
            cape >= 1500 -> Color(0xFFFF1744)
            cape >= 600 -> Color(0xFFFF9100)
            else -> Color(0xFFE040FB)
        }
        val radius = (18f + 26f * intensity + 10f * pulse).dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.48f * intensity), Color.Transparent),
                center = Offset(pt.screenX, pt.screenY),
                radius = radius
            ),
            radius = radius,
            center = Offset(pt.screenX, pt.screenY)
        )
        if (cape >= 150.0) {
            drawCircle(
                color = color.copy(alpha = 0.72f * (1f - pulse * 0.4f)),
                radius = radius * (0.55f + 0.35f * pulse),
                center = Offset(pt.screenX, pt.screenY),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGustStreaks(
    field: ScalarGridField,
    points: List<ProjectedSamplePoint>,
    phaseSeconds: Float
) {
    points.forEachIndexed { idx, pt ->
        val gust = MapLayerDataProvider.interpolateScalar(field, pt.lat, pt.lon) ?: return@forEachIndexed
        val norm = (gust / 90.0).coerceIn(0.15, 1.0).toFloat()
        val color = if (gust >= 65.0) Color(0xFFFF5252) else Color(0xFFFFB74D)
        val sweep = ((phaseSeconds * (1.2f + norm) + idx * 0.25f) % 1f) * 20.dp.toPx()
        val len = (12f + 20f * norm).dp.toPx()
        drawLine(
            color = color.copy(alpha = 0.75f * norm + 0.15f),
            start = Offset(pt.screenX - len * 0.5f + sweep * 0.5f, pt.screenY - sweep * 0.2f),
            end = Offset(pt.screenX + len * 0.5f + sweep * 0.5f, pt.screenY - sweep * 0.2f - 4.dp.toPx()),
            strokeWidth = (2.2f + 1.4f * norm).dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPrecipCells(
    field: ScalarGridField,
    points: List<ProjectedSamplePoint>,
    pulse: Float
) {
    points.forEach { pt ->
        val precip = MapLayerDataProvider.interpolateScalar(field, pt.lat, pt.lon) ?: return@forEach
        if (precip <= 0.02) return@forEach
        val norm = (precip / 8.0).coerceIn(0.2, 1.0).toFloat()
        val color = if (precip >= 4.0) Color(0xFF7C4DFF) else Color(0xFF29B6F6)
        val radius = (22f + 24f * norm + 6f * pulse).dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.55f * norm), color.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(pt.screenX, pt.screenY),
                radius = radius
            ),
            radius = radius,
            center = Offset(pt.screenX, pt.screenY)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCloudPatches(
    field: ScalarGridField,
    points: List<ProjectedSamplePoint>,
    phaseSeconds: Float
) {
    points.forEachIndexed { idx, pt ->
        val clouds = MapLayerDataProvider.interpolateScalar(field, pt.lat, pt.lon) ?: return@forEachIndexed
        if (clouds < 10.0) return@forEachIndexed
        val norm = (clouds / 100.0).coerceIn(0.1, 1.0).toFloat()
        val driftX = sin(phaseSeconds * 0.7f + idx) * 6.dp.toPx()
        val radius = (26f + 22f * norm).dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFECEFF1).copy(alpha = 0.42f * norm), Color.Transparent),
                center = Offset(pt.screenX + driftX, pt.screenY),
                radius = radius
            ),
            radius = radius,
            center = Offset(pt.screenX + driftX, pt.screenY)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPressureIsobars(
    field: ScalarGridField,
    points: List<ProjectedSamplePoint>,
    pulse: Float,
    textPaint: Paint
) {
    points.forEachIndexed { idx, pt ->
        val hpa = MapLayerDataProvider.interpolateScalar(field, pt.lat, pt.lon) ?: return@forEachIndexed
        val isHigh = hpa >= 1015.0
        val color = if (isHigh) Color(0xFFFFCA28) else Color(0xFF42A5F5)
        val baseRadius = (20f + 5f * pulse).dp.toPx()
        drawCircle(
            color = color.copy(alpha = 0.55f),
            radius = baseRadius,
            center = Offset(pt.screenX, pt.screenY),
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = color.copy(alpha = 0.3f),
            radius = baseRadius * 1.45f,
            center = Offset(pt.screenX, pt.screenY),
            style = Stroke(width = 1.dp.toPx())
        )
        if (idx % 3 == 0) {
            val tag = if (isHigh) "H ${hpa.toInt()}" else "T ${hpa.toInt()}"
            drawContext.canvas.nativeCanvas.drawText(
                tag,
                pt.screenX,
                pt.screenY + 8f,
                textPaint
            )
        }
    }
}

private fun windColor(speedKmh: Double): Color = when {
    speedKmh < 15.0 -> Color(0xFF4FC3F7)
    speedKmh < 35.0 -> Color(0xFF66BB6A)
    speedKmh < 60.0 -> Color(0xFFFFCA28)
    else -> Color(0xFFFF5252)
}

private fun tempColor(tempC: Double): Color = when {
    tempC < 0.0 -> Color(0xFF42A5F5)
    tempC < 10.0 -> Color(0xFF26C6DA)
    tempC < 20.0 -> Color(0xFF66BB6A)
    tempC < 28.0 -> Color(0xFFFFB74D)
    else -> Color(0xFFEF5350)
}

private data class LayerDisplayMeta(
    val label: String,
    val sourceHint: String,
    val color: Color
)

private fun layerDisplayMeta(layer: MapWeatherLayer): LayerDisplayMeta = when (layer) {
    MapWeatherLayer.RADAR -> LayerDisplayMeta("Radar", "RainViewer", Color(0xFF29B6F6))
    MapWeatherLayer.SATELLITE -> LayerDisplayMeta("Satellit", "IR / Wolken", Color(0xFFB3E5FC))
    MapWeatherLayer.WIND -> LayerDisplayMeta("Wind", "Strömung", Color(0xFF66BB6A))
    MapWeatherLayer.GUSTS -> LayerDisplayMeta("Windböen", "Spitzenböen", Color(0xFFFFA726))
    MapWeatherLayer.HUMIDITY -> LayerDisplayMeta("Luftfeuchte", "Feuchte", Color(0xFF26C6DA))
    MapWeatherLayer.CAPE -> LayerDisplayMeta("CAPE-Index", "Gewitterenergie", Color(0xFFE040FB))
    MapWeatherLayer.TEMPERATURE -> LayerDisplayMeta("Temperatur", "Wärmefeld", Color(0xFFEF5350))
    MapWeatherLayer.PRECIPITATION -> LayerDisplayMeta("Niederschlag", "Regen/Schnee", Color(0xFF5C6BC0))
    MapWeatherLayer.CLOUDS -> LayerDisplayMeta("Wolken", "Bedeckung", Color(0xFFCFD8DC))
    MapWeatherLayer.PRESSURE -> LayerDisplayMeta("Luftdruck", "Isobaren", Color(0xFFFFCA28))
}

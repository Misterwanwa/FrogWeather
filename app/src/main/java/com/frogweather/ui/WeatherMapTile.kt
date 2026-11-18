package com.frogweather.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.frogweather.BuildConfig
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

// Available weather map layers from OpenWeatherMap (free tier)
enum class WeatherMapLayer(
    val displayName: String, 
    val layerCode: String,
    val legendUrl: String?
) {
    NONE("Kein Overlay", "", null),
    PRECIPITATION("Niederschlag", "precipitation", "https://openweathermap.org/img/legend/precipitation.png"),
    RAIN("Regen", "rain", "https://openweathermap.org/img/legend/rain.png"),
    CLOUDS("Wolken", "clouds", "https://openweathermap.org/img/legend/clouds.png"),
    TEMP("Temperatur", "temp", "https://openweathermap.org/img/legend/temp.png"),
    WIND("Wind", "wind", "https://openweathermap.org/img/legend/wind.png"),
    PRESSURE("Luftdruck", "pressure", "https://openweathermap.org/img/legend/pressure.png"),
    SNOW("Schnee", "snow", "https://openweathermap.org/img/legend/snow.png")
}

@Composable
fun WeatherMapTile(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedLayer by remember { mutableStateOf(WeatherMapLayer.PRECIPITATION) }
    
    // Initialize osmdroid configuration once
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
            // Performance optimizations
            cacheMapTileCount = 40  // More tiles cached in memory
            tileDownloadThreads = 4 // More parallel downloads
            tileFileSystemThreads = 4
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1C2733), shape = RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // Header with layer selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "WETTERKARTE",
                color = Color.White,
                fontSize = 14.sp,
                letterSpacing = 1.sp
            )
            
            // Layer selector dropdown
            LayerSelector(
                selectedLayer = selectedLayer,
                onLayerSelected = { selectedLayer = it }
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
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
                layer = selectedLayer
            )
            
            // Fix: Capture legendUrl in a local variable to allow smart-casting
            // This avoids the issue with selectedLayer being a delegated property
            selectedLayer.legendUrl?.let { url ->
                LegendImage(
                    legendUrl = url,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }
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

@Composable
private fun WeatherMap(
    latitude: Double,
    longitude: Double,
    layer: WeatherMapLayer
) {
    // Create the map view once and reuse it
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                
                // Performance: Pre-load tiles around center
                controller.setZoom(9.0) // Slightly less zoom for faster loading
                controller.setCenter(GeoPoint(latitude, longitude))
                
                // Initial overlay setup
                updateWeatherOverlay(this, layer)
            }
        },
        update = { mapView ->
            mapView.controller.setCenter(GeoPoint(latitude, longitude))
            updateWeatherOverlay(mapView, layer)
            mapView.invalidate()
        }
    )
}

private fun updateWeatherOverlay(mapView: MapView, layer: WeatherMapLayer) {
    // Remove existing weather overlays (TilesOverlay instances, but keep base map)
    mapView.overlays.removeAll { it is TilesOverlay && it != mapView.overlayManager.tilesOverlay }
    
    if (layer != WeatherMapLayer.NONE) {
        // Create tile source for weather overlay
        val weatherTileSource = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
            "OpenWeatherMap_${layer.name}",
            0,
            19,
            256,
            ".png",
            arrayOf("https://tile.openweathermap.org/map/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
                val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
                val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                return "https://tile.openweathermap.org/map/${layer.layerCode}/$z/$x/$y.png?appid=${BuildConfig.OPENWEATHER_API_KEY}"
            }
        }
        
        // Create and add tiles overlay
        val tileProvider = org.osmdroid.tileprovider.MapTileProviderBasic(mapView.context, weatherTileSource)
        val tilesOverlay = TilesOverlay(tileProvider, mapView.context).apply {
            loadingBackgroundColor = android.graphics.Color.TRANSPARENT
            loadingLineColor = android.graphics.Color.TRANSPARENT
            // Enable transparency for weather overlay
            setColorFilter(null)
        }
        
        mapView.overlays.add(tilesOverlay)
    }
}

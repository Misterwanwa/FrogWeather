package com.frogweather.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.frogweather.data.CityConfig
import com.frogweather.data.CityImageRepository
import com.frogweather.data.WeatherScenario
import com.frogweather.network.PhotonClient
import com.frogweather.network.PhotonFeature
import com.frogweather.ui.theme.FrogWeatherTheme
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class CityImageSettingsActivity : ComponentActivity() {
    
    companion object {
        private const val PREFS_NAME = "city_settings_prefs"
        private const val KEY_LAST_CITY_ID = "last_city_id"
        private const val KEY_LAST_CITY_NAME = "last_city_name"
        private const val KEY_LAST_CITY_COUNTRY = "last_city_country"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        
        fun saveLastCity(context: Context, city: CityConfig) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putString(KEY_LAST_CITY_ID, city.id)
                putString(KEY_LAST_CITY_NAME, city.name)
                putString(KEY_LAST_CITY_COUNTRY, city.country)
                putFloat(KEY_LAST_LAT, city.lat.toFloat())
                putFloat(KEY_LAST_LON, city.lon.toFloat())
                apply()
            }
        }
        
        fun loadLastCity(context: Context): CityConfig? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val id = prefs.getString(KEY_LAST_CITY_ID, null) ?: return null
            val name = prefs.getString(KEY_LAST_CITY_NAME, null) ?: return null
            val country = prefs.getString(KEY_LAST_CITY_COUNTRY, null)
            val lat = prefs.getFloat(KEY_LAST_LAT, 0f)
            val lon = prefs.getFloat(KEY_LAST_LON, 0f)
            
            if (lat == 0f && lon == 0f) return null
            
            return CityConfig(
                id = id,
                name = name,
                country = country,
                lat = lat.toDouble(),
                lon = lon.toDouble()
            )
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if we should highlight a specific scenario
        val highlightScenario = intent.getStringExtra("highlight_scenario")?.let { key ->
            WeatherScenario.values().find { it.key == key }
        }
        
        setContent {
            FrogWeatherTheme {
                CityImageSettingsScreen(
                    highlightScenario = highlightScenario,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
fun CityImageSettingsScreen(
    highlightScenario: WeatherScenario? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { CityImageRepository.getInstance(context) }
    val activity = context as? Activity
    
    var selectedCity by remember { mutableStateOf<CityConfig?>(null) }
    var showCitySearch by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Berechtigung benötigt um Bilder auszuwählen", Toast.LENGTH_LONG).show()
        }
    }
    
    // Request permission on first launch
    LaunchedEffect(Unit) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(permission)
    }
    
    // Load city - first try intent extras, then saved city
    LaunchedEffect(Unit) {
        val intent = activity?.intent
        val lat = intent?.getDoubleExtra("current_lat", 0.0) ?: 0.0
        val lon = intent?.getDoubleExtra("current_lon", 0.0) ?: 0.0
        
        // First try to find city by coordinates
        if (lat != 0.0 && lon != 0.0) {
            val city = repository.findCityByLocation(lat, lon)
            if (city != null) {
                selectedCity = city
                return@LaunchedEffect
            }
        }
        
        // Then try to load last used city from preferences
        val lastCity = CityImageSettingsActivity.loadLastCity(context)
        if (lastCity != null) {
            // Check if city already exists in repository
            val existingCity = repository.findCityByLocation(lastCity.lat, lastCity.lon)
            if (existingCity != null) {
                selectedCity = existingCity
            } else {
                // Create new city entry if not exists
                val newCity = lastCity.copy(id = "${lastCity.name}_${System.currentTimeMillis()}")
                repository.saveCity(newCity)
                selectedCity = newCity
            }
            return@LaunchedEffect
        }
        
        // If no city found, show search
        showCitySearch = true
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stadt-Bilder verwalten") },
                backgroundColor = Color(0xFF1C2733),
                contentColor = Color.White,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                showCitySearch || selectedCity == null -> {
                    CitySearchScreen(
                        onCitySelected = { city ->
                            selectedCity = city
                            showCitySearch = false
                            // Save as last city
                            CityImageSettingsActivity.saveLastCity(context, city)
                        },
                        onCreateCity = { name, country, lat, lon ->
                            val newCity = CityConfig(
                                id = "${name}_${System.currentTimeMillis()}",
                                name = name,
                                country = country,
                                lat = lat,
                                lon = lon
                            )
                            repository.saveCity(newCity)
                            selectedCity = newCity
                            showCitySearch = false
                            CityImageSettingsActivity.saveLastCity(context, newCity)
                        }
                    )
                }
                selectedCity != null -> {
                    CityImageManager(
                        city = selectedCity!!,
                        repository = repository,
                        highlightScenario = highlightScenario,
                        onCityChange = { showCitySearch = true },
                        permissionGranted = permissionGranted
                    )
                }
            }
        }
    }
}

@Composable
private fun CitySearchScreen(
    onCitySelected: (CityConfig) -> Unit,
    onCreateCity: (String, String?, Double, Double) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PhotonFeature>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Wähle eine Stadt",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = query,
            onValueChange = { 
                query = it
                if (it.length >= 2) {
                    scope.launch {
                        isLoading = true
                        try {
                            val result = PhotonClient.service.search(it)
                            suggestions = result.features
                        } catch (e: Exception) {
                            suggestions = emptyList()
                        }
                        isLoading = false
                    }
                } else {
                    suggestions = emptyList()
                }
            },
            label = { Text("Stadt suchen...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = Color.White,
                focusedBorderColor = Color(0xFF4FC3F7),
                unfocusedBorderColor = Color.White.copy(alpha = 0.5f)
            )
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn {
            items(suggestions) { feature ->
                val cityName = feature.properties.name ?: "Unbekannt"
                val country = feature.properties.country
                val displayName = if (country?.isNotEmpty() == true) "$cityName, $country" else cityName
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            val city = CityConfig(
                                id = "${feature.properties.name}_${System.currentTimeMillis()}",
                                name = cityName,
                                country = country,
                                lat = feature.geometry.coordinates[1],
                                lon = feature.geometry.coordinates[0]
                            )
                            onCitySelected(city)
                        },
                    backgroundColor = Color(0xFF2A3A4A)
                ) {
                    Text(
                        text = displayName,
                        color = Color.White,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun CityImageManager(
    city: CityConfig,
    repository: CityImageRepository,
    highlightScenario: WeatherScenario?,
    onCityChange: () -> Unit,
    permissionGranted: Boolean
) {
    var cityConfig by remember { mutableStateOf(city) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    // Refresh city config from repository
    LaunchedEffect(city.id) {
        repository.getAllCities().find { it.id == city.id }?.let {
            cityConfig = it
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = cityConfig.name,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                cityConfig.country?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp
                    )
                }
            }
            
            TextButton(onClick = onCityChange) {
                Text("Ändern", color = Color(0xFF4FC3F7))
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Lade Bilder für die verschiedenen Wetterszenarien hoch",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp
        )
        
        if (!permissionGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "⚠️ Berechtigung für Bildzugriff erforderlich",
                color = Color(0xFFFFA726),
                fontSize = 12.sp
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(WeatherScenario.values()) { scenario ->
                val isHighlighted = scenario == highlightScenario
                
                ScenarioCard(
                    scenario = scenario,
                    cityId = cityConfig.id,
                    repository = repository,
                    isHighlighted = isHighlighted,
                    onImageUpdated = {
                        repository.getAllCities().find { it.id == city.id }?.let {
                            cityConfig = it
                        }
                    },
                    permissionGranted = permissionGranted
                )
            }
        }
    }
}

@Composable
private fun ScenarioCard(
    scenario: WeatherScenario,
    cityId: String,
    repository: CityImageRepository,
    isHighlighted: Boolean,
    onImageUpdated: () -> Unit,
    permissionGranted: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showImagePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    // Load image from repository
    var bitmap by remember(cityId, scenario) { 
        mutableStateOf(repository.getImage(cityId, scenario))
    }
    
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { imageUri ->
            scope.launch {
                try {
                    // Copy file to app storage
                    val filename = saveImageToAppStorage(context, imageUri, cityId, scenario)
                    if (filename != null) {
                        repository.assignImageToScenario(cityId, scenario, filename)
                        // Refresh bitmap
                        bitmap = repository.getImage(cityId, scenario)
                        onImageUpdated()
                        Toast.makeText(context, "Bild gespeichert", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Fehler beim Speichern", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(
                width = if (isHighlighted) 4.dp else 0.dp,
                color = if (isHighlighted) Color(0xFF4FC3F7) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = permissionGranted) { 
                if (bitmap != null) {
                    showDeleteConfirm = true
                } else {
                    showImagePicker = true 
                }
            },
        backgroundColor = Color(0xFF2A3A4A),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = scenario.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Löschen",
                        tint = Color.White
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = scenario.emoji,
                        fontSize = 32.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = scenario.displayName,
                        color = Color.White,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Bild hinzufügen",
                        tint = if (permissionGranted) Color(0xFF4FC3F7) else Color.Gray
                    )
                }
            }
        }
    }
    
    if (showImagePicker) {
        AlertDialog(
            onDismissRequest = { showImagePicker = false },
            title = { Text(scenario.displayName) },
            text = { Text("Wähle ein Bild für dieses Wetterszenario aus deiner Galerie.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImagePicker = false
                        imagePicker.launch("image/*")
                    }
                ) {
                    Text("Galerie öffnen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImagePicker = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Bild löschen?") },
            text = { Text("Möchtest du das Bild für '${scenario.displayName}' löschen?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.deleteImage(cityId, scenario)
                            bitmap = null
                            onImageUpdated()
                        }
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Löschen", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

private suspend fun saveImageToAppStorage(
    context: Context,
    uri: Uri,
    cityId: String,
    scenario: WeatherScenario
): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val imagesDir = File(context.filesDir, "city_images").apply { mkdirs() }
        val filename = "${cityId}_${scenario.key}_${System.currentTimeMillis()}.jpg"
        val file = File(imagesDir, filename)
        
        FileOutputStream(file).use { output ->
            inputStream.copyTo(output)
        }
        inputStream.close()
        
        // Compress if needed
        compressImageIfNeeded(file)
        
        filename
    } catch (e: Exception) {
        null
    }
}

private fun compressImageIfNeeded(file: File) {
    val maxSize = 1024 * 1024 // 1MB
    if (file.length() <= maxSize) return
    
    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return
    var quality = 90
    
    do {
        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        }
        quality -= 10
    } while (file.length() > maxSize && quality > 30)
}

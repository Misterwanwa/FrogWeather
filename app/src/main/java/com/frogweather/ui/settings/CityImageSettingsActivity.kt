package com.frogweather.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frogweather.data.AppPrefsKeys
import com.frogweather.data.AppThemeId
import com.frogweather.data.CityConfig
import com.frogweather.data.CityImagePromptGenerator
import com.frogweather.data.CityImageRepository
import com.frogweather.data.SharedPreferencesKeyValueStore
import com.frogweather.data.ThemeRepository
import com.frogweather.data.WeatherScenario
import com.frogweather.network.PhotonClient
import com.frogweather.network.PhotonFeature
import com.frogweather.ui.PromptGeneratorDialog
import com.frogweather.ui.theme.FrogTheme
import com.frogweather.ui.theme.FrogWeatherTheme
import kotlinx.coroutines.launch

class CityImageSettingsActivity : ComponentActivity() {

    private val _selectedTheme = mutableStateOf(AppThemeId.AERO_CLASSIC)
    private lateinit var themeRepository: ThemeRepository

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
        themeRepository = ThemeRepository(
            SharedPreferencesKeyValueStore.forPrefs(this, AppPrefsKeys.PREFS_THEME)
        )
        _selectedTheme.value = themeRepository.getSelectedTheme()

        val highlightScenario = intent.getStringExtra("highlight_scenario")?.let { key ->
            WeatherScenario.entries.find { it.key == key }
        }

        setContent {
            val activeTheme by _selectedTheme
            FrogWeatherTheme(themeId = activeTheme) {
                CityImageSettingsScreen(
                    highlightScenario = highlightScenario,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::themeRepository.isInitialized) {
            _selectedTheme.value = themeRepository.getSelectedTheme()
        }
    }
}

@Composable
fun CityImageSettingsScreen(
    highlightScenario: WeatherScenario? = null,
    onBack: () -> Unit
) {
    val palette = FrogTheme.palette
    val context = LocalContext.current
    val repository = remember { CityImageRepository.getInstance(context) }
    val activity = context as? Activity

    var selectedCity by remember { mutableStateOf<CityConfig?>(null) }
    var showCitySearch by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Berechtigung benötigt um Bilder auszuwählen", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(permission)
    }

    // Load or auto-ensure city: first intent extras, then settings prefs, then main app prefs
    LaunchedEffect(Unit) {
        val intent = activity?.intent
        val hasLatLonExtras = intent?.hasExtra("current_lat") == true && intent.hasExtra("current_lon")
        val lat = intent?.getDoubleExtra("current_lat", 0.0) ?: 0.0
        val lon = intent?.getDoubleExtra("current_lon", 0.0) ?: 0.0
        val intentCityName = intent?.getStringExtra("current_city_name")
        val intentCityCountry = intent?.getStringExtra("current_city_country")

        if (hasLatLonExtras && !(lat == 0.0 && lon == 0.0)) {
            val existing = repository.findCityByLocation(lat, lon)
            val resolved = existing ?: if (!intentCityName.isNullOrBlank()) {
                repository.ensureCity(intentCityName, intentCityCountry, lat, lon)
            } else {
                val mainPrefs = context.getSharedPreferences(AppPrefsKeys.PREFS_MAIN, Context.MODE_PRIVATE)
                val fallbackName = mainPrefs.getString(AppPrefsKeys.KEY_LAST_CITY_NAME, null)
                val fallbackCountry = mainPrefs.getString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, null)
                repository.ensureCity(fallbackName ?: "Aktueller Ort", fallbackCountry, lat, lon)
            }
            CityImageSettingsActivity.saveLastCity(context, resolved)
            selectedCity = resolved
            return@LaunchedEffect
        }

        val lastSettingsCity = CityImageSettingsActivity.loadLastCity(context)
        if (lastSettingsCity != null) {
            val ensured = repository.ensureCity(
                name = lastSettingsCity.name,
                country = lastSettingsCity.country,
                lat = lastSettingsCity.lat,
                lon = lastSettingsCity.lon
            )
            CityImageSettingsActivity.saveLastCity(context, ensured)
            selectedCity = ensured
            return@LaunchedEffect
        }

        val mainPrefs = context.getSharedPreferences(AppPrefsKeys.PREFS_MAIN, Context.MODE_PRIVATE)
        val mainName = mainPrefs.getString(AppPrefsKeys.KEY_LAST_CITY_NAME, null)
        val mainCountry = mainPrefs.getString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, null)
        val mainLat = mainPrefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 0f).toDouble()
        val mainLon = mainPrefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 0f).toDouble()
        if (!mainName.isNullOrBlank() && !(mainLat == 0.0 && mainLon == 0.0)) {
            val ensured = repository.ensureCity(mainName, mainCountry, mainLat, mainLon)
            CityImageSettingsActivity.saveLastCity(context, ensured)
            selectedCity = ensured
            return@LaunchedEffect
        }

        showCitySearch = true
    }

    Scaffold(
        backgroundColor = palette.screenBackground,
        topBar = {
            TopAppBar(
                title = { Text("Stadt-Bilder verwalten", color = palette.textPrimary) },
                backgroundColor = palette.topBarBackground,
                contentColor = palette.textPrimary,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Zurück",
                            tint = palette.textPrimary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.screenBackground)
                .padding(padding)
        ) {
            when {
                showCitySearch || selectedCity == null -> {
                    CitySearchScreen(
                        onCitySelected = { name, country, lat, lon ->
                            val ensured = repository.ensureCity(name, country, lat, lon)
                            selectedCity = ensured
                            showCitySearch = false
                            CityImageSettingsActivity.saveLastCity(context, ensured)
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
    onCitySelected: (String, String?, Double, Double) -> Unit
) {
    val palette = FrogTheme.palette
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<PhotonFeature>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Wähle eine Stadt",
            color = palette.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
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
                        } catch (_: Exception) {
                            suggestions = emptyList()
                        }
                        isLoading = false
                    }
                } else {
                    suggestions = emptyList()
                }
            },
            label = { Text("Stadt suchen...") },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = palette.textSecondary) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = palette.textPrimary,
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.dividerColor,
                focusedLabelColor = palette.accent,
                unfocusedLabelColor = palette.textSecondary,
                cursorColor = palette.accent
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(suggestions) { feature ->
                val cityName = feature.properties.name ?: feature.cityQuery().ifBlank { "Unbekannt" }
                val country = feature.properties.country
                val displayName = if (!country.isNullOrBlank()) "$cityName, $country" else cityName
                val coords = feature.geometry.coordinates
                if (coords.size >= 2) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                onCitySelected(cityName, country, coords[1], coords[0])
                            },
                        backgroundColor = palette.tileSurfaceElevated
                    ) {
                        Text(
                            text = displayName,
                            color = palette.textPrimary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = palette.accent)
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
    val palette = FrogTheme.palette
    var cityConfig by remember(city.id) { mutableStateOf(city) }
    var showPromptDialog by remember { mutableStateOf(false) }

    LaunchedEffect(city.id) {
        repository.getAllCities().find { it.id == city.id }?.let {
            cityConfig = it
        }
    }

    val missingScenarios = remember(cityConfig) {
        repository.getMissingImages(cityConfig.id)
    }
    val cityLabel = remember(cityConfig.name, cityConfig.country) {
        CityImagePromptGenerator.formatCityLabel(cityConfig.name, cityConfig.country)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cityConfig.name,
                    color = palette.textPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                cityConfig.country?.let {
                    Text(
                        text = it,
                        color = palette.textSecondary,
                        fontSize = 16.sp
                    )
                }
            }

            TextButton(onClick = onCityChange) {
                Text("Ändern", color = palette.accent)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Lade Bilder für die verschiedenen Wetterszenarien hoch",
            color = palette.textSecondary,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Unconditionally enabled Prompt Generator button
        Button(
            onClick = { showPromptDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = palette.accent,
                contentColor = palette.screenBackground
            )
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "KI-Prompts generieren (${missingScenarios.size} fehlend)",
                fontWeight = FontWeight.SemiBold
            )
        }

        if (!permissionGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "⚠️ Berechtigung für Bildzugriff erforderlich",
                color = Color(0xFFFFA726),
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(WeatherScenario.entries) { scenario ->
                val isHighlighted = scenario == highlightScenario

                ScenarioCard(
                    scenario = scenario,
                    cityId = cityConfig.id,
                    repository = repository,
                    isHighlighted = isHighlighted,
                    onImageUpdated = {
                        repository.getAllCities().find { it.id == cityConfig.id }?.let {
                            cityConfig = it
                        }
                    },
                    permissionGranted = permissionGranted
                )
            }
        }
    }

    if (showPromptDialog) {
        PromptGeneratorDialog(
            cityLabel = cityLabel,
            missingScenarios = missingScenarios,
            highlightedScenario = highlightScenario,
            onDismiss = { showPromptDialog = false }
        )
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
    val palette = FrogTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showImagePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var bitmap by remember(cityId, scenario) {
        mutableStateOf(repository.getImage(cityId, scenario))
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { imageUri ->
            scope.launch {
                try {
                    val filename = repository.saveImage(cityId, scenario, imageUri)
                    if (filename != null) {
                        repository.assignImageToScenario(cityId, scenario, filename)
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
                color = if (isHighlighted) palette.accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = permissionGranted) {
                if (bitmap != null) {
                    showDeleteConfirm = true
                } else {
                    showImagePicker = true
                }
            },
        backgroundColor = palette.tileSurfaceElevated,
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
                        imageVector = Icons.Default.Delete,
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
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Bild hinzufügen",
                        tint = if (permissionGranted) palette.accent else Color.Gray
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

package com.frogweather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.frogweather.analysis.ForecastConfidenceEngine
import com.frogweather.analysis.HistoricalNormalCalculator
import com.frogweather.analysis.HourlyWeatherPoint
import com.frogweather.analysis.SportAdvisor
import com.frogweather.analysis.SportPreferenceRepository
import com.frogweather.analysis.SportType
import com.frogweather.assistant.AssistantAnswer
import com.frogweather.assistant.AssistantContext
import com.frogweather.assistant.AssistantResponseEngine
import com.frogweather.briefing.BriefingComposer
import com.frogweather.briefing.BriefingScheduleConfig
import com.frogweather.briefing.BriefingScheduler
import com.frogweather.briefing.BriefingTimeCalculator
import com.frogweather.briefing.BriefingType
import com.frogweather.briefing.ClothingAdvisor
import com.frogweather.calendar.CalendarEvent
import com.frogweather.calendar.CalendarRepository
import com.frogweather.calendar.EventRescheduler
import com.frogweather.calendar.RescheduleSuggestion
import com.frogweather.data.AppPrefsKeys
import com.frogweather.data.AppThemeId
import com.frogweather.data.CityImageRepository
import com.frogweather.data.FavoriteLocation
import com.frogweather.data.FavoritesRepository
import com.frogweather.data.ObservedWeatherPoint
import com.frogweather.data.SharedPreferencesKeyValueStore
import com.frogweather.data.ThemeRepository
import com.frogweather.data.TrackRecordRepository
import com.frogweather.data.WeatherScenario
import com.frogweather.map.GeoBounds
import com.frogweather.map.MapForecastModel
import com.frogweather.map.MapLayerData
import com.frogweather.map.MapLayerDataProvider
import com.frogweather.map.MapState
import com.frogweather.map.MapStateReducer
import com.frogweather.map.MapViewMode
import com.frogweather.map.MapWeatherLayer
import com.frogweather.map.PressureLevel
import com.frogweather.network.ForecastResponse
import com.frogweather.network.HistoricalBaselineData
import com.frogweather.network.OpenMeteoArchiveHistory
import com.frogweather.network.OpenMeteoClient
import com.frogweather.network.OpenMeteoForecastData
import com.frogweather.network.OpenMeteoModelIds
import com.frogweather.network.OsrmClient
import com.frogweather.network.OsrmRouteParser
import com.frogweather.network.PhotonClient
import com.frogweather.network.PhotonFeature
import com.frogweather.network.RainViewerClient
import com.frogweather.network.RetrofitClient
import com.frogweather.network.WeatherDetails
import com.frogweather.network.WeatherResponse
import com.frogweather.route.RoutePoint
import com.frogweather.route.RouteTransportMode
import com.frogweather.route.RouteWeatherCalculator
import com.frogweather.route.RouteWeatherResult
import com.frogweather.service.DwdWarningFetcher
import com.frogweather.service.WeatherNotificationService
import com.frogweather.service.WeatherPredictionModel
import com.frogweather.service.WeatherWarning
import com.frogweather.ui.FavoritesAndSettingsSheet
import com.frogweather.ui.LiveWeatherOverlay
import com.frogweather.ui.MissingImageBanner
import com.frogweather.ui.SettingsSheetTab
import com.frogweather.ui.WarningBanners
import com.frogweather.ui.WeatherMapTile
import com.frogweather.ui.analysis.AnalysisScreen
import com.frogweather.ui.analysis.AnalysisUiState
import com.frogweather.ui.map.WeatherMapScreen
import com.frogweather.ui.navigation.AppTab
import com.frogweather.ui.navigation.FrogBottomNavigationBar
import com.frogweather.ui.planner.BriefingCompactCard
import com.frogweather.ui.planner.PlannerScreen
import com.frogweather.ui.settings.CityImageSettingsActivity
import com.frogweather.ui.theme.FrogTheme
import com.frogweather.ui.theme.FrogWeatherTheme
import com.frogweather.widget.WeatherWidgetProvider
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var themeRepository: ThemeRepository
    private lateinit var sportPreferenceRepository: SportPreferenceRepository
    private lateinit var trackRecordRepository: TrackRecordRepository
    private lateinit var briefingScheduler: BriefingScheduler
    private var weatherFetchJob: Job? = null
    private var analysisRetryJob: Job? = null
    private var mapDataJob: Job? = null
    private var routeJob: Job? = null
    private var missingImagesJob: Job? = null
    private var calendarJob: Job? = null
    private var suggestionsJob: Job? = null

    // exposed state for UI
    private val _weatherState = mutableStateOf<WeatherType?>(null)
    val weatherState: State<WeatherType?> = _weatherState
    private val _temperature = mutableStateOf<Double?>(null)
    val temperature: State<Double?> = _temperature
    private val _feelsLike = mutableStateOf<Double?>(null)
    val feelsLike: State<Double?> = _feelsLike
    private val _tempMin = mutableStateOf<Double?>(null)
    val tempMin: State<Double?> = _tempMin
    private val _tempMax = mutableStateOf<Double?>(null)
    val tempMax: State<Double?> = _tempMax
    private val _locationName = mutableStateOf<String?>(null)
    val locationName: State<String?> = _locationName
    private val _country = mutableStateOf<String?>(null)
    val country: State<String?> = _country
    private val _rawWeatherMain = mutableStateOf<String?>(null)
    private val _isNight = mutableStateOf(false)
    val isNight: State<Boolean> = _isNight
    private val _loading = mutableStateOf(true)
    val loading: State<Boolean> = _loading

    // hourly and daily forecast entries
    private val _hourlyEntries = mutableStateListOf<HourlyEntry>()
    val hourlyEntries: List<HourlyEntry> = _hourlyEntries
    private val _dailyEntries = mutableStateListOf<DailyEntry>()
    val dailyEntries: List<DailyEntry> = _dailyEntries

    // rain alert: time string of next expected rain, null if none
    private val _rainAlert = mutableStateOf<String?>(null)
    val rainAlert: State<String?> = _rainAlert

    // detail grid state
    private val _windSpeed = mutableStateOf<Double?>(null)
    val windSpeed: State<Double?> = _windSpeed
    private val _windDeg = mutableStateOf<Int?>(null)
    val windDeg: State<Int?> = _windDeg
    private val _sunrise = mutableStateOf<Long?>(null)
    val sunrise: State<Long?> = _sunrise
    private val _sunset = mutableStateOf<Long?>(null)
    val sunset: State<Long?> = _sunset
    private val _pressure = mutableStateOf<Int?>(null)
    val pressure: State<Int?> = _pressure
    private val _humidity = mutableStateOf<Int?>(null)
    val humidity: State<Int?> = _humidity
    private val _visibilityM = mutableStateOf<Int?>(null)
    val visibilityM: State<Int?> = _visibilityM
    private val _rainMm = mutableStateOf<Double?>(null)
    val rainMm: State<Double?> = _rainMm
    private val _aqi = mutableStateOf<Int?>(null)
    val aqi: State<Int?> = _aqi
    private val _clouds = mutableStateOf<Int?>(null)
    val clouds: State<Int?> = _clouds
    
    // map coordinates
    private val _latitude = mutableStateOf<Double?>(null)
    val latitude: State<Double?> = _latitude
    private val _longitude = mutableStateOf<Double?>(null)
    val longitude: State<Double?> = _longitude
    
    // DWD warnings
    private val _warnings = mutableStateListOf<WeatherWarning>()
    val warnings: List<WeatherWarning> = _warnings
    
    // Missing image tracking
    private val _missingImageCount = mutableStateOf(0)
    val missingImageCount: State<Int> = _missingImageCount
    private val _currentWeatherScenario = mutableStateOf<WeatherScenario?>(null)
    val currentWeatherScenario: State<WeatherScenario?> = _currentWeatherScenario
    private val _missingScenarios = mutableStateListOf<WeatherScenario>()
    val missingScenarios: List<WeatherScenario> = _missingScenarios

    // Personalization, Theme & Navigation state
    private val _favorites = mutableStateListOf<FavoriteLocation>()
    val favorites: List<FavoriteLocation> = _favorites
    private val _activeFavoriteId = mutableStateOf<String?>(null)
    val activeFavoriteId: State<String?> = _activeFavoriteId
    private val _selectedTheme = mutableStateOf(AppThemeId.AERO_CLASSIC)
    val selectedTheme: State<AppThemeId> = _selectedTheme
    private val _selectedTab = mutableStateOf(AppTab.WEATHER)
    val selectedTab: State<AppTab> = _selectedTab
    private val _activeSheetTab = mutableStateOf<SettingsSheetTab?>(null)

    // Phase 2 Analysis, Sport & Track Record state
    private val _selectedSport = mutableStateOf(SportType.RUNNING)
    private val _selectedHistoryDays = mutableStateOf(14)
    private val _uvIndex = mutableStateOf<Double?>(null)
    private val _normalTempC = mutableStateOf<Double?>(null)
    private val _analysisUiState = mutableStateOf(AnalysisUiState())

    // Phase 3 MapLibre 2D/3D Globe, Timeline & Layer state
    private val _mapState = mutableStateOf(MapState())
    private val _mapLayerData = mutableStateOf(MapLayerData())
    private var lastMapBounds: GeoBounds? = null

    // Phase 4 Planner, Calendar, Route, Briefing & Free-Text Assistant state
    private val _allHourlyPoints = mutableStateOf<List<HourlyWeatherPoint>>(emptyList())
    private val _briefingSchedule = mutableStateOf(BriefingScheduleConfig())
    private val _hasCalendarPermission = mutableStateOf(false)
    private val _calendarEvents = mutableStateListOf<CalendarEvent>()
    private val _calendarError = mutableStateOf<String?>(null)
    private val _rescheduleSuggestions = mutableStateListOf<RescheduleSuggestion>()
    private val _routeOriginInput = mutableStateOf("")
    private val _routeDestinationInput = mutableStateOf("")
    private val _routeMode = mutableStateOf(RouteTransportMode.DRIVING)
    private val _routeArrivalOffsetHours = mutableStateOf(2)
    private val _routeResult = mutableStateOf<RouteWeatherResult?>(null)
    private val _routeLoading = mutableStateOf(false)
    private val _routeError = mutableStateOf<String?>(null)
    private var lastRouteCoords: Pair<Pair<Double, Double>, Pair<Double, Double>>? = null
    private var lastRouteDestLabel: String? = null
    private val _assistantAnswer = mutableStateOf<AssistantAnswer?>(null)

    // autocomplete suggestions from Photon API
    private val _suggestions = mutableStateListOf<PhotonFeature>()
    val suggestions: List<PhotonFeature> = _suggestions

    // simple data holder classes
    data class HourlyEntry(
        val time: String,
        val weather: WeatherType,
        val temp: Double,
        val pop: Double,
        val windSpeed: Double,
        val isInterpolated: Boolean,
        val isExtreme: Boolean
    )
    data class DailyEntry(val date: String, val dayName: String, val weather: WeatherType, val tempMin: Double, val tempMax: Double)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = (permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) ||
            (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false)
        if (locationGranted) {
            getLastLocation()
        } else {
            loadFallbackLocationOrIdle()
        }
    }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        _hasCalendarPermission.value = granted
        refreshCalendarEventsIfPermitted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favoritesRepository = FavoritesRepository(
            SharedPreferencesKeyValueStore.forPrefs(this, AppPrefsKeys.PREFS_FAVORITES)
        )
        themeRepository = ThemeRepository(
            SharedPreferencesKeyValueStore.forPrefs(this, AppPrefsKeys.PREFS_THEME)
        )
        sportPreferenceRepository = SportPreferenceRepository(
            SharedPreferencesKeyValueStore.forPrefs(this, AppPrefsKeys.PREFS_SPORT)
        )
        trackRecordRepository = TrackRecordRepository(
            SharedPreferencesKeyValueStore.forPrefs(this, AppPrefsKeys.PREFS_TRACK_RECORD)
        )
        briefingScheduler = BriefingScheduler(
            SharedPreferencesKeyValueStore.forPrefs(this, AppPrefsKeys.PREFS_BRIEFING)
        )
        _briefingSchedule.value = briefingScheduler.getConfig()
        BriefingScheduler.scheduleAll(this)
        _hasCalendarPermission.value = CalendarRepository.hasCalendarPermission(this)
        refreshPersonalizationState()
        refreshCalendarEventsIfPermitted()

        // Request permissions based on Android version
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())

        setContent {
            val activeTheme by _selectedTheme
            FrogWeatherTheme(themeId = activeTheme) {
                FrogWeatherApp(
                    currentWeather = weatherState.value,
                    temperature = temperature.value,
                    feelsLike = feelsLike.value,
                    tempMin = tempMin.value,
                    tempMax = tempMax.value,
                    rainAlert = rainAlert.value,
                    loading = loading.value,
                    hourly = hourlyEntries,
                    daily = dailyEntries,
                    locationName = locationName.value,
                    country = country.value,
                    isNight = isNight.value,
                    details = WeatherDetails(
                        windSpeed = windSpeed.value,
                        windDeg = windDeg.value,
                        sunrise = sunrise.value,
                        sunset = sunset.value,
                        pressure = pressure.value,
                        humidity = humidity.value,
                        visibilityM = visibilityM.value,
                        rainMm = rainMm.value,
                        aqi = aqi.value,
                        temperature = temperature.value,
                        uvIndex = _uvIndex.value,
                        normalTempC = _normalTempC.value
                    ),
                    latitude = latitude.value,
                    longitude = longitude.value,
                    warnings = warnings,
                    missingImageCount = missingImageCount.value,
                    currentWeatherScenario = currentWeatherScenario.value,
                    missingScenarios = missingScenarios,
                    suggestions = suggestions,
                    selectedTab = _selectedTab.value,
                    onTabSelected = { tab ->
                        if (tab != AppTab.MAP && _mapState.value.isPlaying) {
                            _mapState.value = MapStateReducer.setPlaying(_mapState.value, false)
                        }
                        _selectedTab.value = tab
                        if (tab == AppTab.ANALYSIS) {
                            ensureAnalysisLoadedIfNeeded()
                        } else if (tab == AppTab.MAP) {
                            ensureMapDataLoadedIfNeeded()
                        } else if (tab == AppTab.PLANNER) {
                            _hasCalendarPermission.value = CalendarRepository.hasCalendarPermission(this@MainActivity)
                            refreshCalendarEventsIfPermitted()
                        }
                    },
                    favorites = favorites,
                    activeFavoriteId = activeFavoriteId.value,
                    selectedTheme = activeTheme,
                    activeSheetTab = _activeSheetTab.value,
                    onOpenFavoritesSheet = { _activeSheetTab.value = SettingsSheetTab.FAVORITES },
                    onOpenSettingsSheet = { _activeSheetTab.value = SettingsSheetTab.THEMES },
                    onDismissSheet = { _activeSheetTab.value = null },
                    onToggleCurrentFavorite = { toggleCurrentLocationFavorite() },
                    onSaveSuggestionFavorite = { feature -> saveSuggestionAsFavorite(feature) },
                    onSelectFavorite = { fav ->
                        _activeSheetTab.value = null
                        selectFavoriteLocation(fav)
                    },
                    onRemoveFavorite = { favId -> removeFavoriteLocation(favId) },
                    onSelectTheme = { themeId ->
                        themeRepository.setSelectedTheme(themeId)
                        _selectedTheme.value = themeId
                    },
                    onOpenCityImageSettings = {
                        _activeSheetTab.value = null
                        val intent = Intent(this@MainActivity, CityImageSettingsActivity::class.java).apply {
                            _currentWeatherScenario.value?.let { putExtra("highlight_scenario", it.key) }
                            _latitude.value?.let { putExtra("current_lat", it) }
                            _longitude.value?.let { putExtra("current_lon", it) }
                            _locationName.value?.takeIf { it.isNotBlank() }?.let { putExtra("current_city_name", it) }
                            _country.value?.takeIf { it.isNotBlank() }?.let { putExtra("current_city_country", it) }
                        }
                        startActivity(intent)
                    },
                    onQueryChange = { fetchSuggestions(it) },
                    onSuggestionSelected = { feature ->
                        clearSuggestions()
                        val coords = feature.geometry.coordinates
                        if (coords.size >= 2) {
                            fetchWeather(coords[1], coords[0])
                        } else {
                            fetchWeatherByCity(feature.cityQuery())
                        }
                    },
                    onRefreshLocation = { getLastLocation() },
                    onSearch = { city -> clearSuggestions(); fetchWeatherByCity(city) },
                    analysisUiState = _analysisUiState.value,
                    selectedSport = _selectedSport.value,
                    selectedHistoryDays = _selectedHistoryDays.value,
                    onSelectSport = { sport -> updateSelectedSport(sport) },
                    onSelectHistoryDays = { days -> _selectedHistoryDays.value = days },
                    onRetryAnalysis = { retryAnalysisFetch() },
                    mapState = _mapState.value,
                    mapLayerData = _mapLayerData.value,
                    onSelectMapViewMode = { mode ->
                        _mapState.value = MapStateReducer.setViewMode(_mapState.value, mode)
                    },
                    onToggleMapLayer = { layer ->
                        _mapState.value = MapStateReducer.toggleLayer(_mapState.value, layer)
                    },
                    onClearMapLayers = {
                        _mapState.value = MapStateReducer.clearLayers(_mapState.value)
                    },
                    onSelectPressureLevel = { level ->
                        _mapState.value = MapStateReducer.setPressureLevel(_mapState.value, level)
                    },
                    onSelectMapModel = { model ->
                        _mapState.value = MapStateReducer.setModel(_mapState.value, model)
                        val lat = _latitude.value
                        val lon = _longitude.value
                        if (lat != null && lon != null) {
                            refreshMapLayerData(lat, lon, model, forceRefresh = false)
                        }
                    },
                    onSelectTimelineIndex = { index ->
                        _mapState.value = MapStateReducer.seekTimeline(_mapState.value, index)
                    },
                    onToggleMapPlay = {
                        _mapState.value = MapStateReducer.togglePlayback(_mapState.value)
                    },
                    onStepMapTimeline = { delta ->
                        _mapState.value = if (delta >= 0) {
                            MapStateReducer.stepForward(_mapState.value, loop = true)
                        } else {
                            MapStateReducer.stepBackward(_mapState.value, loop = true)
                        }
                    },
                    onToggleMapWarnings = {
                        _mapState.value = MapStateReducer.setShowWarnings(
                            _mapState.value,
                            !_mapState.value.showWarnings
                        )
                    },
                    onRefreshMapData = {
                        val lat = _latitude.value
                        val lon = _longitude.value
                        if (lat != null && lon != null) {
                            refreshMapLayerData(lat, lon, _mapState.value.model, forceRefresh = true)
                        }
                    },
                    onMapViewportBoundsChanged = { bounds ->
                        lastMapBounds = bounds
                        val centerLat = ((bounds.south + bounds.north) / 2.0).coerceIn(-85.0, 85.0)
                        val centerLon = if (bounds.west > bounds.east && (bounds.west - bounds.east) > 180.0) {
                            val unwrapped = (bounds.west + bounds.east + 360.0) / 2.0
                            if (unwrapped > 180.0) unwrapped - 360.0 else unwrapped
                        } else {
                            ((bounds.west + bounds.east) / 2.0).coerceIn(-180.0, 180.0)
                        }
                        val currentLoadedLat = _mapLayerData.value.loadedLat
                        val currentLoadedLon = _mapLayerData.value.loadedLon
                        val loadedGrid = _mapLayerData.value.gridPlan
                        val loadedLatSpan = if (loadedGrid != null && loadedGrid.lats.size >= 2) {
                            abs(loadedGrid.lats.last() - loadedGrid.lats.first()).coerceAtLeast(0.6)
                        } else {
                            null
                        }
                        val loadedLonSpan = if (loadedGrid != null && loadedGrid.lons.size >= 2) {
                            val firstLon = loadedGrid.lons.first()
                            val lastLon = loadedGrid.lons.last()
                            if (firstLon > lastLon && (firstLon - lastLon) > 180.0) {
                                ((lastLon + 360.0) - firstLon).coerceAtLeast(0.6)
                            } else {
                                abs(lastLon - firstLon).coerceAtLeast(0.6)
                            }
                        } else {
                            null
                        }
                        val newLatSpan = abs(bounds.north - bounds.south).coerceAtLeast(0.6)
                        val newLonSpan = if (bounds.west > bounds.east && (bounds.west - bounds.east) > 180.0) {
                            ((bounds.east + 360.0) - bounds.west).coerceAtLeast(0.6)
                        } else {
                            abs(bounds.east - bounds.west).coerceAtLeast(0.6)
                        }
                        val centerMovedSignificantly = currentLoadedLat != null &&
                            currentLoadedLon != null &&
                            (abs(centerLat - currentLoadedLat) > 2.5 || abs(centerLon - currentLoadedLon) > 2.5)
                        val spanChangedSignificantly = (loadedLatSpan != null &&
                            (newLatSpan / loadedLatSpan > 2.5 || loadedLatSpan / newLatSpan > 2.5)) ||
                            (loadedLonSpan != null &&
                                (newLonSpan / loadedLonSpan > 2.5 || loadedLonSpan / newLonSpan > 2.5))
                        if (_selectedTab.value == AppTab.MAP &&
                            centerLat.isFinite() &&
                            centerLon.isFinite() &&
                            currentLoadedLat != null &&
                            currentLoadedLon != null &&
                            !_mapLayerData.value.isLoading &&
                            (centerMovedSignificantly || spanChangedSignificantly)
                        ) {
                            refreshMapLayerData(centerLat, centerLon, _mapState.value.model, forceRefresh = false)
                        }
                    },
                    briefingSchedule = _briefingSchedule.value,
                    hasCalendarPermission = _hasCalendarPermission.value,
                    calendarEvents = _calendarEvents,
                    calendarError = _calendarError.value,
                    rescheduleSuggestions = _rescheduleSuggestions,
                    routeOriginInput = _routeOriginInput.value,
                    routeDestinationInput = _routeDestinationInput.value,
                    routeMode = _routeMode.value,
                    routeArrivalOffsetHours = _routeArrivalOffsetHours.value,
                    routeResult = _routeResult.value,
                    routeLoading = _routeLoading.value,
                    routeError = _routeError.value,
                    assistantAnswer = _assistantAnswer.value,
                    onAskAssistant = { query -> askAssistant(query) },
                    onUpdateMorningSchedule = { enabled, hour, minute ->
                        briefingScheduler.setMorningSchedule(hour = hour, minute = minute, enabled = enabled)
                        _briefingSchedule.value = briefingScheduler.getConfig()
                        BriefingScheduler.scheduleAll(this@MainActivity)
                    },
                    onUpdateEveningSchedule = { enabled, hour, minute ->
                        briefingScheduler.setEveningCheckSchedule(hour = hour, minute = minute, enabled = enabled)
                        _briefingSchedule.value = briefingScheduler.getConfig()
                        BriefingScheduler.scheduleAll(this@MainActivity)
                    },
                    onTriggerTestBriefing = {
                        BriefingScheduler.triggerImmediateBriefing(this@MainActivity, BriefingType.MORNING)
                        Toast.makeText(this@MainActivity, "Morgen-Briefing wird gesendet...", Toast.LENGTH_SHORT).show()
                    },
                    onRequestCalendarPermission = {
                        calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    },
                    onRefreshCalendar = { refreshCalendarEventsIfPermitted() },
                    onRouteOriginChange = { _routeOriginInput.value = it },
                    onRouteDestinationChange = { _routeDestinationInput.value = it },
                    onRouteModeChange = { _routeMode.value = it },
                    onRouteArrivalOffsetChange = { _routeArrivalOffsetHours.value = it },
                    onCalculateRoute = { calculateRouteWeather() },
                    onOpenInMaps = { openRouteInMaps() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::themeRepository.isInitialized && ::favoritesRepository.isInitialized) {
            refreshPersonalizationState()
        }
        _hasCalendarPermission.value = CalendarRepository.hasCalendarPermission(this)
        refreshCalendarEventsIfPermitted()
        val lat = _latitude.value
        val lon = _longitude.value
        val weather = _weatherState.value
        if (lat != null && lon != null && weather != null) {
            checkMissingImages(lat, lon, weather, _rawWeatherMain.value ?: weather.name)
        }
    }

    private fun refreshPersonalizationState() {
        _selectedTheme.value = themeRepository.getSelectedTheme()
        _favorites.clear()
        _favorites.addAll(favoritesRepository.getFavorites())
        _activeFavoriteId.value = favoritesRepository.getActiveFavoriteId()
        if (::sportPreferenceRepository.isInitialized) {
            _selectedSport.value = sportPreferenceRepository.getSelectedSport()
        }
        if (::trackRecordRepository.isInitialized) {
            val summary = trackRecordRepository.getTrackRecordSummary(_latitude.value, _longitude.value)
            _analysisUiState.value = _analysisUiState.value.copy(trackRecord = summary)
        }
    }

    @SuppressLint("MissingPermission") // permission is requested before call
    private fun getLastLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                fetchWeather(location.latitude, location.longitude)
            } else {
                loadFallbackLocationOrIdle()
            }
        }.addOnFailureListener {
            loadFallbackLocationOrIdle()
        }
    }

    private fun loadFallbackLocationOrIdle() {
        val activeId = favoritesRepository.getActiveFavoriteId()
        val activeFavorite = activeId?.let { id ->
            favoritesRepository.getFavorites().firstOrNull { it.id == id }
        }
        if (activeFavorite != null) {
            saveLastCity(activeFavorite.name, activeFavorite.country, activeFavorite.latitude, activeFavorite.longitude)
            fetchWeather(activeFavorite.latitude, activeFavorite.longitude)
            return
        }

        val lastCity = loadLastCity()
        if (lastCity != null) {
            fetchWeather(lastCity.first, lastCity.second)
            return
        }

        _weatherState.value = WeatherType.OTHER
        _loading.value = false
    }

    private fun loadLastCity(): Pair<Double, Double>? {
        val prefs = getSharedPreferences(AppPrefsKeys.PREFS_MAIN, MODE_PRIVATE)
        val lat = prefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 0f).toDouble()
        val lon = prefs.getFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 0f).toDouble()
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }

    private fun toggleCurrentLocationFavorite() {
        val name = _locationName.value?.takeIf { it.isNotBlank() } ?: return
        val lat = _latitude.value ?: return
        val lon = _longitude.value ?: return
        val existing = favoritesRepository.findMatchingFavorite(lat, lon)
        if (existing != null) {
            val updated = favoritesRepository.removeFavorite(existing.id)
            _favorites.clear()
            _favorites.addAll(updated)
            _activeFavoriteId.value = favoritesRepository.getActiveFavoriteId()
            Toast.makeText(this, "$name aus Favoriten entfernt", Toast.LENGTH_SHORT).show()
        } else {
            val created = FavoriteLocation(
                id = "${name}_${System.currentTimeMillis()}",
                name = name,
                country = _country.value.orEmpty(),
                latitude = lat,
                longitude = lon,
                addedAtMillis = System.currentTimeMillis()
            )
            val updated = favoritesRepository.addFavorite(created)
            val saved = favoritesRepository.findMatchingFavorite(lat, lon) ?: created
            favoritesRepository.setActiveFavoriteId(saved.id)
            _favorites.clear()
            _favorites.addAll(updated)
            _activeFavoriteId.value = saved.id
            Toast.makeText(this, "$name zu Favoriten hinzugefügt", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSuggestionAsFavorite(feature: PhotonFeature) {
        val coords = feature.geometry.coordinates
        if (coords.size < 2) return
        val lon = coords[0]
        val lat = coords[1]
        val name = feature.properties.name ?: feature.cityQuery().ifBlank { return }
        val country = feature.properties.country.orEmpty()
        val created = FavoriteLocation(
            id = "${name}_${System.currentTimeMillis()}",
            name = name,
            country = country,
            latitude = lat,
            longitude = lon,
            addedAtMillis = System.currentTimeMillis()
        )
        val updated = favoritesRepository.addFavorite(created)
        _favorites.clear()
        _favorites.addAll(updated)
        Toast.makeText(this, "$name gespeichert", Toast.LENGTH_SHORT).show()
    }

    private fun selectFavoriteLocation(fav: FavoriteLocation) {
        favoritesRepository.setActiveFavoriteId(fav.id)
        _activeFavoriteId.value = fav.id
        saveLastCity(fav.name, fav.country, fav.latitude, fav.longitude)
        fetchWeather(fav.latitude, fav.longitude)
    }

    private fun removeFavoriteLocation(id: String) {
        val updated = favoritesRepository.removeFavorite(id)
        _favorites.clear()
        _favorites.addAll(updated)
        _activeFavoriteId.value = favoritesRepository.getActiveFavoriteId()
    }

    private fun fetchWeatherByCity(city: String) {
        _loading.value = true
        weatherFetchJob?.cancel()
        analysisRetryJob?.cancel()
        mapDataJob?.cancel()
        routeJob?.cancel()
        missingImagesJob?.cancel()
        _routeLoading.value = false
        weatherFetchJob = lifecycleScope.launch {
            try {
                val response = RetrofitClient.service.currentWeatherByCity(city, BuildConfig.OPENWEATHER_API_KEY)
                val lat = response.coord.lat
                val lon = response.coord.lon
                applyCurrentWeatherResponse(lat, lon, response)
                _loading.value = false
                fetchAllLocationData(lat, lon, response, owmCityOverride = city)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _weatherState.value = WeatherType.OTHER
                _rawWeatherMain.value = null
                _loading.value = false
            }
        }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        _loading.value = true
        weatherFetchJob?.cancel()
        analysisRetryJob?.cancel()
        mapDataJob?.cancel()
        routeJob?.cancel()
        missingImagesJob?.cancel()
        _routeLoading.value = false
        weatherFetchJob = lifecycleScope.launch {
            try {
                val response = RetrofitClient.service.currentWeather(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
                applyCurrentWeatherResponse(lat, lon, response)
                _loading.value = false
                fetchAllLocationData(lat, lon, response, owmCityOverride = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _weatherState.value = WeatherType.OTHER
                _rawWeatherMain.value = null
                _loading.value = false
            }
        }
    }

    private fun applyCurrentWeatherResponse(
        lat: Double,
        lon: Double,
        response: WeatherResponse
    ) {
        analysisRetryJob?.cancel()
        mapDataJob?.cancel()
        lastMapBounds = null
        val mainLabel = response.weather.firstOrNull()?.main ?: ""
        val weather = WeatherType.fromString(mainLabel)
        _weatherState.value = weather
        _temperature.value = response.main.temp
        _feelsLike.value = response.main.feelsLike
        _tempMin.value = response.main.tempMin
        _tempMax.value = response.main.tempMax
        _locationName.value = response.name
        _country.value = response.sys?.country
        _rawWeatherMain.value = mainLabel
        _windSpeed.value = response.wind?.speed
        _windDeg.value = response.wind?.deg
        _sunrise.value = response.sys?.sunrise
        _sunset.value = response.sys?.sunset
        _pressure.value = response.main.pressure.takeIf { it > 0 }
        _humidity.value = response.main.humidity.takeIf { it >= 0 }
        _visibilityM.value = response.visibility
        _rainMm.value = response.rain?.oneHour ?: 0.0
        _clouds.value = response.clouds?.all
        _latitude.value = lat
        _longitude.value = lon
        _uvIndex.value = null
        _normalTempC.value = null

        val matchingFavorite = favoritesRepository.findMatchingFavorite(lat, lon)
        favoritesRepository.setActiveFavoriteId(matchingFavorite?.id)
        _activeFavoriteId.value = matchingFavorite?.id

        saveLastCity(response.name, response.sys?.country, lat, lon)
        saveCachedCurrentWeather(
            tempC = response.main.temp,
            condition = weather.label,
            weatherType = weather,
            rawMain = mainLabel,
            sunriseSec = response.sys?.sunrise,
            sunsetSec = response.sys?.sunset
        )
        WeatherNotificationService.startService(this@MainActivity, lat, lon)
        updateWidget()
        checkMissingImages(lat, lon, weather, mainLabel)
        if (_selectedTab.value == AppTab.MAP) {
            refreshMapLayerData(lat, lon, _mapState.value.model, forceRefresh = false)
        }
    }

    private suspend fun fetchAllLocationData(
        lat: Double,
        lon: Double,
        currentResponse: WeatherResponse,
        owmCityOverride: String?
    ) = supervisorScope {
        analysisRetryJob?.cancel()
        val trackSummary = trackRecordRepository.getTrackRecordSummary(lat, lon)
        _analysisUiState.value = AnalysisUiState(
            isLoadingForecast = true,
            isLoadingHistory = true,
            currentTempC = currentResponse.main.temp,
            trackRecord = trackSummary,
            loadedLat = lat,
            loadedLon = lon
        )

        val owmForecastDeferred = async {
            runCatchingNonFatal {
                if (!owmCityOverride.isNullOrBlank()) {
                    RetrofitClient.service.forecastByCity(owmCityOverride, BuildConfig.OPENWEATHER_API_KEY)
                } else {
                    RetrofitClient.service.forecast(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
                }
            }
        }
        val airPollutionDeferred = async {
            runCatchingNonFatal {
                RetrofitClient.service.airPollution(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
            }
        }
        val dwdWarningsDeferred = async {
            runCatchingNonFatal { DwdWarningFetcher.fetchWarnings(lat, lon) }
        }
        val openMeteoForecastDeferred = async {
            runCatchingNonFatal { OpenMeteoClient.fetchMultiModelForecast(lat, lon, forecastDays = 5) }
        }
        val recentHistoryDeferred = async {
            runCatchingNonFatal { OpenMeteoClient.fetchRecentHistory(lat, lon, days = 30) }
        }
        val baselineDeferred = async {
            runCatchingNonFatal { OpenMeteoClient.fetchSameDateWindowBaseline(lat, lon) }
        }

        val dwdWarnings = dwdWarningsDeferred.await()
        if (dwdWarnings != null) {
            _warnings.clear()
            _warnings.addAll(dwdWarnings)
        }

        val airPollution = airPollutionDeferred.await()
        if (airPollution != null) {
            _aqi.value = airPollution.list.firstOrNull()?.main?.aqi
        }

        val owmForecast = owmForecastDeferred.await()
        if (owmForecast != null) {
            updateForecastStates(owmForecast)
        }

        val openMeteoForecast = openMeteoForecastDeferred.await()
        val recentHistory = recentHistoryDeferred.await()
        val baseline = baselineDeferred.await()

        applyAnalysisResults(
            lat = lat,
            lon = lon,
            cityName = currentResponse.name ?: _locationName.value.orEmpty(),
            currentTempC = currentResponse.main.temp,
            observedRainMm = currentResponse.rain?.oneHour ?: 0.0,
            observedWindKmh = (currentResponse.wind?.speed ?: 0.0) * 3.6,
            owmForecast = owmForecast,
            openMeteoForecast = openMeteoForecast,
            recentHistory = recentHistory,
            baseline = baseline
        )
    }

    private fun applyAnalysisResults(
        lat: Double,
        lon: Double,
        cityName: String,
        currentTempC: Double?,
        observedRainMm: Double,
        observedWindKmh: Double,
        owmForecast: ForecastResponse?,
        openMeteoForecast: OpenMeteoForecastData?,
        recentHistory: OpenMeteoArchiveHistory?,
        baseline: HistoricalBaselineData?
    ) {
        val activeLat = _latitude.value
        val activeLon = _longitude.value
        if (activeLat == null || activeLon == null ||
            abs(activeLat - lat) > 1e-6 || abs(activeLon - lon) > 1e-6
        ) {
            return
        }

        val currentState = _analysisUiState.value
        val allHourlyPoints = openMeteoForecast?.toHourlyWeatherPoints().orEmpty()
        _allHourlyPoints.value = allHourlyPoints
        val nowMillis = System.currentTimeMillis()
        val currentHourPoint = allHourlyPoints.minByOrNull { abs(it.epochMillis - nowMillis) }
        val currentUv = currentHourPoint?.uvIndex
        val dedupedHourlyPoints = allHourlyPoints
            .sortedBy { it.epochMillis }
            .distinctBy { it.epochMillis }
        val hourlyPoints = dedupedHourlyPoints
            .filter { it.epochMillis >= nowMillis - 1_800_000L }
            .ifEmpty { dedupedHourlyPoints }
            .take(24)
        val peakPoint = hourlyPoints
            .filter { it.uvIndex.isFinite() }
            .maxByOrNull { it.uvIndex }
        val utcOffsetSeconds = openMeteoForecast?.utcOffsetSeconds ?: 0

        val peakUvHourLabel = peakPoint?.let { pt ->
            val offset = runCatching { ZoneOffset.ofTotalSeconds(utcOffsetSeconds) }.getOrDefault(ZoneOffset.UTC)
            Instant.ofEpochMilli(pt.epochMillis)
                .atOffset(offset)
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN))
        }

        val sport = _selectedSport.value
        val sportVerdict = if (hourlyPoints.isNotEmpty()) {
            SportAdvisor.evaluateHoursToSkip(sport, hourlyPoints)
        } else {
            null
        }
        val bestWindow = if (hourlyPoints.isNotEmpty()) {
            SportAdvisor.findBestSportWindow(sport, hourlyPoints, openMeteoForecast?.utcOffsetSeconds)
        } else {
            null
        }

        val confidence = if (openMeteoForecast != null) {
            val modelTemps = openMeteoForecast.comparisonModelTemps().toMutableMap()
            val modelPrecips = openMeteoForecast.comparisonModelPrecips().toMutableMap()
            val modelWinds = openMeteoForecast.comparisonModelWinds().toMutableMap()
            if (owmForecast != null) {
                val alignedOwm = openMeteoForecast.alignOwmForecast(owmForecast)
                if (alignedOwm.temps.any { it != null }) modelTemps[OpenMeteoModelIds.OWM] = alignedOwm.temps
                if (alignedOwm.precips.any { it != null }) modelPrecips[OpenMeteoModelIds.OWM] = alignedOwm.precips
                if (alignedOwm.windsKmh.any { it != null }) modelWinds[OpenMeteoModelIds.OWM] = alignedOwm.windsKmh
            }
            ForecastConfidenceEngine.computeConfidence(modelTemps, modelPrecips, modelWinds)
        } else {
            null
        }

        // Track Record: verify due past snapshots BEFORE saving new future snapshots
        if (currentTempC != null && currentTempC.isFinite()) {
            trackRecordRepository.verifyCurrentObservation(
                latitude = lat,
                longitude = lon,
                observed = ObservedWeatherPoint(
                    epochMillis = nowMillis,
                    tempC = currentTempC,
                    precipMm = observedRainMm,
                    windKmh = observedWindKmh
                )
            )
        }
        if (openMeteoForecast != null) {
            trackRecordRepository.saveForecastSnapshotsFromForecast(
                latitude = lat,
                longitude = lon,
                cityName = cityName,
                forecast = openMeteoForecast,
                owmForecast = owmForecast,
                sport = sport,
                capturedAtMillis = nowMillis
            )
        }
        val updatedTrackRecord = trackRecordRepository.getTrackRecordSummary(lat, lon)

        // History 30d series
        val historySeries30 = if (recentHistory != null) {
            HistoricalNormalCalculator.buildHistorySeries(
                dailyDates = recentHistory.dates,
                mins = recentHistory.dailyMinC.map { it ?: Double.NaN },
                maxs = recentHistory.dailyMaxC.map { it ?: Double.NaN },
                means = recentHistory.dailyMeanC.map { it ?: Double.NaN },
                days = 30
            )
        } else {
            null
        }

        // Sinusoidal normal curve & delta at location's current local hour
        val zoneOffset = runCatching { ZoneOffset.ofTotalSeconds(utcOffsetSeconds) }.getOrDefault(ZoneOffset.UTC)
        val localNow = ZonedDateTime.now(zoneOffset)
        val hourOfDay = localNow.hour + localNow.minute / 60.0
        val normalMin = baseline?.meanDailyMinC ?: _tempMin.value
        val normalMax = baseline?.meanDailyMaxC ?: _tempMax.value
        val normalComparison = if (normalMin != null && normalMax != null && currentTempC != null) {
            HistoricalNormalCalculator.compareWithNormal(currentTempC, normalMin, normalMax, hourOfDay)
        } else {
            null
        }
        val normalCurve24h = if (normalMin != null && normalMax != null) {
            HistoricalNormalCalculator.build24HourNormalCurve(normalMin, normalMax)
        } else {
            emptyList()
        }

        _uvIndex.value = currentUv
        _normalTempC.value = normalComparison?.normalTempC

        val forecastError = if (openMeteoForecast == null) {
            "Multi-Modell-Vorhersage konnte nicht geladen werden."
        } else {
            null
        }
        val historyError = if (recentHistory == null && baseline == null) {
            "Historische Klimadaten konnten nicht geladen werden."
        } else {
            null
        }

        _analysisUiState.value = currentState.copy(
            isLoadingForecast = false,
            isLoadingHistory = false,
            forecastError = forecastError,
            historyError = historyError,
            currentTempC = currentTempC,
            currentUvIndex = currentUv,
            peakUvIndex = peakPoint?.uvIndex,
            peakUvHourLabel = peakUvHourLabel,
            utcOffsetSeconds = utcOffsetSeconds,
            normalComparison = normalComparison,
            dailyNormalMinC = normalMin,
            dailyNormalMaxC = normalMax,
            currentHourOfDay = hourOfDay,
            normalCurve24h = normalCurve24h,
            history30Days = historySeries30,
            hourlyPoints = hourlyPoints,
            sportVerdict = sportVerdict,
            bestSportWindow = bestWindow,
            confidence = confidence,
            trackRecord = updatedTrackRecord,
            loadedLat = lat,
            loadedLon = lon
        )
        recomputeCalendarReschedules()
    }

    private fun updateSelectedSport(sport: SportType) {
        _selectedSport.value = sport
        sportPreferenceRepository.setSelectedSport(sport)
        val points = _analysisUiState.value.hourlyPoints
        if (points.isNotEmpty()) {
            _analysisUiState.value = _analysisUiState.value.copy(
                sportVerdict = SportAdvisor.evaluateHoursToSkip(sport, points),
                bestSportWindow = SportAdvisor.findBestSportWindow(
                    sport,
                    points,
                    _analysisUiState.value.utcOffsetSeconds
                )
            )
        }
        recomputeCalendarReschedules()
    }

    private fun ensureAnalysisLoadedIfNeeded() {
        val lat = _latitude.value ?: return
        val lon = _longitude.value ?: return
        val state = _analysisUiState.value
        val sameCoords = state.loadedLat != null && state.loadedLon != null &&
            abs(state.loadedLat - lat) <= 0.01 && abs(state.loadedLon - lon) <= 0.01
        val needsRetry = !sameCoords ||
            ((state.hourlyPoints.isEmpty() || state.history30Days == null) &&
                !state.isLoadingForecast && !state.isLoadingHistory)
        if (needsRetry) {
            retryAnalysisFetch()
        }
    }

    private fun ensureMapDataLoadedIfNeeded() {
        val lat = _latitude.value ?: return
        val lon = _longitude.value ?: return
        val data = _mapLayerData.value
        val activeModel = _mapState.value.model
        val sameCoords = data.loadedLat != null && data.loadedLon != null &&
            abs(data.loadedLat - lat) <= 0.05 && abs(data.loadedLon - lon) <= 0.05
        val sameModel = data.loadedModel == activeModel
        val hasContent = data.rainViewer != null || data.gridForecasts.isNotEmpty()
        if (!sameCoords || !sameModel || (!hasContent && !data.isLoading)) {
            refreshMapLayerData(lat, lon, activeModel, forceRefresh = false)
        }
    }

    private fun refreshMapLayerData(
        lat: Double,
        lon: Double,
        model: MapForecastModel,
        forceRefresh: Boolean
    ) {
        val bounds = lastMapBounds ?: GeoBounds(
            south = (lat - 2.0).coerceIn(-85.0, 85.0),
            west = (lon - 2.5).coerceIn(-180.0, 180.0),
            north = (lat + 2.0).coerceIn(-85.0, 85.0),
            east = (lon + 2.5).coerceIn(-180.0, 180.0)
        )
        val gridPlan = MapLayerDataProvider.buildSampleGrid(
            bounds = bounds,
            rows = 4,
            cols = 4,
            minSpanDeg = 0.6
        )

        mapDataJob?.cancel()
        mapDataJob = lifecycleScope.launch {
            supervisorScope {
                _mapLayerData.value = _mapLayerData.value.copy(
                    isLoading = true,
                    errorMessage = null
                )

                val rainViewerDeferred = async {
                    runCatchingNonFatal {
                        RainViewerClient.fetchWeatherMaps(forceRefresh = forceRefresh)
                    }
                }
                val gridForecastsDeferred = async {
                    runCatchingNonFatal {
                        OpenMeteoClient.fetchForecastGridForModel(
                            coordinates = gridPlan.coordinates,
                            modelId = model.id,
                            hourlyVars = MapLayerDataProvider.ALL_M3_HOURLY_VARIABLES,
                            forecastDays = 3
                        )
                    }
                }

                val rainViewer = rainViewerDeferred.await()
                val gridForecasts = gridForecastsDeferred.await().orEmpty()
                val sampleForecast = gridForecasts.firstOrNull()
                val forecastTimestamps = sampleForecast?.timestampsEpochMillis.orEmpty()
                val zoneOffsetSeconds = sampleForecast?.utcOffsetSeconds ?: 0

                val (frames, defaultIndex) = MapLayerDataProvider.buildTimelineFrames(
                    rainViewer = rainViewer,
                    forecastTimestampsMillis = forecastTimestamps,
                    nowEpochMillis = System.currentTimeMillis(),
                    zoneOffsetSeconds = zoneOffsetSeconds
                )

                if (frames.isNotEmpty()) {
                    _mapState.value = MapStateReducer.setTimelineFrames(
                        state = _mapState.value,
                        frames = frames,
                        initialIndex = if (_mapState.value.timelineFrames.isEmpty()) defaultIndex else null
                    )
                }

                val errorMsg = if (rainViewer == null && gridForecasts.isEmpty()) {
                    "Kartendaten nicht erreichbar"
                } else {
                    null
                }

                _mapLayerData.value = MapLayerData(
                    rainViewer = rainViewer,
                    gridPlan = gridPlan,
                    gridForecasts = gridForecasts,
                    loadedLat = lat,
                    loadedLon = lon,
                    loadedModel = model,
                    isLoading = false,
                    errorMessage = errorMsg,
                    lastUpdatedEpochMillis = System.currentTimeMillis()
                )
            }
        }
    }

    private fun retryAnalysisFetch() {
        val lat = _latitude.value ?: return
        val lon = _longitude.value ?: return
        val cityName = _locationName.value.orEmpty()
        val currentTemp = _temperature.value
        val observedRain = _rainMm.value ?: 0.0
        val observedWindKmh = (_windSpeed.value ?: 0.0) * 3.6

        analysisRetryJob?.cancel()
        analysisRetryJob = lifecycleScope.launch {
            supervisorScope {
                _analysisUiState.value = _analysisUiState.value.copy(
                    isLoadingForecast = true,
                    isLoadingHistory = true,
                    forecastError = null,
                    historyError = null,
                    trackRecord = trackRecordRepository.getTrackRecordSummary(lat, lon)
                )

                val owmForecastDeferred = async {
                    runCatchingNonFatal {
                        RetrofitClient.service.forecast(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
                    }
                }
                val openMeteoForecastDeferred = async {
                    runCatchingNonFatal { OpenMeteoClient.fetchMultiModelForecast(lat, lon, forecastDays = 5) }
                }
                val recentHistoryDeferred = async {
                    runCatchingNonFatal { OpenMeteoClient.fetchRecentHistory(lat, lon, days = 30) }
                }
                val baselineDeferred = async {
                    runCatchingNonFatal { OpenMeteoClient.fetchSameDateWindowBaseline(lat, lon) }
                }

                val owmForecast = owmForecastDeferred.await()
                if (owmForecast != null && _hourlyEntries.isEmpty()) {
                    updateForecastStates(owmForecast)
                }

                applyAnalysisResults(
                    lat = lat,
                    lon = lon,
                    cityName = cityName,
                    currentTempC = currentTemp,
                    observedRainMm = observedRain,
                    observedWindKmh = observedWindKmh,
                    owmForecast = owmForecast,
                    openMeteoForecast = openMeteoForecastDeferred.await(),
                    recentHistory = recentHistoryDeferred.await(),
                    baseline = baselineDeferred.await()
                )
            }
        }
    }

    private suspend inline fun <T> runCatchingNonFatal(crossinline block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun updateForecastStates(forecast: ForecastResponse) {
        _hourlyEntries.clear()
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        
        val currentTimestamp = Instant.now().epochSecond
        val predictions = com.frogweather.service.WeatherPredictionModel.predict24Hours(
            lat = latitude.value ?: 0.0,
            lon = longitude.value ?: 0.0,
            currentTemp = temperature.value,
            currentPressure = pressure.value?.toDouble(),
            currentHumidity = humidity.value,
            currentWindSpeed = windSpeed.value,
            currentWeather = weatherState.value,
            currentClouds = clouds.value,
            currentPop = if (weatherState.value == WeatherType.RAINY || weatherState.value == WeatherType.THUNDERSTORM) 0.8 else 0.0,
            currentDt = currentTimestamp,
            forecastItems = forecast.list,
            warnings = warnings
        )

        val zoneId = try {
            ZoneOffset.ofTotalSeconds(forecast.city.timezone)
        } catch (e: Exception) {
            ZoneOffset.UTC
        }

        predictions.forEachIndexed { index, pred ->
            val time = if (index == 0) "Jetzt" else {
                pred.time.atZone(zoneId).format(formatter)
            }
            _hourlyEntries.add(
                HourlyEntry(
                    time = time,
                    weather = pred.weatherType,
                    temp = pred.temp,
                    pop = pred.pop,
                    windSpeed = pred.windSpeed,
                    isInterpolated = pred.isInterpolated,
                    isExtreme = pred.isExtreme
                )
            )
        }

        // daily: pick one entry per day for next 7 days
        _dailyEntries.clear()
        val dailyGroups = forecast.list.groupBy { 
            Instant.ofEpochSecond(it.dt).atZone(ZoneOffset.UTC).toLocalDate() 
        }
        val dayFormatter = DateTimeFormatter.ofPattern("dd.MM")
        val dayNameFormatter = DateTimeFormatter.ofPattern("E", Locale.GERMAN)
        
        dailyGroups.entries.take(7).forEach { (date, items) ->
            val min = items.minOf { it.main.tempMin }
            val max = items.maxOf { it.main.tempMax }
            val mainWeather = items.groupBy { it.weather.firstOrNull()?.main ?: "" }
                .maxByOrNull { it.value.size }?.key ?: ""
            
            _dailyEntries.add(DailyEntry(
                date.format(dayFormatter),
                date.format(dayNameFormatter),
                WeatherType.fromString(mainWeather),
                min,
                max
            ))
        }
        
        // simple rain alert
        val nextRain = forecast.list.firstOrNull { it.weather.any { w -> w.main.lowercase().contains("rain") } }
        _rainAlert.value = nextRain?.let { 
            val time = Instant.ofEpochSecond(it.dt).atZone(ZoneOffset.UTC).format(formatter)
            "Regen erwartet um $time"
        }
    }
    
    private fun fetchAirPollution(lat: Double, lon: Double) {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.service.airPollution(lat, lon, BuildConfig.OPENWEATHER_API_KEY)
                _aqi.value = response.list.firstOrNull()?.main?.aqi
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun fetchDwdWarnings(lat: Double, lon: Double) {
        lifecycleScope.launch {
            try {
                val dwdWarnings = DwdWarningFetcher.fetchWarnings(lat, lon)
                _warnings.clear()
                _warnings.addAll(dwdWarnings)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun fetchSuggestions(query: String) {
        if (query.length < 2) {
            suggestionsJob?.cancel()
            clearSuggestions()
            return
        }
        suggestionsJob?.cancel()
        suggestionsJob = lifecycleScope.launch {
            try {
                val response = PhotonClient.service.search(query)
                _suggestions.clear()
                _suggestions.addAll(response.features)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun clearSuggestions() {
        _suggestions.clear()
    }

    private fun updateWidget() {
        WeatherWidgetProvider.updateAllWidgets(this)
    }
    
    private fun saveLastCity(name: String?, country: String?, lat: Double, lon: Double) {
        val prefs = getSharedPreferences(AppPrefsKeys.PREFS_MAIN, MODE_PRIVATE)
        prefs.edit().apply {
            putString(AppPrefsKeys.KEY_LAST_CITY_NAME, name)
            putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, country)
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, lat.toFloat())
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LON, lon.toFloat())
            apply()
        }
    }

    private fun saveCachedCurrentWeather(
        tempC: Double,
        condition: String,
        weatherType: WeatherType,
        rawMain: String,
        sunriseSec: Long?,
        sunsetSec: Long?
    ) {
        val prefs = getSharedPreferences(AppPrefsKeys.PREFS_MAIN, MODE_PRIVATE)
        prefs.edit().apply {
            putFloat(AppPrefsKeys.KEY_LAST_WEATHER_TEMP, tempC.toFloat())
            putString(AppPrefsKeys.KEY_LAST_WEATHER_CONDITION, condition)
            putString(AppPrefsKeys.KEY_LAST_WEATHER_TYPE, weatherType.name)
            putString(AppPrefsKeys.KEY_LAST_WEATHER_RAW_MAIN, rawMain)
            if (sunriseSec != null) putLong(AppPrefsKeys.KEY_LAST_WEATHER_SUNRISE, sunriseSec)
            if (sunsetSec != null) putLong(AppPrefsKeys.KEY_LAST_WEATHER_SUNSET, sunsetSec)
            putLong(AppPrefsKeys.KEY_LAST_WEATHER_UPDATED_AT, System.currentTimeMillis())
            apply()
        }
    }

    private fun refreshCalendarEventsIfPermitted() {
        val granted = CalendarRepository.hasCalendarPermission(this)
        _hasCalendarPermission.value = granted
        if (!granted) {
            calendarJob?.cancel()
            _calendarEvents.clear()
            _rescheduleSuggestions.clear()
            _calendarError.value = null
            return
        }
        calendarJob?.cancel()
        calendarJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                CalendarRepository.queryUpcomingEvents(this@MainActivity, daysAhead = 5)
            }
            _hasCalendarPermission.value = result.permissionGranted
            _calendarEvents.clear()
            _calendarEvents.addAll(result.events)
            _calendarError.value = result.errorMessage
            recomputeCalendarReschedules()
        }
    }

    private fun recomputeCalendarReschedules() {
        val points = _allHourlyPoints.value.ifEmpty { _analysisUiState.value.hourlyPoints }
        val suggestions = EventRescheduler.suggestReschedules(
            events = _calendarEvents.toList(),
            hourlyPoints = points,
            sport = _selectedSport.value
        )
        _rescheduleSuggestions.clear()
        _rescheduleSuggestions.addAll(suggestions)
    }

    private fun calculateRouteWeather() {
        val destQuery = _routeDestinationInput.value.trim()
        if (destQuery.isBlank()) {
            _routeError.value = "Bitte einen Zielort eingeben."
            return
        }
        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            val requestLat = _latitude.value
            val requestLon = _longitude.value
            _routeLoading.value = true
            _routeError.value = null
            try {
                val originQuery = _routeOriginInput.value.trim()
                val activeFav = _activeFavoriteId.value?.let { id ->
                    _favorites.firstOrNull { it.id == id }
                }

                val (originLabel, originLat, originLon) = if (originQuery.isNotBlank()) {
                    val feat = PhotonClient.service.search(originQuery, limit = 1).features.firstOrNull()
                    val coords = feat?.geometry?.coordinates
                    if (feat == null || coords == null || coords.size < 2) {
                        _routeError.value = "Startort „$originQuery“ konnte nicht gefunden werden."
                        return@launch
                    }
                    Triple(feat.properties.name ?: originQuery, coords[1], coords[0])
                } else {
                    val lat = activeFav?.latitude ?: _latitude.value
                    val lon = activeFav?.longitude ?: _longitude.value
                    val name = activeFav?.name ?: _locationName.value ?: "Aktueller Ort"
                    if (lat == null || lon == null) {
                        _routeError.value = "Kein Startstandort verfügbar."
                        return@launch
                    }
                    Triple(name, lat, lon)
                }

                val destFeat = PhotonClient.service.search(destQuery, limit = 1).features.firstOrNull()
                val destCoords = destFeat?.geometry?.coordinates
                if (destFeat == null || destCoords == null || destCoords.size < 2) {
                    _routeError.value = "Zielort „$destQuery“ konnte nicht gefunden werden."
                    return@launch
                }
                val destLat = destCoords[1]
                val destLon = destCoords[0]
                val destLabel = destFeat.properties.name ?: destQuery

                val mode = _routeMode.value
                val osrmResult = OsrmClient.fetchRoute(originLat, originLon, destLat, destLon, mode)
                if (!osrmResult.isSuccess) {
                    _routeError.value = osrmResult.errorMessage
                    return@launch
                }

                val sampledPoints = OsrmRouteParser.sampleWaypoints(
                    points = osrmResult.points,
                    durationSeconds = osrmResult.durationSeconds,
                    originName = originLabel,
                    destinationName = destLabel,
                    maxSamples = 5
                )

                val segmentForecasts = runCatchingNonFatal {
                    val gridCoords = sampledPoints.map { it.latitude to it.longitude }
                    val gridData = OpenMeteoClient.fetchForecastGridForModel(
                        coordinates = gridCoords,
                        modelId = OpenMeteoModelIds.BEST_MATCH,
                        hourlyVars = OpenMeteoClient.DEFAULT_M2_HOURLY.split(","),
                        forecastDays = 3
                    )
                    gridData.map { it.toHourlyWeatherPoints() }
                }.orEmpty()

                val fallbackForecast = _allHourlyPoints.value.ifEmpty { _analysisUiState.value.hourlyPoints }
                val nowMillis = System.currentTimeMillis()
                val targetArrival = nowMillis + _routeArrivalOffsetHours.value * 3_600_000L

                val result = RouteWeatherCalculator.computeRouteWithSegmentForecasts(
                    origin = originLabel,
                    destination = destLabel,
                    mode = mode,
                    targetArrivalMillis = targetArrival,
                    routePoints = sampledPoints,
                    segmentForecasts = segmentForecasts,
                    fallbackForecasts = fallbackForecast,
                    nowMillis = nowMillis
                )

                if (originQuery.isBlank() && requestLat != null && requestLon != null) {
                    val currentLat = _latitude.value
                    val currentLon = _longitude.value
                    if (currentLat == null || currentLon == null ||
                        abs(currentLat - requestLat) > 1e-6 || abs(currentLon - requestLon) > 1e-6
                    ) {
                        return@launch
                    }
                }

                _routeResult.value = result
                lastRouteCoords = (originLat to originLon) to (destLat to destLon)
                lastRouteDestLabel = destLabel

                getSharedPreferences(AppPrefsKeys.PREFS_MAIN, MODE_PRIVATE)
                    .edit()
                    .putString(
                        AppPrefsKeys.KEY_LAST_WEATHER_HINT,
                        "${result.origin} → ${result.destination}: ${result.departureReason}"
                    )
                    .apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _routeError.value = "Routenberechnung fehlgeschlagen: ${e.message ?: "Netzwerkfehler"}"
            } finally {
                if (coroutineContext[Job] == routeJob) {
                    _routeLoading.value = false
                }
            }
        }
    }

    private fun openRouteInMaps() {
        val coords = lastRouteCoords
        if (coords == null) {
            Toast.makeText(this, "Bitte zuerst eine Route berechnen.", Toast.LENGTH_SHORT).show()
            return
        }
        val launched = RouteWeatherCalculator.launchExternalMaps(
            context = this,
            originLat = coords.first.first,
            originLon = coords.first.second,
            destLat = coords.second.first,
            destLon = coords.second.second,
            destLabel = lastRouteDestLabel,
            mode = _routeMode.value
        )
        if (!launched) {
            Toast.makeText(this, "Keine Karten-App für die Navigation gefunden.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askAssistant(query: String) {
        val points = _allHourlyPoints.value.ifEmpty { _analysisUiState.value.hourlyPoints }
        val routeSummary = _routeResult.value?.let { res ->
            "${res.origin} → ${res.destination}: ${res.departureReason}"
        }
        val calendarMsgs = _rescheduleSuggestions.map { "${it.eventTitle}: ${it.message}" }
        val ctx = AssistantContext(
            cityName = _locationName.value,
            currentTempC = _temperature.value,
            feelsLikeC = _feelsLike.value,
            weatherType = _weatherState.value,
            windKmh = _windSpeed.value?.let { it * 3.6 },
            uvIndex = _uvIndex.value,
            selectedSport = _selectedSport.value,
            hourlyPoints = points,
            calendarWarnings = calendarMsgs,
            lastRouteSummary = routeSummary,
            utcOffsetSeconds = _analysisUiState.value.utcOffsetSeconds
        )
        _assistantAnswer.value = AssistantResponseEngine.answer(query, ctx)
    }

    private fun checkMissingImages(
        lat: Double,
        lon: Double,
        weather: WeatherType,
        rawWeatherMain: String = _rawWeatherMain.value ?: weather.name
    ) {
        missingImagesJob?.cancel()
        missingImagesJob = lifecycleScope.launch {
            val repo = CityImageRepository.getInstance(this@MainActivity)
            val night = WeatherScenario.isNightTime(
                nowEpochSec = System.currentTimeMillis() / 1000L,
                sunriseEpochSec = _sunrise.value,
                sunsetEpochSec = _sunset.value,
                fallbackSolarElevationDeg = WeatherPredictionModel.calculateSolarElevation(
                    lat,
                    lon,
                    Instant.now()
                )
            )
            val scenario = WeatherScenario.fromWeatherType(
                weatherType = rawWeatherMain.ifBlank { weather.name },
                isNight = night
            )
            val city = repo.findCityByLocation(lat, lon)
            val activeLat = _latitude.value
            val activeLon = _longitude.value
            if (activeLat == null || activeLon == null ||
                abs(activeLat - lat) > 1e-6 || abs(activeLon - lon) > 1e-6
            ) {
                return@launch
            }
            _isNight.value = night
            _currentWeatherScenario.value = scenario
            val missing = city?.let { repo.getMissingImages(it.id) } ?: WeatherScenario.entries.toList()
            _missingScenarios.clear()
            _missingScenarios.addAll(missing)
            _missingImageCount.value = missing.size
        }
    }
}

@Composable
fun FrogWeatherApp(
    currentWeather: WeatherType?,
    temperature: Double?,
    feelsLike: Double?,
    tempMin: Double?,
    tempMax: Double?,
    rainAlert: String?,
    loading: Boolean,
    hourly: List<MainActivity.HourlyEntry>,
    daily: List<MainActivity.DailyEntry>,
    locationName: String?,
    details: WeatherDetails,
    latitude: Double?,
    longitude: Double?,
    warnings: List<WeatherWarning>,
    missingImageCount: Int,
    currentWeatherScenario: WeatherScenario?,
    suggestions: List<PhotonFeature>,
    onQueryChange: (String) -> Unit,
    onSuggestionSelected: (PhotonFeature) -> Unit,
    onRefreshLocation: () -> Unit,
    onSearch: (String) -> Unit,
    country: String? = null,
    isNight: Boolean = false,
    missingScenarios: List<WeatherScenario> = emptyList(),
    selectedTab: AppTab = AppTab.WEATHER,
    onTabSelected: (AppTab) -> Unit = {},
    favorites: List<FavoriteLocation> = emptyList(),
    activeFavoriteId: String? = null,
    selectedTheme: AppThemeId = AppThemeId.AERO_CLASSIC,
    activeSheetTab: SettingsSheetTab? = null,
    onOpenFavoritesSheet: () -> Unit = {},
    onOpenSettingsSheet: () -> Unit = {},
    onDismissSheet: () -> Unit = {},
    onToggleCurrentFavorite: () -> Unit = {},
    onSaveSuggestionFavorite: (PhotonFeature) -> Unit = {},
    onSelectFavorite: (FavoriteLocation) -> Unit = {},
    onRemoveFavorite: (String) -> Unit = {},
    onSelectTheme: (AppThemeId) -> Unit = {},
    onOpenCityImageSettings: () -> Unit = {},
    analysisUiState: AnalysisUiState = AnalysisUiState(),
    selectedSport: SportType = SportType.RUNNING,
    selectedHistoryDays: Int = 14,
    onSelectSport: (SportType) -> Unit = {},
    onSelectHistoryDays: (Int) -> Unit = {},
    onRetryAnalysis: () -> Unit = {},
    mapState: MapState = MapState(),
    mapLayerData: MapLayerData = MapLayerData(),
    onSelectMapViewMode: (MapViewMode) -> Unit = {},
    onToggleMapLayer: (MapWeatherLayer) -> Unit = {},
    onClearMapLayers: () -> Unit = {},
    onSelectPressureLevel: (PressureLevel) -> Unit = {},
    onSelectMapModel: (MapForecastModel) -> Unit = {},
    onSelectTimelineIndex: (Int) -> Unit = {},
    onToggleMapPlay: () -> Unit = {},
    onStepMapTimeline: (Int) -> Unit = {},
    onToggleMapWarnings: () -> Unit = {},
    onRefreshMapData: () -> Unit = {},
    onMapViewportBoundsChanged: (GeoBounds) -> Unit = {},
    briefingSchedule: BriefingScheduleConfig = BriefingScheduleConfig(),
    hasCalendarPermission: Boolean = false,
    calendarEvents: List<CalendarEvent> = emptyList(),
    calendarError: String? = null,
    rescheduleSuggestions: List<RescheduleSuggestion> = emptyList(),
    routeOriginInput: String = "",
    routeDestinationInput: String = "",
    routeMode: RouteTransportMode = RouteTransportMode.DRIVING,
    routeArrivalOffsetHours: Int = 2,
    routeResult: RouteWeatherResult? = null,
    routeLoading: Boolean = false,
    routeError: String? = null,
    assistantAnswer: AssistantAnswer? = null,
    onAskAssistant: (String) -> Unit = {},
    onUpdateMorningSchedule: (enabled: Boolean, hour: Int, minute: Int) -> Unit = { _, _, _ -> },
    onUpdateEveningSchedule: (enabled: Boolean, hour: Int, minute: Int) -> Unit = { _, _, _ -> },
    onTriggerTestBriefing: () -> Unit = {},
    onRequestCalendarPermission: () -> Unit = {},
    onRefreshCalendar: () -> Unit = {},
    onRouteOriginChange: (String) -> Unit = {},
    onRouteDestinationChange: (String) -> Unit = {},
    onRouteModeChange: (RouteTransportMode) -> Unit = {},
    onRouteArrivalOffsetChange: (Int) -> Unit = {},
    onCalculateRoute: () -> Unit = {},
    onOpenInMaps: () -> Unit = {}
) {
    val palette = FrogTheme.palette
    val scrollState = rememberScrollState()
    val isCurrentFavorite = remember(favorites, latitude, longitude) {
        if (latitude == null || longitude == null) {
            false
        } else {
            favorites.any {
                kotlin.math.abs(it.latitude - latitude) <= 0.01 &&
                    kotlin.math.abs(it.longitude - longitude) <= 0.01
            }
        }
    }
    val hourlySnapshot = hourly.toList()
    val rescheduleSnapshot = rescheduleSuggestions.toList()
    val composedBriefing = remember(
        locationName,
        temperature,
        feelsLike,
        tempMin,
        tempMax,
        currentWeather,
        details.windSpeed,
        details.uvIndex,
        hourlySnapshot,
        selectedSport,
        analysisUiState.bestSportWindow,
        analysisUiState.utcOffsetSeconds,
        rescheduleSnapshot,
        routeResult
    ) {
        val popMax = ((hourlySnapshot.maxOfOrNull { it.pop } ?: 0.0) * 100.0).toInt().coerceIn(0, 100)
        val windKmh = (details.windSpeed ?: 0.0) * 3.6
        val clothing = ClothingAdvisor.recommendClothing(
            tempC = temperature ?: 15.0,
            feelsLikeC = feelsLike ?: temperature ?: 15.0,
            precipProb = popMax,
            windKmh = windKmh,
            uvIndex = details.uvIndex ?: 2.0
        )
        val zoneOffset = runCatching {
            ZoneOffset.ofTotalSeconds(analysisUiState.utcOffsetSeconds)
        }.getOrDefault(ZoneOffset.UTC)
        val dateLabel = ZonedDateTime.now(zoneOffset).format(
            DateTimeFormatter.ofPattern("EEEE, dd.MM.", Locale.GERMAN)
        )
        val cityLabel = locationName?.takeIf { it.isNotBlank() } ?: "Aktueller Ort"
        val tempText = temperature?.let { "${it.toInt()} °C" } ?: "-- °C"
        val rangeText = if (tempMin != null && tempMax != null) {
            " (${tempMin.toInt()}–${tempMax.toInt()} °C)"
        } else {
            ""
        }
        val dailySummary = "$cityLabel: $tempText$rangeText, ${currentWeather?.label ?: "Wetterdaten"}"
        val calendarWarnings = rescheduleSnapshot.map { "${it.eventTitle}: ${it.message}" }
        val routeCheck = routeResult?.let { "${it.origin} → ${it.destination}: ${it.departureReason}" }
        BriefingComposer.composeBriefing(
            dateLabel = dateLabel,
            dailySummary = dailySummary,
            clothing = clothing,
            bestSportWindow = analysisUiState.bestSportWindow,
            calendarWarnings = calendarWarnings,
            routeCheck = routeCheck,
            briefingType = BriefingType.MORNING,
            utcOffsetSeconds = analysisUiState.utcOffsetSeconds
        )
    }
    val morningTimeLabel = remember(briefingSchedule.morningHour, briefingSchedule.morningMinute) {
        BriefingTimeCalculator.formatTime(briefingSchedule.morningHour, briefingSchedule.morningMinute)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val bgColors = palette.gradientForWeather(currentWeather, isNight)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(bgColors))
        )

        Scaffold(
            backgroundColor = Color.Transparent,
            bottomBar = {
                FrogBottomNavigationBar(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                SearchBar(
                    suggestions = suggestions,
                    favoritesCount = favorites.size,
                    onQueryChange = onQueryChange,
                    onSuggestionSelected = onSuggestionSelected,
                    onSaveSuggestionFavorite = onSaveSuggestionFavorite,
                    onRefreshLocation = onRefreshLocation,
                    onSearch = onSearch,
                    onOpenFavorites = onOpenFavoritesSheet,
                    onOpenSettings = onOpenSettingsSheet
                )

                when (selectedTab) {
                    AppTab.WEATHER -> {
                        if (loading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = palette.textPrimary)
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(16.dp)
                            ) {
                                if (warnings.isNotEmpty()) {
                                    WarningBanners(warnings = warnings)
                                    Spacer(modifier = Modifier.height(16.dp))
                                }

                                if (missingImageCount > 0 && latitude != null && longitude != null && locationName != null && currentWeatherScenario != null) {
                                    MissingImageBanner(
                                        missingCount = missingImageCount,
                                        highlightedScenario = currentWeatherScenario,
                                        latitude = latitude,
                                        longitude = longitude,
                                        cityName = locationName,
                                        country = country,
                                        missingScenarios = missingScenarios
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = listOfNotNull(
                                            locationName,
                                            country?.takeIf { it.isNotBlank() }
                                        ).joinToString(", ").ifBlank { "Unbekannter Ort" },
                                        style = MaterialTheme.typography.h4,
                                        color = palette.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (locationName != null && latitude != null && longitude != null) {
                                        IconButton(onClick = onToggleCurrentFavorite) {
                                            Icon(
                                                imageVector = if (isCurrentFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = if (isCurrentFavorite) "Favorit entfernen" else "Als Favorit speichern",
                                                tint = if (isCurrentFavorite) Color(0xFFFFD54F) else palette.textPrimary
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = "${temperature?.toInt() ?: "--"}°",
                                    style = MaterialTheme.typography.h1,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = "${currentWeather?.label ?: ""} • Gefühlt ${feelsLike?.toInt() ?: "--"}°",
                                    style = MaterialTheme.typography.subtitle1,
                                    color = palette.textSecondary
                                )
                                Text(
                                    text = "H:${tempMax?.toInt() ?: "--"}°  L:${tempMin?.toInt() ?: "--"}°",
                                    style = MaterialTheme.typography.subtitle1,
                                    color = palette.textSecondary
                                )

                                if (details.normalTempC != null && temperature != null &&
                                    details.normalTempC.isFinite() && temperature.isFinite()
                                ) {
                                    val delta = temperature - details.normalTempC
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(palette.tileSurface.copy(alpha = 0.85f))
                                            .clickable { onTabSelected(AppTab.ANALYSIS) }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = String.format(
                                                Locale.GERMAN,
                                                "normalerweise %.1f °C um diese Zeit (%+.1f °C)",
                                                details.normalTempC,
                                                delta
                                            ),
                                            color = palette.accent,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                FrogAnimation(type = currentWeather ?: WeatherType.OTHER)

                                Spacer(modifier = Modifier.height(24.dp))

                                if (rainAlert != null) {
                                    Card(
                                        backgroundColor = palette.cardSurface,
                                        shape = RoundedCornerShape(12.dp),
                                        elevation = 0.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = palette.accent)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = rainAlert, color = palette.textPrimary)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(24.dp))
                                }

                                BriefingCompactCard(
                                    briefing = composedBriefing,
                                    morningTimeLabel = morningTimeLabel,
                                    onOpenPlanner = { onTabSelected(AppTab.PLANNER) }
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                HourlyForecast(items = hourly)

                                Spacer(modifier = Modifier.height(24.dp))

                                DailyForecast(items = daily)

                                Spacer(modifier = Modifier.height(24.dp))

                                WeatherDetailsGrid(
                                    details = details,
                                    onOpenAnalysis = { onTabSelected(AppTab.ANALYSIS) }
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                if (latitude != null && longitude != null) {
                                    WeatherMapTile(
                                        latitude = latitude,
                                        longitude = longitude,
                                        onOpenFullMap = { onTabSelected(AppTab.MAP) }
                                    )
                                }

                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }
                    }

                    AppTab.ANALYSIS -> {
                        AnalysisScreen(
                            cityName = locationName,
                            country = country,
                            latitude = latitude,
                            longitude = longitude,
                            uiState = analysisUiState,
                            selectedSport = selectedSport,
                            selectedHistoryDays = selectedHistoryDays,
                            onSelectSport = onSelectSport,
                            onSelectHistoryDays = onSelectHistoryDays,
                            onRetry = onRetryAnalysis
                        )
                    }

                    AppTab.MAP -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            WeatherMapScreen(
                                latitude = latitude,
                                longitude = longitude,
                                cityName = locationName,
                                country = country,
                                isLoadingLocation = loading,
                                mapState = mapState,
                                layerData = mapLayerData,
                                warnings = warnings,
                                onSelectViewMode = onSelectMapViewMode,
                                onToggleLayer = onToggleMapLayer,
                                onClearLayers = onClearMapLayers,
                                onSelectPressureLevel = onSelectPressureLevel,
                                onSelectModel = onSelectMapModel,
                                onSelectTimelineIndex = onSelectTimelineIndex,
                                onTogglePlay = onToggleMapPlay,
                                onStepTimeline = onStepMapTimeline,
                                onToggleWarnings = onToggleMapWarnings,
                                onRefreshData = onRefreshMapData,
                                onViewportBoundsChanged = onMapViewportBoundsChanged
                            )
                        }
                    }

                    AppTab.PLANNER -> {
                        PlannerScreen(
                            cityName = locationName,
                            country = country,
                            latitude = latitude,
                            longitude = longitude,
                            selectedSport = selectedSport,
                            briefing = composedBriefing,
                            briefingSchedule = briefingSchedule,
                            hasCalendarPermission = hasCalendarPermission,
                            calendarEvents = calendarEvents,
                            calendarError = calendarError,
                            rescheduleSuggestions = rescheduleSuggestions,
                            routeOriginInput = routeOriginInput,
                            routeDestinationInput = routeDestinationInput,
                            routeMode = routeMode,
                            routeArrivalOffsetHours = routeArrivalOffsetHours,
                            routeResult = routeResult,
                            routeLoading = routeLoading,
                            routeError = routeError,
                            assistantAnswer = assistantAnswer,
                            onAskAssistant = onAskAssistant,
                            onUpdateMorningSchedule = onUpdateMorningSchedule,
                            onUpdateEveningSchedule = onUpdateEveningSchedule,
                            onTriggerTestBriefing = onTriggerTestBriefing,
                            onRequestCalendarPermission = onRequestCalendarPermission,
                            onRefreshCalendar = onRefreshCalendar,
                            onRouteOriginChange = onRouteOriginChange,
                            onRouteDestinationChange = onRouteDestinationChange,
                            onRouteModeChange = onRouteModeChange,
                            onRouteArrivalOffsetChange = onRouteArrivalOffsetChange,
                            onCalculateRoute = onCalculateRoute,
                            onOpenInMaps = onOpenInMaps
                        )
                    }
                }
            }
        }

        LiveWeatherOverlay(
            weatherType = currentWeather,
            scenario = currentWeatherScenario,
            isNight = isNight,
            windSpeedMs = details.windSpeed,
            isDimmed = (selectedTab == AppTab.MAP)
        )

        if (activeSheetTab != null) {
            FavoritesAndSettingsSheet(
                initialTab = activeSheetTab,
                favorites = favorites,
                activeFavoriteId = activeFavoriteId,
                currentLocationName = locationName,
                currentCountry = country,
                currentLat = latitude,
                currentLon = longitude,
                selectedTheme = selectedTheme,
                onSaveCurrentLocation = onToggleCurrentFavorite,
                onSelectFavorite = onSelectFavorite,
                onRemoveFavorite = onRemoveFavorite,
                onSelectTheme = onSelectTheme,
                onOpenCityImageSettings = onOpenCityImageSettings,
                onDismiss = onDismissSheet,
                selectedSport = selectedSport,
                onSelectSport = onSelectSport,
                briefingTimeLabel = morningTimeLabel,
                onOpenBriefingSettings = {
                    onDismissSheet()
                    onTabSelected(AppTab.PLANNER)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun SearchBar(
    suggestions: List<PhotonFeature>,
    favoritesCount: Int = 0,
    onQueryChange: (String) -> Unit,
    onSuggestionSelected: (PhotonFeature) -> Unit,
    onSaveSuggestionFavorite: (PhotonFeature) -> Unit = {},
    onRefreshLocation: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenFavorites: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val palette = FrogTheme.palette
    var text by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardSurface, RoundedCornerShape(24.dp))
                .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = palette.textPrimary)
            Spacer(modifier = Modifier.width(6.dp))
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    onQueryChange(it)
                    expanded = it.isNotEmpty()
                },
                placeholder = { Text("Stadt suchen...", color = palette.textSecondary) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    textColor = palette.textPrimary,
                    cursorColor = palette.accent
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSearch(text)
                    expanded = false
                })
            )
            IconButton(onClick = onRefreshLocation) {
                Icon(Icons.Default.LocationOn, contentDescription = "Standort", tint = palette.textPrimary)
            }
            IconButton(onClick = onOpenFavorites) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(Icons.Default.Bookmarks, contentDescription = "Favoriten", tint = palette.textPrimary)
                    if (favoritesCount > 0) {
                        Box(
                            modifier = Modifier
                                .offset(x = 4.dp, y = (-3).dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(palette.accent)
                                .padding(horizontal = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = favoritesCount.toString(),
                                color = palette.screenBackground,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Einstellungen & Themes", tint = palette.textPrimary)
            }
        }

        if (expanded && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                backgroundColor = palette.tileSurfaceElevated,
                elevation = 8.dp,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column {
                    suggestions.forEach { feature ->
                        ListItem(
                            modifier = Modifier.clickable {
                                text = feature.properties.name ?: feature.cityQuery()
                                expanded = false
                                onSuggestionSelected(feature)
                            },
                            text = { Text(feature.properties.name ?: feature.cityQuery(), color = palette.textPrimary) },
                            secondaryText = {
                                Text(
                                    listOfNotNull(feature.properties.state, feature.properties.country)
                                        .filter { it.isNotBlank() }
                                        .joinToString(", "),
                                    color = palette.textSecondary
                                )
                            },
                            trailing = {
                                IconButton(onClick = { onSaveSuggestionFavorite(feature) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.StarBorder,
                                        contentDescription = "Als Favorit speichern",
                                        tint = palette.accent
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HourlyForecast(items: List<MainActivity.HourlyEntry>) {
    val palette = FrogTheme.palette
    Card(
        backgroundColor = palette.cardSurface,
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "STÜNDLICHE VORHERSAGE (24 STUNDEN)",
                color = palette.textSecondary,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { entry ->
                    val rowBgColor = when {
                        entry.isExtreme -> Color(0xFFEF5350).copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }
                    val borderModifier = if (entry.isExtreme) {
                        Modifier.border(1.dp, Color(0xFFEF5350).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(rowBgColor)
                            .then(borderModifier)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = entry.time,
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.width(50.dp)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(110.dp)
                        ) {
                            Text(text = entry.weather.forecastEmoji, fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = entry.weather.label,
                                color = palette.textPrimary,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }

                        Text(
                            text = "${entry.temp.toInt()}°",
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.width(35.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(55.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(text = "☔", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${(entry.pop * 100).toInt()}%",
                                color = palette.textSecondary,
                                fontSize = 13.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(75.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(text = "💨", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${(entry.windSpeed * 3.6).toInt()} km/h",
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when {
                                        entry.isExtreme -> Color(0xFFEF5350)
                                        !entry.isInterpolated -> palette.accentSecondary
                                        else -> palette.accent.copy(alpha = 0.2f)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when {
                                    entry.isExtreme -> "EXTREM"
                                    !entry.isInterpolated -> "API"
                                    else -> "MODELL"
                                },
                                color = when {
                                    entry.isExtreme -> Color.White
                                    !entry.isInterpolated -> palette.screenBackground
                                    else -> palette.textPrimary
                                },
                                fontSize = 9.sp,
                                style = MaterialTheme.typography.caption
                            )
                        }
                    }
                    if (entry != items.last()) {
                        Divider(color = palette.dividerColor)
                    }
                }
            }
        }
    }
}

@Composable
fun DailyForecast(items: List<MainActivity.DailyEntry>) {
    val palette = FrogTheme.palette
    Card(
        backgroundColor = palette.cardSurface,
        shape = RoundedCornerShape(16.dp),
        elevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "7-TAGE-VORHERSAGE",
                color = palette.textSecondary,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.dayName,
                        color = palette.textPrimary,
                        modifier = Modifier.weight(1f),
                        fontSize = 16.sp
                    )
                    Text(
                        text = entry.weather.forecastEmoji,
                        fontSize = 22.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        Text(text = "${entry.tempMax.toInt()}°", color = palette.textPrimary, fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "${entry.tempMin.toInt()}°", color = palette.textSecondary, fontSize = 16.sp)
                    }
                }
                if (entry != items.last()) {
                    Divider(color = palette.dividerColor)
                }
            }
        }
    }
}

@Composable
fun FrogAnimation(type: WeatherType) {
    val palette = FrogTheme.palette
    val context = LocalContext.current

    val videoRes: Int? = when (type) {
        WeatherType.RAINY  -> R.raw.rain
        WeatherType.SUNNY  -> R.raw.sunny
        WeatherType.CLOUDY -> R.raw.cloudy
        else -> null
    }

    if (videoRes != null) {
        val player = remember(videoRes) {
            ExoPlayer.Builder(context).build().apply {
                val uri = android.net.Uri.parse("android.resource://${context.packageName}/$videoRes")
                setMediaItem(MediaItem.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                prepare()
                playWhenReady = true
            }
        }

        DisposableEffect(player) {
            onDispose { player.release() }
        }

        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(palette.cardSurface, shape = RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = type.emoji, fontSize = 64.sp)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewApp() {
    val sampleHourly = listOf(
        MainActivity.HourlyEntry("Jetzt", WeatherType.SUNNY, 22.0, 0.0, 5.0, false, false),
        MainActivity.HourlyEntry("01:00", WeatherType.SUNNY, 21.5, 0.0, 5.2, true, false),
        MainActivity.HourlyEntry("02:00", WeatherType.CLOUDY, 20.0, 0.1, 5.8, true, false),
        MainActivity.HourlyEntry("03:00", WeatherType.RAINY, 19.0, 0.6, 12.0, false, false),
        MainActivity.HourlyEntry("04:00", WeatherType.THUNDERSTORM, 18.0, 0.9, 22.0, true, true),
        MainActivity.HourlyEntry("05:00", WeatherType.CLOUDY, 18.5, 0.4, 15.0, true, false),
        MainActivity.HourlyEntry("06:00", WeatherType.SUNNY, 19.5, 0.1, 8.0, true, false),
        MainActivity.HourlyEntry("07:00", WeatherType.SUNNY, 21.0, 0.0, 6.0, false, false)
    )
    val sampleDaily = listOf(
        MainActivity.DailyEntry("01.03", "Mo", WeatherType.CLOUDY, 15.0, 22.0),
        MainActivity.DailyEntry("02.03", "Di", WeatherType.RAINY, 14.0, 18.0),
        MainActivity.DailyEntry("03.03", "Mi", WeatherType.SUNNY, 16.0, 24.0),
        MainActivity.DailyEntry("04.03", "Do", WeatherType.CLOUDY, 15.0, 21.0),
        MainActivity.DailyEntry("05.03", "Fr", WeatherType.SUNNY, 17.0, 25.0),
        MainActivity.DailyEntry("06.03", "Sa", WeatherType.RAINY, 13.0, 17.0),
        MainActivity.DailyEntry("07.03", "So", WeatherType.SUNNY, 15.0, 23.0)
    )
    FrogWeatherTheme(darkTheme = true) {
        FrogWeatherApp(
            currentWeather = WeatherType.SUNNY,
            temperature = 22.0,
            feelsLike = 21.0,
            tempMin = 18.0,
            tempMax = 25.0,
            rainAlert = null,
            loading = false,
            hourly = sampleHourly,
            daily = sampleDaily,
            locationName = "Ort",
            details = WeatherDetails(
                windSpeed = 5.0,
                windDeg = 180,
                sunrise = 1700000000L,
                sunset = 1700040000L,
                pressure = 1013,
                humidity = 50,
                visibilityM = 10000,
                rainMm = 0.0,
                aqi = 1,
                temperature = 22.0
            ),
            latitude = 50.0,
            longitude = 10.0,
            warnings = emptyList(),
            missingImageCount = 0,
            currentWeatherScenario = null,
            suggestions = emptyList(),
            onQueryChange = {},
            onSuggestionSelected = {},
            onRefreshLocation = {},
            onSearch = {}
        )
    }
}

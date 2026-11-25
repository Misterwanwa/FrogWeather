package com.frogweather.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// Alle unterstützten Wettertypen für Bilder
enum class WeatherScenario(val key: String, val displayName: String, val emoji: String) {
    SUNNY("sunny", "Sonnig", "☀️"),
    PARTLY_CLOUDY("partly_cloudy", "Leicht bewölkt", "⛅"),
    CLOUDY("cloudy", "Bewölkt", "☁️"),
    LIGHT_RAIN("light_rain", "Leichter Regen", "🌦"),
    RAIN("rain", "Regen", "🌧"),
    HEAVY_RAIN("heavy_rain", "Starker Regen", "⛈"),
    THUNDERSTORM("thunderstorm", "Gewitter", "⚡"),
    LIGHT_SNOW("light_snow", "Leichter Schnee", "🌨"),
    SNOW("snow", "Schnee", "❄️"),
    BLIZZARD("blizzard", "Schneesturm", "🌬"),
    FOG("fog", "Nebel", "🌫"),
    NIGHT_CLEAR("night_clear", "Klar (Nacht)", "🌙"),
    NIGHT_CLOUDY("night_cloudy", "Bewölkt (Nacht)", "☁️");

    companion object {
        fun fromWeatherType(weatherType: String, isNight: Boolean = false): WeatherScenario {
            return when (weatherType.trim().uppercase()) {
                "CLEAR", "SUNNY" -> if (isNight) NIGHT_CLEAR else SUNNY
                "CLOUDS", "CLOUDY" -> if (isNight) NIGHT_CLOUDY else CLOUDY
                "PARTLY CLOUDY", "PARTLY_CLOUDY", "FEW CLOUDS" -> if (isNight) NIGHT_CLOUDY else PARTLY_CLOUDY
                "LIGHT RAIN", "LIGHT_RAIN" -> LIGHT_RAIN
                "RAIN", "DRIZZLE", "RAINY" -> RAIN
                "HEAVY RAIN", "HEAVY_RAIN" -> HEAVY_RAIN
                "THUNDERSTORM" -> THUNDERSTORM
                "LIGHT SNOW", "LIGHT_SNOW" -> LIGHT_SNOW
                "SNOW" -> SNOW
                "BLIZZARD" -> BLIZZARD
                "MIST", "FOG", "HAZE" -> FOG
                "NIGHT_CLEAR", "NIGHT CLEAR" -> NIGHT_CLEAR
                "NIGHT_CLOUDY", "NIGHT CLOUDY" -> NIGHT_CLOUDY
                else -> if (isNight) NIGHT_CLOUDY else CLOUDY
            }
        }

        fun isNightTime(
            nowEpochSec: Long,
            sunriseEpochSec: Long?,
            sunsetEpochSec: Long?,
            fallbackSolarElevationDeg: Double? = null,
            fallbackLocalHour: Int? = null
        ): Boolean {
            if (sunriseEpochSec != null && sunsetEpochSec != null &&
                sunriseEpochSec > 0L && sunsetEpochSec > 0L &&
                sunsetEpochSec > sunriseEpochSec
            ) {
                return nowEpochSec < sunriseEpochSec || nowEpochSec >= sunsetEpochSec
            }
            if (fallbackSolarElevationDeg != null && fallbackSolarElevationDeg.isFinite()) {
                return fallbackSolarElevationDeg < 0.0
            }
            if (fallbackLocalHour != null) {
                return fallbackLocalHour < 6 || fallbackLocalHour >= 20
            }
            return false
        }
    }
}

data class CityConfig(
    val id: String,
    val name: String,
    val country: String?,
    val lat: Double,
    val lon: Double,
    val images: Map<WeatherScenario, String> = emptyMap() // scenario -> filename
)

class CityImageRepository(private val context: Context) {
    
    private val prefs = context.getSharedPreferences("city_images", Context.MODE_PRIVATE)
    private val imagesDir = File(context.filesDir, "city_images").apply { mkdirs() }
    
    companion object {
        private const val CITIES_KEY = "cities"
        private var instance: CityImageRepository? = null
        
        fun getInstance(context: Context): CityImageRepository {
            return instance ?: CityImageRepository(context.applicationContext).also { instance = it }
        }
    }
    
    // Alle gespeicherten Städte laden
    fun getAllCities(): List<CityConfig> {
        val citiesJson = prefs.getString(CITIES_KEY, "[]") ?: "[]"
        return parseCities(citiesJson)
    }
    
    // Stadt anhand der Koordinaten finden (mit Toleranz)
    fun findCityByLocation(lat: Double, lon: Double, tolerance: Double = 0.5): CityConfig? {
        return getAllCities().find { city ->
            kotlin.math.abs(city.lat - lat) < tolerance && 
            kotlin.math.abs(city.lon - lon) < tolerance
        }
    }

    fun ensureCity(
        name: String,
        country: String?,
        lat: Double,
        lon: Double,
        tolerance: Double = 0.5
    ): CityConfig {
        val cleanName = name.trim().ifEmpty { "Unbekannte Stadt" }
        val cleanCountry = country?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        val existing = findCityByLocation(lat, lon, tolerance)
        if (existing != null) {
            val needsNameUpdate = existing.name.isBlank() || existing.name == "Unbekannte Stadt"
            val needsCountryUpdate = existing.country.isNullOrBlank() && cleanCountry != null
            if (needsNameUpdate || needsCountryUpdate) {
                val updated = existing.copy(
                    name = if (needsNameUpdate) cleanName else existing.name,
                    country = if (needsCountryUpdate) cleanCountry else existing.country
                )
                saveCity(updated)
                return updated
            }
            return existing
        }
        val created = CityConfig(
            id = "${cleanName}_${System.currentTimeMillis()}",
            name = cleanName,
            country = cleanCountry,
            lat = lat,
            lon = lon
        )
        saveCity(created)
        return created
    }
    
    // Stadt hinzufügen oder aktualisieren
    fun saveCity(city: CityConfig) {
        val cities = getAllCities().toMutableList()
        val existingIndex = cities.indexOfFirst { it.id == city.id }
        
        if (existingIndex >= 0) {
            cities[existingIndex] = city
        } else {
            cities.add(city)
        }
        
        saveCities(cities)
    }
    
    // Bild für ein Wetterszenario speichern
    suspend fun saveImage(cityId: String, scenario: WeatherScenario, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val filename = "${cityId}_${scenario.key}_${System.currentTimeMillis()}.jpg"
            val file = File(imagesDir, filename)
            
            FileOutputStream(file).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            
            // Bild komprimieren falls nötig
            compressImageIfNeeded(file)
            
            filename
        } catch (e: Exception) {
            null
        }
    }
    
    // Bild laden
    fun getImage(cityId: String, scenario: WeatherScenario): Bitmap? {
        val city = getAllCities().find { it.id == cityId } ?: return null
        val filename = city.images[scenario] ?: return null
        val file = File(imagesDir, filename)
        
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    /**
     * Loads a downscaled bitmap suitable for Android RemoteViews (<= 1 MB Binder limit).
     */
    fun getScaledImage(
        cityId: String,
        scenario: WeatherScenario,
        maxSizePx: Int = 400
    ): Bitmap? {
        val city = getAllCities().find { it.id == cityId } ?: return null
        val filename = city.images[scenario] ?: return null
        val file = File(imagesDir, filename)
        if (!file.exists()) return null

        val targetMax = maxSizePx.coerceIn(64, 600)
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var inSampleSize = 1
        while ((srcW / inSampleSize) > targetMax * 2 || (srcH / inSampleSize) > targetMax * 2) {
            inSampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            this.inPreferredConfig = Bitmap.Config.RGB_565
        }
        val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        val maxDim = maxOf(sampled.width, sampled.height)
        if (maxDim <= targetMax) return sampled

        val scale = targetMax.toFloat() / maxDim.toFloat()
        val scaledW = (sampled.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (sampled.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(sampled, scaledW, scaledH, true).also {
            if (it !== sampled) sampled.recycle()
        }
    }

    /**
     * Resolves a downscaled city image for the given location/name and weather scenario,
     * falling back through compatible weather scenarios if the exact scenario image is missing.
     */
    fun findBestImageForWeather(
        cityName: String?,
        lat: Double?,
        lon: Double?,
        scenario: WeatherScenario,
        maxSizePx: Int = 400
    ): Bitmap? {
        val cities = getAllCities()
        if (cities.isEmpty()) return null

        val validCoords = lat != null && lon != null &&
            lat.isFinite() && lon.isFinite() &&
            !(kotlin.math.abs(lat) < 1e-6 && kotlin.math.abs(lon) < 1e-6)

        val matchedCity = (if (validCoords) findCityByLocation(lat!!, lon!!) else null)
            ?: cityName?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
                cities.firstOrNull { it.name.equals(query, ignoreCase = true) }
            }
            ?: return null

        val candidates = buildList {
            add(scenario)
            when (scenario) {
                WeatherScenario.LIGHT_RAIN, WeatherScenario.HEAVY_RAIN -> add(WeatherScenario.RAIN)
                WeatherScenario.THUNDERSTORM -> {
                    add(WeatherScenario.HEAVY_RAIN)
                    add(WeatherScenario.RAIN)
                }
                WeatherScenario.LIGHT_SNOW, WeatherScenario.BLIZZARD -> add(WeatherScenario.SNOW)
                WeatherScenario.NIGHT_CLEAR -> add(WeatherScenario.SUNNY)
                WeatherScenario.NIGHT_CLOUDY -> {
                    add(WeatherScenario.PARTLY_CLOUDY)
                    add(WeatherScenario.CLOUDY)
                }
                WeatherScenario.PARTLY_CLOUDY -> {
                    add(WeatherScenario.SUNNY)
                    add(WeatherScenario.CLOUDY)
                }
                WeatherScenario.FOG -> add(WeatherScenario.CLOUDY)
                WeatherScenario.SUNNY, WeatherScenario.CLOUDY, WeatherScenario.RAIN, WeatherScenario.SNOW -> Unit
            }
        }

        for (candidate in candidates) {
            val bmp = getScaledImage(matchedCity.id, candidate, maxSizePx)
            if (bmp != null) return bmp
        }
        return null
    }
    
    // Prüfen ob ein Bild für ein Szenario existiert
    fun hasImage(cityId: String, scenario: WeatherScenario): Boolean {
        val city = getAllCities().find { it.id == cityId } ?: return false
        val filename = city.images[scenario] ?: return false
        return File(imagesDir, filename).exists()
    }
    
    // Prüfen welche Bilder fehlen
    fun getMissingImages(cityId: String): List<WeatherScenario> {
        return WeatherScenario.values().filter { !hasImage(cityId, it) }
    }
    
    // Bild zuweisen
    fun assignImageToScenario(cityId: String, scenario: WeatherScenario, filename: String) {
        val city = getAllCities().find { it.id == cityId } ?: return
        val updatedImages = city.images.toMutableMap().apply {
            // Altes Bild löschen falls vorhanden
            put(scenario, filename)?.let { oldFilename ->
                deleteImageFile(oldFilename)
            }
        }
        saveCity(city.copy(images = updatedImages))
    }
    
    // Bild löschen
    fun deleteImage(cityId: String, scenario: WeatherScenario) {
        val city = getAllCities().find { it.id == cityId } ?: return
        val filename = city.images[scenario] ?: return
        
        deleteImageFile(filename)
        
        val updatedImages = city.images.toMutableMap().apply { remove(scenario) }
        saveCity(city.copy(images = updatedImages))
    }
    
    // Stadt löschen
    fun deleteCity(cityId: String) {
        val city = getAllCities().find { it.id == cityId } ?: return
        
        // Alle Bilder der Stadt löschen
        city.images.values.forEach { deleteImageFile(it) }
        
        // Stadt aus Liste entfernen
        val cities = getAllCities().filter { it.id != cityId }
        saveCities(cities)
    }
    
    private fun saveCities(cities: List<CityConfig>) {
        val json = JSONArray()
        cities.forEach { city ->
            json.put(serializeCity(city))
        }
        prefs.edit { putString(CITIES_KEY, json.toString()) }
    }
    
    private fun parseCities(json: String): List<CityConfig> {
        val list = mutableListOf<CityConfig>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { parseCity(it) }?.let { list.add(it) }
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return list
    }
    
    private fun serializeCity(city: CityConfig): JSONObject {
        return JSONObject().apply {
            put("id", city.id)
            put("name", city.name)
            put("country", city.country)
            put("lat", city.lat)
            put("lon", city.lon)
            put("images", JSONObject(city.images.mapKeys { it.key.key }))
        }
    }
    
    private fun parseCity(obj: JSONObject): CityConfig? {
        return try {
            val imagesJson = obj.optJSONObject("images") ?: JSONObject()
            val images = mutableMapOf<WeatherScenario, String>()
            
            WeatherScenario.values().forEach { scenario ->
                if (!imagesJson.isNull(scenario.key)) {
                    val filename = imagesJson.optString(scenario.key, "")
                    if (filename.isNotEmpty()) images[scenario] = filename
                }
            }
            
            CityConfig(
                id = obj.getString("id"),
                name = obj.getString("name"),
                country = if (obj.isNull("country")) null else obj.optString("country", "").takeIf { it.isNotBlank() && it != "null" },
                lat = obj.getDouble("lat"),
                lon = obj.getDouble("lon"),
                images = images
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun compressImageIfNeeded(file: File) {
        val maxSize = 1024 * 1024 // 1MB
        if (file.length() <= maxSize) return
        
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        var quality = 90
        
        do {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            quality -= 10
        } while (file.length() > maxSize && quality > 30)
    }
    
    private fun deleteImageFile(filename: String) {
        File(imagesDir, filename).delete()
    }
}

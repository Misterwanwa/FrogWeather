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
            return when (weatherType.uppercase()) {
                "CLEAR", "SUNNY" -> if (isNight) NIGHT_CLEAR else SUNNY
                "CLOUDS", "CLOUDY" -> if (isNight) NIGHT_CLOUDY else CLOUDY
                "PARTLY CLOUDY", "FEW CLOUDS" -> PARTLY_CLOUDY
                "RAIN", "DRIZZLE" -> RAIN
                "THUNDERSTORM" -> THUNDERSTORM
                "SNOW" -> SNOW
                "MIST", "FOG", "HAZE" -> FOG
                else -> if (isNight) NIGHT_CLOUDY else CLOUDY
            }
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
                parseCity(array.getJSONObject(i))?.let { list.add(it) }
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
                imagesJson.optString(scenario.key, null)?.let { filename ->
                    if (filename.isNotEmpty()) images[scenario] = filename
                }
            }
            
            CityConfig(
                id = obj.getString("id"),
                name = obj.getString("name"),
                country = obj.optString("country", null),
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

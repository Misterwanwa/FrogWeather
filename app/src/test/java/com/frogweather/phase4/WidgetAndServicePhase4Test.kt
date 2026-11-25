package com.frogweather.phase4

import com.frogweather.WeatherType
import com.frogweather.data.AppPrefsKeys
import com.frogweather.data.KeyValueStore
import com.frogweather.data.WeatherScenario
import com.frogweather.phase1.InMemoryKeyValueStore
import com.frogweather.service.BootReceiver
import com.frogweather.service.WeatherNotificationService
import com.frogweather.widget.WeatherWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class WidgetAndServicePhase4Test {

    private class FloatCapableStore : KeyValueStore {
        private val entries = linkedMapOf<String, Any>()
        override fun getString(key: String, defaultValue: String?): String? =
            (entries[key] as? String) ?: defaultValue

        override fun putString(key: String, value: String?) {
            if (value == null) entries.remove(key) else entries[key] = value
        }

        override fun remove(key: String) {
            entries.remove(key)
        }

        fun putFloat(key: String, value: Float) {
            entries[key] = value
        }

        fun getFloat(key: String, defaultValue: Float): Float =
            (entries[key] as? Number)?.toFloat() ?: defaultValue
    }

    @Test
    fun isValidCoordinatePair_rejectsNullIslandAndAcceptsEquatorOrPrimeMeridian() {
        assertFalse(WeatherWidgetProvider.isValidCoordinatePair(0.0, 0.0))
        assertFalse(WeatherWidgetProvider.isValidCoordinatePair(Double.NaN, 10.0))
        assertFalse(WeatherWidgetProvider.isValidCoordinatePair(95.0, 10.0))
        assertTrue(WeatherWidgetProvider.isValidCoordinatePair(0.0, 10.0))
        assertTrue(WeatherWidgetProvider.isValidCoordinatePair(51.5, 0.0))
    }

    @Test
    fun resolveWidgetLocation_fallsBackToSharedFrogWeatherPrefsAndSkipsZeroZero() {
        val widgetStore = FloatCapableStore().apply {
            putFloat("lat_1", 0.0f)
            putFloat("lon_1", 0.0f)
        }
        val sharedStore = FloatCapableStore().apply {
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 52.52f)
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 13.405f)
            putString(AppPrefsKeys.KEY_LAST_CITY_NAME, "Berlin")
            putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, "DE")
        }

        val resolved = WeatherWidgetProvider.resolveWidgetLocation(1, widgetStore, sharedStore)
        assertNotNull(resolved)
        assertEquals("Berlin", resolved!!.cityName)
        assertEquals(52.52, resolved.latitude, 0.01)
        assertEquals(13.405, resolved.longitude, 0.01)

        val emptyResolved = WeatherWidgetProvider.resolveWidgetLocation(
            99,
            InMemoryKeyValueStore(),
            InMemoryKeyValueStore()
        )
        assertNull(emptyResolved)
        val placeholder = WeatherWidgetProvider.buildPlaceholderDisplayState()
        assertEquals("FrogWeather", placeholder.cityText)
        assertEquals("--°", placeholder.temperatureText)
        assertEquals("🐸", placeholder.emoji)
        assertTrue(WeatherWidgetProvider.REQUIRED_WIDGET_CATEGORY.contains("home_screen"))
        assertTrue(WeatherWidgetProvider.REQUIRED_WIDGET_CATEGORY.contains("keyguard"))
    }

    @Test
    fun resolveLocalCityImageFilename_usesExactScenarioAndNeverUnsplash() {
        val map = mapOf(WeatherScenario.SUNNY to "berlin_sunny.jpg")
        val found = WeatherWidgetProvider.resolveLocalCityImageFilename(WeatherScenario.SUNNY, map)
        val missing = WeatherWidgetProvider.resolveLocalCityImageFilename(WeatherScenario.BLIZZARD, map)
        assertEquals("berlin_sunny.jpg", found)
        assertFalse(found!!.contains("unsplash.com"))
        assertNull(missing)
    }

    @Test
    fun liveNotificationState_buildsPublicNotificationWithoutNaN() {
        val state = WeatherNotificationService.buildLiveNotificationState(
            tempC = 18.4,
            condition = "Sonnig",
            locationName = "München",
            weatherType = WeatherType.SUNNY
        )
        assertTrue(state.title.contains("18°"))
        assertTrue(state.title.contains("Sonnig"))
        assertTrue(state.contentText.contains("München"))
        assertEquals(1, state.visibility)
        assertTrue(state.ongoing)

        val nanState = WeatherNotificationService.buildLiveNotificationState(
            tempC = Double.NaN,
            condition = null,
            locationName = "",
            weatherType = WeatherType.OTHER
        )
        assertFalse(nanState.title.contains("NaN"))
        assertTrue(nanState.title.isNotBlank())
    }

    @Test
    fun bootReceiver_handlesSupportedActionsAndIgnoresOthers() {
        var calls = 0
        assertTrue(BootReceiver.handleIntentAction(BootReceiver.ACTION_BOOT_COMPLETED) { calls++ })
        assertTrue(BootReceiver.handleIntentAction(BootReceiver.ACTION_MY_PACKAGE_REPLACED) { calls++ })
        assertTrue(BootReceiver.handleIntentAction(BootReceiver.ACTION_TIME_SET) { calls++ })
        assertTrue(BootReceiver.handleIntentAction(BootReceiver.ACTION_TIMEZONE_CHANGED) { calls++ })
        assertEquals(4, calls)

        assertFalse(BootReceiver.handleIntentAction("android.intent.action.AIRPLANE_MODE") { calls++ })
        assertFalse(BootReceiver.handleIntentAction(null) { calls++ })
        assertEquals(4, calls)
    }

    @Test
    fun resolveWidgetLocation_doesNotPairStaleSharedCityNameWithDifferentWidgetCoords() {
        val widgetStore = FloatCapableStore().apply {
            putFloat("lat_7", 52.52f)
            putFloat("lon_7", 13.405f)
            // city_7 intentionally absent
        }
        val sharedStore = FloatCapableStore().apply {
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 48.1351f)
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 11.5820f)
            putString(AppPrefsKeys.KEY_LAST_CITY_NAME, "München")
            putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, "DE")
        }

        val resolved = WeatherWidgetProvider.resolveWidgetLocation(7, widgetStore, sharedStore)
        assertNotNull(resolved)
        assertEquals(52.52, resolved!!.latitude, 0.01)
        assertEquals(13.405, resolved.longitude, 0.01)
        assertNull(resolved.cityName)
    }

    @Test
    fun weatherNotificationService_resolveCoordinatesIsPublic() {
        val method = WeatherNotificationService::class.java.getMethod(
            "resolveCoordinates",
            java.lang.Double::class.java,
            java.lang.Double::class.java,
            java.lang.Double::class.java,
            java.lang.Double::class.java,
            java.lang.Double::class.java,
            java.lang.Double::class.java
        )
        assertTrue(Modifier.isPublic(method.modifiers))
        assertEquals(
            52.52 to 13.405,
            WeatherNotificationService.resolveCoordinates(52.52, 13.405, null, null, null, null)
        )
    }

    @Test
    fun resolveWidgetLocation_pinnedWidgetDoesNotInheritMismatchedSharedCountry() {
        val widgetStore = FloatCapableStore().apply {
            putFloat("lat_9", 52.52f)
            putFloat("lon_9", 13.405f)
            putString("city_9", "Berlin")
        }
        val sharedStore = FloatCapableStore().apply {
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LAT, 35.6762f)
            putFloat(AppPrefsKeys.KEY_LAST_CITY_LON, 139.6503f)
            putString(AppPrefsKeys.KEY_LAST_CITY_NAME, "Tokyo")
            putString(AppPrefsKeys.KEY_LAST_CITY_COUNTRY, "JP")
        }

        val resolved = WeatherWidgetProvider.resolveWidgetLocation(9, widgetStore, sharedStore)
        assertNotNull(resolved)
        assertEquals("Berlin", resolved!!.cityName)
        assertNull(resolved.country)
    }
}

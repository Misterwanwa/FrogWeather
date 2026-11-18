package com.frogweather

import com.frogweather.service.WeatherNotificationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherNotificationServiceTest {

    @Test
    fun resolveCoordinates_prefersValidIntentCoordinates() {
        val resolved = WeatherNotificationService.resolveCoordinates(
            intentLat = 52.52,
            intentLon = 13.405,
            savedNotifLat = 48.137,
            savedNotifLon = 11.576,
            savedCityLat = 50.11,
            savedCityLon = 8.68
        )

        assertEquals(52.52 to 13.405, resolved)
    }

    @Test
    fun resolveCoordinates_fallsBackToSavedNotificationCoordsOnNullIntent() {
        val resolved = WeatherNotificationService.resolveCoordinates(
            intentLat = null,
            intentLon = null,
            savedNotifLat = 48.137,
            savedNotifLon = 11.576,
            savedCityLat = 50.11,
            savedCityLon = 8.68
        )

        assertEquals(48.137 to 11.576, resolved)
    }

    @Test
    fun resolveCoordinates_ignoresZeroZeroIntentAndFallsBack() {
        val resolved = WeatherNotificationService.resolveCoordinates(
            intentLat = 0.0,
            intentLon = 0.0,
            savedNotifLat = 48.137,
            savedNotifLon = 11.576,
            savedCityLat = 50.11,
            savedCityLon = 8.68
        )

        assertEquals(48.137 to 11.576, resolved)
    }

    @Test
    fun resolveCoordinates_fallsBackToSavedCityCoordsWhenNotifCoordsMissing() {
        val resolved = WeatherNotificationService.resolveCoordinates(
            intentLat = null,
            intentLon = null,
            savedNotifLat = null,
            savedNotifLon = null,
            savedCityLat = 50.11,
            savedCityLon = 8.68
        )

        assertEquals(50.11 to 8.68, resolved)
    }

    @Test
    fun resolveCoordinates_returnsNullWhenAllSourcesAreNullOrNullIsland() {
        val resolvedNull = WeatherNotificationService.resolveCoordinates(
            intentLat = null,
            intentLon = null,
            savedNotifLat = null,
            savedNotifLon = null,
            savedCityLat = null,
            savedCityLon = null
        )
        assertNull(resolvedNull)

        val resolvedZero = WeatherNotificationService.resolveCoordinates(
            intentLat = 0.0,
            intentLon = 0.0,
            savedNotifLat = 0.0,
            savedNotifLon = 0.0,
            savedCityLat = 0.0,
            savedCityLon = 0.0
        )
        assertNull(resolvedZero)
    }
}

package com.frogweather.data

import android.content.Context
import android.content.SharedPreferences

object AppPrefsKeys {
    const val PREFS_MAIN = "frog_weather_prefs"
    const val PREFS_FAVORITES = "frog_weather_favorites"
    const val PREFS_THEME = "frog_weather_theme"
    const val PREFS_TRACK_RECORD = "frog_weather_track_record"
    const val PREFS_SPORT = "frog_weather_sport"
    const val PREFS_BRIEFING = "frog_weather_briefing"

    const val KEY_LAST_CITY_NAME = "last_city_name"
    const val KEY_LAST_CITY_COUNTRY = "last_city_country"
    const val KEY_LAST_CITY_LAT = "last_city_lat"
    const val KEY_LAST_CITY_LON = "last_city_lon"
    const val KEY_SELECTED_SPORT = "selected_sport_type"

    const val KEY_LAST_WEATHER_TEMP = "notification_last_temp"
    const val KEY_LAST_WEATHER_CONDITION = "notification_last_condition"
    const val KEY_LAST_WEATHER_TYPE = "notification_last_weather_type"
    const val KEY_LAST_WEATHER_RAW_MAIN = "last_weather_raw_main"
    const val KEY_LAST_WEATHER_SUNRISE = "last_weather_sunrise"
    const val KEY_LAST_WEATHER_SUNSET = "last_weather_sunset"
    const val KEY_LAST_WEATHER_UPDATED_AT = "last_weather_updated_at"
    const val KEY_LAST_WEATHER_HINT = "last_weather_hint"

    const val KEY_BRIEFING_MORNING_ENABLED = "briefing_morning_enabled"
    const val KEY_BRIEFING_MORNING_HOUR = "briefing_morning_hour"
    const val KEY_BRIEFING_MORNING_MINUTE = "briefing_morning_minute"
    const val KEY_BRIEFING_EVENING_ENABLED = "briefing_evening_enabled"
    const val KEY_BRIEFING_EVENING_HOUR = "briefing_evening_hour"
    const val KEY_BRIEFING_EVENING_MINUTE = "briefing_evening_minute"
}

interface KeyValueStore {
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String?)
    fun remove(key: String)
}

class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences
) : KeyValueStore {
    override fun getString(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
            apply()
        }
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun getFloat(key: String, defaultValue: Float = 0f): Float =
        if (prefs.contains(key)) prefs.getFloat(key, defaultValue) else defaultValue

    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    companion object {
        fun forPrefs(context: Context, prefsName: String): SharedPreferencesKeyValueStore =
            SharedPreferencesKeyValueStore(
                context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            )
    }
}

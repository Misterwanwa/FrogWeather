package com.frogweather.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import coil.imageLoader
import coil.request.ImageRequest
import com.frogweather.BuildConfig
import com.frogweather.MainActivity
import com.frogweather.R
import com.frogweather.WeatherType
import com.frogweather.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        // Widget added to home screen
    }

    override fun onDisabled(context: Context) {
        // Widget removed from home screen
    }

    companion object {
        private const val TAG = "WeatherWidget"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, WeatherWidgetProvider::class.java)
            )
            
            for (widgetId in widgetIds) {
                updateWidget(context, appWidgetManager, widgetId)
            }
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val lat = prefs.getFloat("lat_$appWidgetId", 0f).toDouble()
            val lon = prefs.getFloat("lon_$appWidgetId", 0f).toDouble()
            val cityName = prefs.getString("city_$appWidgetId", null)
            
            // Create RemoteViews
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            
            // Set click intent to open app
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_click_area, pendingIntent)
            
            // Update data if we have coordinates
            if (lat != 0.0 && lon != 0.0) {
                scope.launch {
                    try {
                        // Fetch weather data
                        val response = RetrofitClient.service.currentWeather(
                            lat, lon, BuildConfig.OPENWEATHER_API_KEY
                        )
                        
                        val temp = response.main.temp.toInt()
                        val weatherMain = response.weather.firstOrNull()?.main ?: ""
                        val weatherType = WeatherType.fromString(weatherMain)
                        val city = cityName ?: response.name ?: "--"
                        val country = response.sys?.country ?: ""
                        val locationText = if (country.isNotEmpty()) "$city, $country" else city
                        
                        // Update views
                        views.setTextViewText(R.id.widget_city, locationText)
                        views.setTextViewText(R.id.widget_temp, "${temp}°")
                        views.setTextViewText(R.id.widget_emoji, weatherType.emoji)
                        
                        // Try to load city image
                        loadCityImage(context, city, views, appWidgetId, appWidgetManager)
                        
                        // Update widget
                        withContext(Dispatchers.Main) {
                            appWidgetManager.updateAppWidget(appWidgetId, views)
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating widget", e)
                    }
                }
            } else {
                // No location set yet
                views.setTextViewText(R.id.widget_city, "FrogWeather")
                views.setTextViewText(R.id.widget_temp, "--°")
                views.setTextViewText(R.id.widget_emoji, "🐸")
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
        
        private suspend fun loadCityImage(
            context: Context,
            city: String,
            views: RemoteViews,
            appWidgetId: Int,
            appWidgetManager: AppWidgetManager
        ) {
            try {
                // Try to get cached image first
                val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                val cachedUrl = prefs.getString("image_$city", null)
                
                val imageUrl = cachedUrl ?: fetchCityImageUrl(city)
                
                if (imageUrl != null && !cachedUrl.equals(imageUrl)) {
                    prefs.edit().putString("image_$city", imageUrl).apply()
                }
                
                imageUrl?.let { url ->
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false) // Required for RemoteViews
                        .build()
                    
                    val drawable = context.imageLoader.execute(request).drawable
                    drawable?.let {
                        val bitmap = drawableToBitmap(it)
                        views.setImageViewBitmap(R.id.widget_city_image, bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading city image", e)
            }
        }
        
        private suspend fun fetchCityImageUrl(city: String): String? {
            return try {
                // Using Unsplash Source API for city images
                // Returns a random image of the city
                "https://source.unsplash.com/400x400/?$city,city,landmark"
            } catch (e: Exception) {
                null
            }
        }
        
        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }
            
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }
        
        fun saveWidgetLocation(
            context: Context,
            appWidgetId: Int,
            lat: Double,
            lon: Double,
            cityName: String?
        ) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("lat_$appWidgetId", lat.toFloat())
                .putFloat("lon_$appWidgetId", lon.toFloat())
                .putString("city_$appWidgetId", cityName)
                .apply()
            
            // Trigger update
            updateAllWidgets(context)
        }
    }
}

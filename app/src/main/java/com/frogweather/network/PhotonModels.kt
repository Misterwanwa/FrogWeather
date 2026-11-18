package com.frogweather.network

import com.squareup.moshi.Json

/**
 * Minimal subset of the Photon (komoot) GeoJSON response.
 * Endpoint: https://photon.komoot.io/api/?q=...&lang=de&limit=10
 */
data class PhotonResponse(
    val features: List<PhotonFeature>
)

data class PhotonFeature(
    val properties: PhotonProperties,
    val geometry: PhotonGeometry
) {
    /** Human-readable label in the format: "Name - PLZ - State" */
    fun displayLabel(): String {
        val p = properties
        val parts = mutableListOf<String>()
        // Build name: combine name + (optional) city context in parens
        val name = buildString {
            append(p.name ?: p.city ?: p.town ?: p.village ?: return@buildString)
            val parent = p.city ?: p.town ?: p.village
            if (parent != null && parent != p.name) append(" ($parent)")
        }
        if (name.isNotEmpty()) parts.add(name)
        p.postcode?.let { parts.add(it) }
        // Region descriptor: county/state
        val region = p.county ?: p.state
        if (!region.isNullOrEmpty()) parts.add(region)
        return parts.joinToString(" - ")
    }

    /** The city name to pass to OpenWeatherMap city search */
    fun cityQuery(): String =
        properties.city
            ?: properties.town
            ?: properties.village
            ?: properties.name
            ?: ""
}

data class PhotonGeometry(
    val coordinates: List<Double>
)

data class PhotonProperties(
    val name: String?,
    val city: String?,
    val town: String?,
    val village: String?,
    val postcode: String?,
    val county: String?,
    val state: String?,
    val country: String?,
    @Json(name = "country_code") val countryCode: String?
)

package com.frogweather.data

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.math.abs

data class FavoriteLocation(
    val id: String,
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val addedAtMillis: Long
)

private data class RawFavoriteLocation(
    val id: String? = null,
    val name: String? = null,
    val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val addedAtMillis: Long? = null
) {
    fun toFavoriteOrNull(): FavoriteLocation? {
        val safeId = id?.takeIf { it.isNotBlank() } ?: return null
        val safeName = name?.takeIf { it.isNotBlank() } ?: return null
        val safeCountry = country ?: ""
        val safeLat = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val safeLon = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val safeAdded = addedAtMillis ?: 0L
        return FavoriteLocation(
            id = safeId,
            name = safeName,
            country = safeCountry,
            latitude = safeLat,
            longitude = safeLon,
            addedAtMillis = safeAdded
        )
    }
}

class FavoritesRepository(private val storage: KeyValueStore) {

    companion object {
        private const val KEY_FAVORITES_JSON = "favorites_json"
        private const val KEY_ACTIVE_FAVORITE_ID = "active_favorite_id"
        private const val COORD_TOLERANCE_DEG = 0.01
        private const val COORD_EPSILON = 1e-9

        private val moshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        private val listType = Types.newParameterizedType(
            List::class.java,
            FavoriteLocation::class.java
        )

        private val listAdapter: JsonAdapter<List<FavoriteLocation?>> = moshi.adapter(listType)

        private val rawListType = Types.newParameterizedType(
            List::class.java,
            Any::class.java
        )
        private val rawListAdapter: JsonAdapter<List<Any?>> = moshi.adapter(rawListType)
        private val rawFavoriteAdapter: JsonAdapter<RawFavoriteLocation> =
            moshi.adapter(RawFavoriteLocation::class.java)

        private fun isValidCoords(lat: Double, lon: Double): Boolean =
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

        private fun coordsMatch(latA: Double, lonA: Double, latB: Double, lonB: Double): Boolean =
            isValidCoords(latA, lonA) &&
                isValidCoords(latB, lonB) &&
                abs(latA - latB) <= COORD_TOLERANCE_DEG + COORD_EPSILON &&
                abs(lonA - lonB) <= COORD_TOLERANCE_DEG + COORD_EPSILON
    }

    fun getFavorites(): List<FavoriteLocation> {
        val raw = storage.getString(KEY_FAVORITES_JSON, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val rawList = try {
            rawListAdapter.fromJson(raw)
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return rawList.mapNotNull { element ->
            if (element == null) {
                null
            } else {
                runCatching {
                    rawFavoriteAdapter.fromJsonValue(element)?.toFavoriteOrNull()
                }.getOrNull()
            }
        }
    }

    fun addFavorite(location: FavoriteLocation): List<FavoriteLocation> {
        val current = getFavorites().toMutableList()
        if (!isValidCoords(location.latitude, location.longitude) ||
            location.id.isBlank() ||
            location.name.isBlank()
        ) {
            return current.toList()
        }
        val idMatchIndex = current.indexOfFirst { it.id == location.id }
        val existingIndex = if (idMatchIndex >= 0) {
            idMatchIndex
        } else {
            current.indexOfFirst { existing ->
                coordsMatch(existing.latitude, existing.longitude, location.latitude, location.longitude)
            }
        }
        val result = if (existingIndex >= 0) {
            val existing = current[existingIndex]
            val updatedEntry = location.copy(id = existing.id)
            current[existingIndex] = updatedEntry

            val activeId = getActiveFavoriteId()
            val deduplicated = mutableListOf<FavoriteLocation>()
            current.forEachIndexed { index, item ->
                if (index == existingIndex) {
                    deduplicated.add(item)
                } else if (coordsMatch(item.latitude, item.longitude, location.latitude, location.longitude)) {
                    if (activeId == item.id) {
                        setActiveFavoriteId(updatedEntry.id)
                    }
                } else {
                    deduplicated.add(item)
                }
            }
            deduplicated
        } else {
            current.add(location)
            current
        }
        persistFavorites(result)
        return result.toList()
    }

    fun removeFavorite(id: String): List<FavoriteLocation> {
        val updated = getFavorites().filterNot { it.id == id }
        persistFavorites(updated)
        if (getActiveFavoriteId() == id) {
            setActiveFavoriteId(null)
        }
        return updated
    }

    fun getActiveFavoriteId(): String? =
        storage.getString(KEY_ACTIVE_FAVORITE_ID, null)?.takeIf { it.isNotBlank() }

    fun setActiveFavoriteId(id: String?) {
        if (id.isNullOrBlank()) {
            storage.remove(KEY_ACTIVE_FAVORITE_ID)
        } else {
            storage.putString(KEY_ACTIVE_FAVORITE_ID, id)
        }
    }

    fun findMatchingFavorite(lat: Double, lon: Double): FavoriteLocation? {
        if (!isValidCoords(lat, lon)) return null
        return getFavorites().firstOrNull {
            coordsMatch(it.latitude, it.longitude, lat, lon)
        }
    }

    private fun persistFavorites(list: List<FavoriteLocation>) {
        val safeList = list.filter {
            isValidCoords(it.latitude, it.longitude) && it.id.isNotBlank() && it.name.isNotBlank()
        }
        storage.putString(KEY_FAVORITES_JSON, listAdapter.toJson(safeList))
    }
}

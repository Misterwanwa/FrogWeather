package com.frogweather.phase1

import com.frogweather.data.FavoriteLocation
import com.frogweather.data.FavoritesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FavoritesRepositoryTest {

    @Test
    fun emptyStore_returnsEmptyListAndNullActiveId() {
        val repo = FavoritesRepository(InMemoryKeyValueStore())
        assertEquals(emptyList<FavoriteLocation>(), repo.getFavorites())
        assertNull(repo.getActiveFavoriteId())
    }

    @Test
    fun addFavorite_persistsAcrossRepositoryInstances() {
        val store = InMemoryKeyValueStore()
        val repo1 = FavoritesRepository(store)
        val munich = FavoriteLocation("m1", "München", "DE", 48.137, 11.576, 1_700_000_000_000L)
        val saoPaulo = FavoriteLocation("sp1", "São Paulo", "BR", -23.55, -46.63, 1_700_000_001_000L)

        repo1.addFavorite(munich)
        repo1.addFavorite(saoPaulo)

        val repo2 = FavoritesRepository(store)
        val loaded = repo2.getFavorites()
        assertEquals(2, loaded.size)
        assertEquals("München", loaded[0].name)
        assertEquals("São Paulo", loaded[1].name)
    }

    @Test
    fun addFavorite_deduplicatesById() {
        val repo = FavoritesRepository(InMemoryKeyValueStore())
        repo.addFavorite(FavoriteLocation("id1", "Berlin", "DE", 52.52, 13.405, 100L))
        val updated = repo.addFavorite(FavoriteLocation("id1", "Berlin Mitte", "DE", 52.52, 13.405, 200L))

        assertEquals(1, updated.size)
        assertEquals("Berlin Mitte", updated[0].name)
    }

    @Test
    fun addFavorite_deduplicatesWithinCoordinateToleranceAndPreservesExistingId() {
        val repo = FavoritesRepository(InMemoryKeyValueStore())
        repo.addFavorite(FavoriteLocation("orig_id", "Tokyo", "JP", 35.6762, 139.6503, 100L))
        repo.setActiveFavoriteId("orig_id")

        // Within <= 0.01° merges and preserves "orig_id"
        val afterClose = repo.addFavorite(FavoriteLocation("new_id", "Tokyo Central", "JP", 35.6800, 139.6550, 200L))
        assertEquals(1, afterClose.size)
        assertEquals("orig_id", afterClose[0].id)
        assertEquals("Tokyo Central", afterClose[0].name)
        assertEquals("orig_id", repo.getActiveFavoriteId())

        // Outside > 0.01° adds second entry
        val afterFar = repo.addFavorite(FavoriteLocation("yokohama", "Yokohama", "JP", 35.4437, 139.6380, 300L))
        assertEquals(2, afterFar.size)
    }

    @Test
    fun removeFavorite_deletesEntryAndClearsActiveIdWhenActiveRemoved() {
        val repo = FavoritesRepository(InMemoryKeyValueStore())
        repo.addFavorite(FavoriteLocation("a", "Hamburg", "DE", 53.55, 9.99, 100L))
        repo.addFavorite(FavoriteLocation("b", "Köln", "DE", 50.94, 6.96, 200L))
        repo.setActiveFavoriteId("a")

        val remaining = repo.removeFavorite("a")
        assertEquals(1, remaining.size)
        assertEquals("b", remaining[0].id)
        assertNull(repo.getActiveFavoriteId())
    }

    @Test
    fun removeFavorite_preservesActiveIdWhenDifferentFavoriteRemoved() {
        val repo = FavoritesRepository(InMemoryKeyValueStore())
        repo.addFavorite(FavoriteLocation("a", "Hamburg", "DE", 53.55, 9.99, 100L))
        repo.addFavorite(FavoriteLocation("b", "Köln", "DE", 50.94, 6.96, 200L))
        repo.setActiveFavoriteId("a")

        val remaining = repo.removeFavorite("b")
        assertEquals(1, remaining.size)
        assertEquals("a", repo.getActiveFavoriteId())
    }

    @Test
    fun setActiveFavoriteId_persistsAndClearsWithNull() {
        val store = InMemoryKeyValueStore()
        val repo = FavoritesRepository(store)
        repo.setActiveFavoriteId("fav_1")
        assertEquals("fav_1", FavoritesRepository(store).getActiveFavoriteId())

        repo.setActiveFavoriteId(null)
        assertNull(FavoritesRepository(store).getActiveFavoriteId())
    }

    @Test
    fun findMatchingFavorite_usesCoordinateTolerance() {
        val repo = FavoritesRepository(InMemoryKeyValueStore())
        repo.addFavorite(FavoriteLocation("muc", "München", "DE", 48.137, 11.576, 100L))

        assertNotNull(repo.findMatchingFavorite(48.141, 11.580))
        assertNull(repo.findMatchingFavorite(48.500, 11.576))
    }

    @Test
    fun corruptJsonInStorage_returnsEmptyListSafely() {
        val store = InMemoryKeyValueStore()
        store.putString("favorites_json", "{broken_json!!")
        val repo = FavoritesRepository(store)

        assertEquals(emptyList<FavoriteLocation>(), repo.getFavorites())
    }

    @Test
    fun jsonArrayWithNullElements_filtersNullsWithoutNpe() {
        val store = InMemoryKeyValueStore()
        store.putString("favorites_json", "[null]")
        val repoOnlyNull = FavoritesRepository(store)
        assertEquals(emptyList<FavoriteLocation>(), repoOnlyNull.getFavorites())

        val mixedPayload = """
            [
              {"id":"1","name":"Berlin","country":"DE","latitude":52.52,"longitude":13.405,"addedAtMillis":100},
              null
            ]
        """.trimIndent()
        store.putString("favorites_json", mixedPayload)
        val repoMixed = FavoritesRepository(store)
        val loaded = repoMixed.getFavorites()
        assertEquals(1, loaded.size)
        assertEquals("1", loaded[0].id)
        assertEquals("Berlin", loaded[0].name)
    }

    @Test
    fun coordinateTolerance_handlesIeee754BoundaryAndRejectsBeyondTolerance() {
        val repo = FavoritesRepository(InMemoryKeyValueStore())
        val baseLat = -23.55
        val baseLon = -46.63
        repo.addFavorite(FavoriteLocation("sp", "São Paulo", "BR", baseLat, baseLon, 100L))

        val boundaryLat = baseLat - 0.01 // -23.560000000000002 in IEEE 754 Double
        assertNotNull(repo.findMatchingFavorite(boundaryLat, baseLon))
        assertNull(repo.findMatchingFavorite(baseLat - 0.011, baseLon))

        val deduped = repo.addFavorite(FavoriteLocation("sp_dup", "São Paulo Center", "BR", boundaryLat, baseLon, 200L))
        assertEquals(1, deduped.size)
        assertEquals("sp", deduped[0].id)
        assertEquals("São Paulo Center", deduped[0].name)

        // 0.011° outside stored entry's tolerance must NOT match or deduplicate
        assertNull(repo.findMatchingFavorite(boundaryLat - 0.011, baseLon))
        val afterFar = repo.addFavorite(FavoriteLocation("far", "Nearby", "BR", boundaryLat - 0.011, baseLon, 300L))
        assertEquals(2, afterFar.size)
    }

    @Test
    fun addFavorite_updatingByIdOntoAnotherEntryCoords_removesDuplicateAndTransfersActiveId() {
        val repo = FavoritesRepository(InMemoryKeyValueStore())
        repo.addFavorite(FavoriteLocation("a", "Alpha", "DE", 10.0, 20.0, 1L))
        repo.addFavorite(FavoriteLocation("b", "Beta", "DE", 50.0, 10.0, 2L))
        repo.setActiveFavoriteId("b")

        val updated = repo.addFavorite(FavoriteLocation("a", "AlphaMovedToBeta", "DE", 50.0, 10.0, 3L))
        assertEquals(1, updated.size)
        assertEquals("a", updated[0].id)
        assertEquals("AlphaMovedToBeta", updated[0].name)
        assertEquals(50.0, updated[0].latitude, 1e-6)
        assertEquals(10.0, updated[0].longitude, 1e-6)
        assertEquals("a", repo.getActiveFavoriteId())
    }
}

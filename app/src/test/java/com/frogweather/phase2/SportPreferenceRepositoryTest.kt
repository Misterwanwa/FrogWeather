package com.frogweather.phase2

import com.frogweather.analysis.SportPreferenceRepository
import com.frogweather.analysis.SportType
import com.frogweather.phase1.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Test

class SportPreferenceRepositoryTest {

    @Test
    fun `defaults to RUNNING when storage is empty`() {
        val repo = SportPreferenceRepository(InMemoryKeyValueStore())
        assertEquals(SportType.RUNNING, repo.getSelectedSport())
        assertEquals(8, repo.getAvailableSports().size)
    }

    @Test
    fun `persists and reloads every SportType across instances`() {
        val store = InMemoryKeyValueStore()
        for (sport in SportType.entries) {
            SportPreferenceRepository(store).setSelectedSport(sport)
            val reloaded = SportPreferenceRepository(store).getSelectedSport()
            assertEquals(sport, reloaded)
        }
    }

    @Test
    fun `SportType fromId handles case, whitespace, enum names, and invalid inputs`() {
        assertEquals(SportType.CYCLING, SportType.fromId("  CYCLING "))
        assertEquals(SportType.WINTER_SPORT, SportType.fromId("winter_sport"))
        assertEquals(SportType.TEAM_SPORT, SportType.fromId("Team_Sport"))
        assertEquals(SportType.RUNNING, SportType.fromId(null))
        assertEquals(SportType.RUNNING, SportType.fromId("unknown_sport"))
    }
}

package com.frogweather.phase3

import com.frogweather.map.MapForecastModel
import com.frogweather.map.MapState
import com.frogweather.map.MapStateController
import com.frogweather.map.MapViewMode
import com.frogweather.map.MapWeatherLayer
import com.frogweather.map.PressureLevel
import com.frogweather.map.TimelineFrame
import com.frogweather.map.TimelineFrameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStateControllerTest {

    @Test
    fun defaultState_matchesContractDefaults() {
        val state = MapState()
        assertEquals(MapViewMode.MODE_2D, state.viewMode)
        assertTrue(state.activeLayers.isEmpty())
        assertEquals(PressureLevel.SFC, state.pressureLevel)
        assertEquals(MapForecastModel.GFS, state.model)
        assertEquals(0, state.timelineIndex)
        assertFalse(state.isPlaying)
        assertEquals(10, MapWeatherLayer.entries.size)
    }

    @Test
    fun toggleAllTenLayers_andSwitchModesLevelsModels_preservesActiveLayers() {
        val controller = MapStateController()

        MapWeatherLayer.entries.forEach { layer ->
            controller.toggleLayer(layer)
        }
        assertEquals(MapWeatherLayer.entries.toSet(), controller.state.value.activeLayers)

        controller.setViewMode(MapViewMode.GLOBE_3D)
        assertEquals(MapViewMode.GLOBE_3D, controller.state.value.viewMode)
        assertEquals(10, controller.state.value.activeLayers.size)

        controller.setViewMode(MapViewMode.STREET_3D)
        assertEquals(MapViewMode.STREET_3D, controller.state.value.viewMode)
        assertEquals(10, controller.state.value.activeLayers.size)

        controller.setPressureLevel(PressureLevel.HPA_850)
        controller.setModel(MapForecastModel.ECMWF)
        assertEquals(PressureLevel.HPA_850, controller.state.value.pressureLevel)
        assertEquals(MapForecastModel.ECMWF, controller.state.value.model)
        assertEquals(10, controller.state.value.activeLayers.size)

        controller.toggleLayer(MapWeatherLayer.CAPE)
        assertFalse(MapWeatherLayer.CAPE in controller.state.value.activeLayers)
        assertEquals(9, controller.state.value.activeLayers.size)

        controller.clearLayers()
        assertTrue(controller.state.value.activeLayers.isEmpty())
    }

    @Test
    fun timelineNavigation_seekStepLoopAndPlaybackGuard_behaveCorrectly() {
        val controller = MapStateController()

        // Cannot play with 0 or 1 frames
        controller.togglePlayback()
        assertFalse(controller.state.value.isPlaying)

        val single = listOf(TimelineFrame(1000L, "12:00", TimelineFrameSource.RADAR_PAST))
        controller.setTimelineFrames(single)
        controller.togglePlayback()
        assertFalse(controller.state.value.isPlaying)

        val frames = listOf(
            TimelineFrame(1000L, "12:00", TimelineFrameSource.RADAR_PAST),
            TimelineFrame(2000L, "12:10", TimelineFrameSource.RADAR_PAST),
            TimelineFrame(3000L, "13:00", TimelineFrameSource.FORECAST_MODEL, forecastHourIndex = 0)
        )
        controller.setTimelineFrames(frames, initialIndex = 1)
        assertEquals(1, controller.state.value.timelineIndex)

        controller.togglePlayback()
        assertTrue(controller.state.value.isPlaying)

        controller.stepForward(loop = true)
        assertEquals(2, controller.state.value.timelineIndex)
        controller.stepForward(loop = true)
        assertEquals(0, controller.state.value.timelineIndex)

        controller.stepBackward(loop = true)
        assertEquals(2, controller.state.value.timelineIndex)

        controller.stepForward(loop = false)
        assertEquals(2, controller.state.value.timelineIndex)

        controller.seekTimeline(99)
        assertEquals(2, controller.state.value.timelineIndex)
        controller.seekTimeline(-5)
        assertEquals(0, controller.state.value.timelineIndex)
    }
}

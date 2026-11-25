package com.frogweather.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MapViewMode { MODE_2D, GLOBE_3D, STREET_3D }

enum class PressureLevel(val hpaLabel: String, val openMeteoSuffix: String) {
    SFC("Sfc", ""),
    HPA_850("850 hPa", "_850hPa"),
    HPA_500("500 hPa", "_500hPa"),
    HPA_250("250 hPa", "_250hPa")
}

enum class MapForecastModel(val id: String, val displayName: String) {
    GFS("gfs_global", "GFS"),
    ECMWF("ecmwf_ifs025", "ECMWF")
}

enum class MapWeatherLayer {
    RADAR,
    SATELLITE,
    WIND,
    GUSTS,
    HUMIDITY,
    CAPE,
    TEMPERATURE,
    PRECIPITATION,
    CLOUDS,
    PRESSURE
}

enum class TimelineFrameSource {
    RADAR_PAST,
    RADAR_NOWCAST,
    FORECAST_MODEL
}

data class TimelineFrame(
    val epochMillis: Long,
    val label: String,
    val source: TimelineFrameSource,
    val radarPath: String? = null,
    val forecastHourIndex: Int? = null
)

data class TimelineState(
    val frames: List<TimelineFrame> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false
) {
    val activeFrame: TimelineFrame? get() = frames.getOrNull(currentIndex)
    val canPlay: Boolean get() = frames.size > 1
}

data class MapState(
    val viewMode: MapViewMode = MapViewMode.MODE_2D,
    val activeLayers: Set<MapWeatherLayer> = emptySet(),
    val pressureLevel: PressureLevel = PressureLevel.SFC,
    val model: MapForecastModel = MapForecastModel.GFS,
    val timelineIndex: Int = 0,
    val isPlaying: Boolean = false,
    val timelineFrames: List<TimelineFrame> = emptyList(),
    val showWarnings: Boolean = true
) {
    val timelineState: TimelineState
        get() = TimelineState(
            frames = timelineFrames,
            currentIndex = timelineIndex,
            isPlaying = isPlaying
        )

    val activeFrame: TimelineFrame?
        get() = timelineFrames.getOrNull(timelineIndex)
}

object MapStateReducer {
    fun setViewMode(state: MapState, mode: MapViewMode): MapState =
        state.copy(viewMode = mode)

    fun toggleLayer(state: MapState, layer: MapWeatherLayer): MapState {
        val nextLayers = if (layer in state.activeLayers) {
            state.activeLayers - layer
        } else {
            state.activeLayers + layer
        }
        return state.copy(activeLayers = nextLayers)
    }

    fun setLayerActive(state: MapState, layer: MapWeatherLayer, active: Boolean): MapState {
        val nextLayers = if (active) {
            state.activeLayers + layer
        } else {
            state.activeLayers - layer
        }
        return state.copy(activeLayers = nextLayers)
    }

    fun clearLayers(state: MapState): MapState =
        state.copy(activeLayers = emptySet())

    fun setPressureLevel(state: MapState, level: PressureLevel): MapState =
        state.copy(pressureLevel = level)

    fun setModel(state: MapState, model: MapForecastModel): MapState =
        state.copy(model = model)

    fun setTimelineFrames(
        state: MapState,
        frames: List<TimelineFrame>,
        initialIndex: Int? = null
    ): MapState {
        val maxIdx = (frames.size - 1).coerceAtLeast(0)
        val clampedIndex = (initialIndex ?: state.timelineIndex).coerceIn(0, maxIdx)
        val canPlay = frames.size > 1
        return state.copy(
            timelineFrames = frames,
            timelineIndex = clampedIndex,
            isPlaying = if (canPlay) state.isPlaying else false
        )
    }

    fun seekTimeline(state: MapState, index: Int): MapState {
        val maxIdx = (state.timelineFrames.size - 1).coerceAtLeast(0)
        return state.copy(timelineIndex = index.coerceIn(0, maxIdx))
    }

    fun stepForward(state: MapState, loop: Boolean = true): MapState {
        val size = state.timelineFrames.size
        if (size <= 1) return state.copy(timelineIndex = 0, isPlaying = false)
        val nextIndex = if (state.timelineIndex + 1 < size) {
            state.timelineIndex + 1
        } else if (loop) {
            0
        } else {
            size - 1
        }
        return state.copy(timelineIndex = nextIndex)
    }

    fun stepBackward(state: MapState, loop: Boolean = true): MapState {
        val size = state.timelineFrames.size
        if (size <= 1) return state.copy(timelineIndex = 0, isPlaying = false)
        val prevIndex = if (state.timelineIndex - 1 >= 0) {
            state.timelineIndex - 1
        } else if (loop) {
            size - 1
        } else {
            0
        }
        return state.copy(timelineIndex = prevIndex)
    }

    fun setPlaying(state: MapState, playing: Boolean): MapState {
        val allowPlay = playing && state.timelineFrames.size > 1
        return state.copy(isPlaying = allowPlay)
    }

    fun togglePlayback(state: MapState): MapState =
        setPlaying(state, !state.isPlaying)

    fun setShowWarnings(state: MapState, show: Boolean): MapState =
        state.copy(showWarnings = show)
}

class MapStateController(initialState: MapState = MapState()) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<MapState> = _state.asStateFlow()

    fun setViewMode(mode: MapViewMode) {
        _state.update { MapStateReducer.setViewMode(it, mode) }
    }

    fun toggleLayer(layer: MapWeatherLayer) {
        _state.update { MapStateReducer.toggleLayer(it, layer) }
    }

    fun setLayerActive(layer: MapWeatherLayer, active: Boolean) {
        _state.update { MapStateReducer.setLayerActive(it, layer, active) }
    }

    fun clearLayers() {
        _state.update { MapStateReducer.clearLayers(it) }
    }

    fun setPressureLevel(level: PressureLevel) {
        _state.update { MapStateReducer.setPressureLevel(it, level) }
    }

    fun setModel(model: MapForecastModel) {
        _state.update { MapStateReducer.setModel(it, model) }
    }

    fun setTimelineFrames(frames: List<TimelineFrame>, initialIndex: Int? = null) {
        _state.update { MapStateReducer.setTimelineFrames(it, frames, initialIndex) }
    }

    fun seekTimeline(index: Int) {
        _state.update { MapStateReducer.seekTimeline(it, index) }
    }

    fun stepForward(loop: Boolean = true) {
        _state.update { MapStateReducer.stepForward(it, loop) }
    }

    fun stepBackward(loop: Boolean = true) {
        _state.update { MapStateReducer.stepBackward(it, loop) }
    }

    fun setPlaying(playing: Boolean) {
        _state.update { MapStateReducer.setPlaying(it, playing) }
    }

    fun togglePlayback() {
        _state.update { MapStateReducer.togglePlayback(it) }
    }

    fun setShowWarnings(show: Boolean) {
        _state.update { MapStateReducer.setShowWarnings(it, show) }
    }
}

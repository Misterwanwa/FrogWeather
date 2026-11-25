package com.frogweather.phase1

import com.frogweather.data.KeyValueStore

class InMemoryKeyValueStore : KeyValueStore {
    private val map = linkedMapOf<String, String>()

    override fun getString(key: String, defaultValue: String?): String? =
        map[key] ?: defaultValue

    override fun putString(key: String, value: String?) {
        if (value == null) {
            map.remove(key)
        } else {
            map[key] = value
        }
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    fun rawEntries(): Map<String, String> = map.toMap()
}

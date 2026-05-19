package moe.chensi.volume.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AppPreferencesStore(private val dataStore: DataStore<Preferences>) {
    companion object {
        private val key = stringPreferencesKey("apps")

        private val json = Json { ignoreUnknownKeys = true }
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @Serializable
    private data class SerializedState(
        val values: MutableList<AppPreferences>,
        val indices: MutableMap<String, Int>,
        val systemSliderVisibility: MutableMap<String, Boolean> = mutableMapOf(),
        val interceptVolumeKeys: Boolean = true,
        val buttonOffsetX: Float = 0f,
        val buttonOffsetY: Float = 0f,
        val buttonCornerRadius: Float = 28f,
        val buttonSize: Float = 56f,
        val buttonColor: String = "#FF6200EE",
        val idleTimeout: Float = 5f,
        val animationDuration: Float = 300f
    )

    private val lock = Any()
    private var state = SerializedState(mutableListOf(), mutableMapOf())
    val values: List<AppPreferences>
        get() = state.values
    val indices: Map<String, Int>
        get() = synchronized(lock) { state.indices.toMap() }
    fun getSystemSliderVisible(id: String): Boolean {
        return synchronized(lock) { state.systemSliderVisibility[id] ?: true }
    }

    fun setSystemSliderVisible(id: String, value: Boolean) {
        val changed = synchronized(lock) {
            val oldValue = state.systemSliderVisibility[id] ?: true
            if (oldValue == value) {
                return@synchronized false
            }

            val updated = state.systemSliderVisibility.toMutableMap()
            updated[id] = value
            state = state.copy(systemSliderVisibility = updated)
            true
        }

        if (changed) {
            save()
        }
    }

    var systemSliderVisibility: Map<String, Boolean>
        get() = synchronized(lock) { state.systemSliderVisibility.toMap() }
        set(value) {
            val changed = synchronized(lock) {
                if (state.systemSliderVisibility == value) {
                    return@synchronized false
                }

                state = state.copy(systemSliderVisibility = value.toMutableMap())
                true
            }

            if (changed) {
                save()
            }
        }

    var interceptVolumeKeys: Boolean
        get() = synchronized(lock) { state.interceptVolumeKeys }
        set(value) {
            val changed = synchronized(lock) {
                if (state.interceptVolumeKeys == value) {
                    return@synchronized false
                }

                state = state.copy(interceptVolumeKeys = value)
                true
            }

            if (changed) {
                save()
            }
        }

    var buttonOffsetX: Float
        get() = synchronized(lock) { state.buttonOffsetX }
        set(value) {
            val changed = synchronized(lock) {
                if (state.buttonOffsetX == value) return@synchronized false
                state = state.copy(buttonOffsetX = value)
                true
            }
            if (changed) save()
        }

    var buttonOffsetY: Float
        get() = synchronized(lock) { state.buttonOffsetY }
        set(value) {
            val changed = synchronized(lock) {
                if (state.buttonOffsetY == value) return@synchronized false
                state = state.copy(buttonOffsetY = value)
                true
            }
            if (changed) save()
        }

    var buttonCornerRadius: Float
        get() = synchronized(lock) { state.buttonCornerRadius }
        set(value) {
            val changed = synchronized(lock) {
                if (state.buttonCornerRadius == value) return@synchronized false
                state = state.copy(buttonCornerRadius = value)
                true
            }
            if (changed) save()
        }

    var buttonSize: Float
        get() = synchronized(lock) { state.buttonSize }
        set(value) {
            val changed = synchronized(lock) {
                if (state.buttonSize == value) return@synchronized false
                state = state.copy(buttonSize = value)
                true
            }
            if (changed) save()
        }

    var buttonColor: String
        get() = synchronized(lock) { state.buttonColor }
        set(value) {
            val changed = synchronized(lock) {
                if (state.buttonColor == value) return@synchronized false
                state = state.copy(buttonColor = value)
                true
            }
            if (changed) save()
        }

    var idleTimeout: Float
        get() = synchronized(lock) { state.idleTimeout }
        set(value) {
            val changed = synchronized(lock) {
                if (state.idleTimeout == value) return@synchronized false
                state = state.copy(idleTimeout = value)
                true
            }
            if (changed) save()
        }

    var animationDuration: Float
        get() = synchronized(lock) { state.animationDuration }
        set(value) {
            val changed = synchronized(lock) {
                if (state.animationDuration == value) return@synchronized false
                state = state.copy(animationDuration = value)
                true
            }
            if (changed) save()
        }


    fun track(onChange: (first: Boolean) -> Unit) {
        var first = true

        scope.launch {
            dataStore.data.collect { preferences ->
                val valueJson = preferences[key]
                if (valueJson != null) {
                    synchronized(lock) {
                        state = json.decodeFromString<SerializedState>(valueJson)
                    }
                }

                onChange(first)
                @Suppress("AssignedValueIsNeverRead")
                first = false
            }
        }
    }

    fun getOrCreate(packageName: String): AppPreferences {
        synchronized(lock) {
            val index = state.indices[packageName]
            if (index != null) {
                return state.values[index]
            }

            val value = AppPreferences()
            state.indices[packageName] = state.values.size
            state.values.add(value)
            return value
        }
    }

    fun save() {
        scope.launch {
            dataStore.edit { preferences ->
                preferences[key] = Json.encodeToString(state)
            }
        }
    }
}

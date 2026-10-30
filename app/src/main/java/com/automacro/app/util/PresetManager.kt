package com.automacro.app.util

import android.content.Context
import android.content.SharedPreferences
import com.automacro.app.model.MacroPreset
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PresetManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("auto_macro_presets", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val MAX_SLOTS = 7
        private const val KEY_PRESETS = "key_preset_list"
        private const val KEY_CURRENT_SLOT = "key_current_slot_index"
    }

    fun getAllPresets(): MutableList<MacroPreset> {
        val json = prefs.getString(KEY_PRESETS, null)
        if (json.isNullOrEmpty()) {
            val defaultList = createDefaultPresets()
            saveAllPresets(defaultList)
            return defaultList
        }
        return try {
            val type = object : TypeToken<MutableList<MacroPreset>>() {}.type
            var list: MutableList<MacroPreset> = gson.fromJson(json, type)
            if (list.size > MAX_SLOTS) {
                list = list.take(MAX_SLOTS).toMutableList()
                saveAllPresets(list)
            } else if (list.size < MAX_SLOTS) {
                // 补全不足 7 个的槽位
                for (i in (list.size + 1)..MAX_SLOTS) {
                    list.add(MacroPreset(id = i, name = "仓位 $i"))
                }
                saveAllPresets(list)
            }
            list
        } catch (e: Exception) {
            val fallback = createDefaultPresets()
            saveAllPresets(fallback)
            fallback
        }
    }

    fun saveAllPresets(list: List<MacroPreset>) {
        prefs.edit().putString(KEY_PRESETS, gson.toJson(list)).apply()
    }

    fun getPreset(slotId: Int): MacroPreset {
        val list = getAllPresets()
        return list.find { it.id == slotId } ?: MacroPreset(id = slotId, name = "仓位 $slotId")
    }

    fun savePreset(preset: MacroPreset) {
        val list = getAllPresets()
        val index = list.indexOfFirst { it.id == preset.id }
        if (index != -1) {
            list[index] = preset
        } else {
            list.add(preset)
        }
        saveAllPresets(list)
    }

    fun getCurrentSlotIndex(): Int {
        return prefs.getInt(KEY_CURRENT_SLOT, 1)
    }

    fun setCurrentSlotIndex(slotId: Int) {
        prefs.edit().putInt(KEY_CURRENT_SLOT, slotId).apply()
    }

    private fun createDefaultPresets(): MutableList<MacroPreset> {
        val list = mutableListOf<MacroPreset>()
        for (i in 1..MAX_SLOTS) {
            list.add(
                MacroPreset(
                    id = i,
                    name = "仓位 $i",
                    triggerX = 150 + (i * 20) % 200,
                    triggerY = 800 + (i * 30) % 300,
                    targetX = 540,
                    targetY = 700 + (i * 20) % 200,
                    speed = 4 + (i % 5),
                    alpha = 0.65f,
                    isSlideUp = false
                )
            )
        }
        return list
    }
}

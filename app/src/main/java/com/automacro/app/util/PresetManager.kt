package com.automacro.app.util

import android.content.Context
import android.content.SharedPreferences
import com.automacro.app.model.MacroPreset
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PresetManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("auto_macro_presets", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        const val MAX_SLOTS = 7
        private const val KEY_PRESETS = "key_preset_list"
        private const val KEY_CURRENT_SLOT = "key_current_slot_index"
    }

    /**
     * 创建默认仓位：按键 A 与准星物理居中于屏幕中心
     */
    fun createDefaultPreset(slotId: Int): MacroPreset {
        val dm = context.resources.displayMetrics
        val density = dm.density
        val cx = dm.widthPixels / 2
        val cy = dm.heightPixels / 2
        // A键直径 60dp（半径 30dp），准星直径 44dp（半径 22dp）
        val triggerOffset = (30 * density).toInt()
        val crosshairOffset = (22 * density).toInt()
        return MacroPreset(
            id = slotId,
            name = "仓位 $slotId",
            triggerX = cx - triggerOffset,
            triggerY = cy - triggerOffset,
            targetX = cx - crosshairOffset,
            targetY = cy - crosshairOffset,
            speed = 5,
            alpha = 0.65f
        )
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
                    list.add(createDefaultPreset(i))
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
        return list.find { it.id == slotId } ?: createDefaultPreset(slotId)
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

    /** 清空指定仓位，恢复默认值（A键与准星物理居中于屏幕中心） */
    fun resetPreset(slotId: Int) {
        val list = getAllPresets()
        val index = list.indexOfFirst { it.id == slotId }
        val fresh = createDefaultPreset(slotId)
        if (index != -1) list[index] = fresh else list.add(fresh)
        saveAllPresets(list)
    }

    private fun createDefaultPresets(): MutableList<MacroPreset> {
        val list = mutableListOf<MacroPreset>()
        for (i in 1..MAX_SLOTS) {
            list.add(createDefaultPreset(i))
        }
        return list
    }
}

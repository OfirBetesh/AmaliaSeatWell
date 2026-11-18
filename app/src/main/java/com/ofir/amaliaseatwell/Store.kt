package com.ofir.amaliaseatwell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

fun hex(address: Int, command: Int): String = String.format("%02X:%02X", address, command)

data class FoundCode(val address: Int, val command: Int, var name: String) {
    val hex: String get() = hex(address, command)
}

/** Everything the app remembers: scan progress, found codes and programs. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("amalia", Context.MODE_PRIVATE)

    var scanIndex: Int
        get() = p.getInt("scanIndex", 0)
        set(v) { p.edit().putInt("scanIndex", v).apply() }

    var lockedAddress: Int
        get() = p.getInt("lockedAddress", -1)
        set(v) { p.edit().putInt("lockedAddress", v).apply() }

    var lockedIndex: Int
        get() = p.getInt("lockedIndex", 0)
        set(v) { p.edit().putInt("lockedIndex", v).apply() }

    var micEnabled: Boolean
        get() = p.getBoolean("mic", true)
        set(v) { p.edit().putBoolean("mic", v).apply() }

    var motionEnabled: Boolean
        get() = p.getBoolean("motion", false)
        set(v) { p.edit().putBoolean("motion", v).apply() }

    /** Reaction threshold: how many times louder than background counts as a reaction. */
    var sensitivity: Int
        get() = p.getInt("sensitivity", 4)
        set(v) { p.edit().putInt("sensitivity", v).apply() }

    fun resetScan() {
        p.edit().putInt("scanIndex", 0).putInt("lockedAddress", -1).putInt("lockedIndex", 0).apply()
    }

    // ---- found codes ----

    @Synchronized
    fun codes(): MutableList<FoundCode> {
        val arr = JSONArray(p.getString("codes", "[]"))
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            FoundCode(o.getInt("a"), o.getInt("c"), o.getString("n"))
        }
    }

    @Synchronized
    private fun saveCodes(list: List<FoundCode>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("a", it.address).put("c", it.command).put("n", it.name)) }
        p.edit().putString("codes", arr.toString()).apply()
    }

    fun hasCode(address: Int, command: Int) = codes().any { it.address == address && it.command == command }

    @Synchronized
    fun addCode(address: Int, command: Int, name: String?): FoundCode? {
        val list = codes()
        if (list.any { it.address == address && it.command == command }) return null
        val code = FoundCode(address, command, name ?: "כפתור ${list.size + 1}")
        list.add(code)
        saveCodes(list)
        return code
    }

    @Synchronized
    fun renameCode(address: Int, command: Int, name: String) {
        val list = codes()
        list.firstOrNull { it.address == address && it.command == command }?.name = name
        saveCodes(list)
    }

    @Synchronized
    fun removeCode(address: Int, command: Int) {
        saveCodes(codes().filterNot { it.address == address && it.command == command })
    }

    fun findByName(name: String): FoundCode? =
        codes().firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

    // ---- programs ----

    @Synchronized
    fun programs(): LinkedHashMap<String, String> {
        val o = JSONObject(p.getString("programs", "{}"))
        val map = LinkedHashMap<String, String>()
        o.keys().forEach { k -> map[k] = o.getString(k) }
        return map
    }

    @Synchronized
    fun savePrograms(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        p.edit().putString("programs", o.toString()).apply()
    }
}

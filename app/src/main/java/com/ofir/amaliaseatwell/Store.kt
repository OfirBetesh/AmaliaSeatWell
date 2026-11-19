package com.ofir.amaliaseatwell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val UNTESTED = 0
const val WORKS = 1
const val DEAD = 2

fun hex(address: Int, command: Int): String = String.format("%02X:%02X", address, command)
fun pack(address: Int, command: Int): Int = (address shl 8) or command
fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

/** One button of a remote. [aliases] holds other codes that were merged into it (packed). */
class Btn(
    var id: String = newId(),
    var address: Int,
    var command: Int,
    var name: String,
    var emoji: String = "",
    var color: Int = 0,
    var wide: Boolean = false,
    var hidden: Boolean = false,
    var status: Int = UNTESTED,
    val aliases: MutableList<Int> = ArrayList()
) {
    val hex: String get() = hex(address, command)

    fun matches(a: Int, c: Int) = (address == a && command == c) || aliases.contains(pack(a, c))

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("a", address).put("c", command).put("n", name).put("e", emoji)
        .put("col", color).put("w", wide).put("h", hidden).put("s", status)
        .put("al", JSONArray(aliases))

    companion object {
        fun fromJson(o: JSONObject, keepId: Boolean = true): Btn {
            val al = o.optJSONArray("al")
            val aliases = ArrayList<Int>()
            if (al != null) for (i in 0 until al.length()) aliases.add(al.getInt(i))
            return Btn(
                id = if (keepId) o.optString("id", newId()) else newId(),
                address = o.getInt("a"), command = o.getInt("c"),
                name = o.optString("n", "?"), emoji = o.optString("e", ""),
                color = o.optInt("col", 0), wide = o.optBoolean("w", false),
                hidden = o.optBoolean("h", false), status = o.optInt("s", UNTESTED),
                aliases = aliases
            )
        }
    }
}

/** A remote = one device: its buttons (in display order) and its scan progress. */
class Remote(
    val id: String = newId(),
    var name: String,
    val buttons: MutableList<Btn> = ArrayList(),
    var scanIndex: Int = 0,
    var lockedAddress: Int = -1,
    var lockedIndex: Int = 0
) {
    fun find(a: Int, c: Int): Btn? = buttons.firstOrNull { it.matches(a, c) }
    val hasProgress: Boolean get() = scanIndex > 0 || lockedAddress >= 0

    fun toJson(): JSONObject {
        val arr = JSONArray()
        buttons.forEach { arr.put(it.toJson()) }
        return JSONObject().put("id", id).put("name", name).put("buttons", arr)
            .put("scanIndex", scanIndex).put("lockedAddress", lockedAddress).put("lockedIndex", lockedIndex)
    }

    /** Shareable export: layout + codes, no scan progress. */
    fun toExport(): String {
        val arr = JSONArray()
        buttons.forEach { arr.put(it.toJson().apply { remove("id") }) }
        return JSONObject().put("type", "irlab-remote").put("v", 2).put("name", name)
            .put("address", lockedAddress).put("buttons", arr).toString(2)
    }

    fun toReadable(): String {
        val sb = StringBuilder("שלט: $name\n")
        if (lockedAddress >= 0) sb.append("כתובת NEC: ${String.format("%02X", lockedAddress)}\n")
        sb.append("\n")
        buttons.forEachIndexed { i, b ->
            val st = when (b.status) { WORKS -> "✓"; DEAD -> "✗"; else -> "?" }
            sb.append("${i + 1}. $st ${b.emoji} ${b.name} — ${b.hex}")
            if (b.aliases.isNotEmpty()) sb.append("  (גם: ${b.aliases.joinToString { hex(it shr 8, it and 0xFF) }})")
            if (b.hidden) sb.append("  [מוסתר]")
            sb.append("\n")
        }
        return sb.toString()
    }

    companion object {
        fun fromJson(o: JSONObject): Remote {
            val r = Remote(
                id = o.optString("id", newId()), name = o.optString("name", "שלט"),
                scanIndex = o.optInt("scanIndex", 0), lockedAddress = o.optInt("lockedAddress", -1),
                lockedIndex = o.optInt("lockedIndex", 0)
            )
            val arr = o.optJSONArray("buttons") ?: JSONArray()
            for (i in 0 until arr.length()) r.buttons.add(Btn.fromJson(arr.getJSONObject(i)))
            return r
        }

        /** Parses a shared export into a brand-new remote (new ids). */
        fun fromExport(text: String): Remote {
            val o = JSONObject(text.trim())
            val r = Remote(name = o.optString("name", "שלט מיובא"))
            val addr = o.optInt("address", -1)
            if (addr >= 0) { r.lockedAddress = addr; r.lockedIndex = 256 }
            val arr = o.getJSONArray("buttons")
            for (i in 0 until arr.length()) r.buttons.add(Btn.fromJson(arr.getJSONObject(i), keepId = false))
            return r
        }
    }
}

/** What the beep of this device sounds like (learned during calibration). */
class Calibration(val bin: Int, val threshold: Double, val noise: Double, val beep: Double) {
    val hz: Int get() = bin * 16000 / 512
    fun toJson(): String = JSONObject().put("bin", bin).put("thr", threshold)
        .put("noise", noise).put("beep", beep).toString()

    companion object {
        fun parse(s: String?): Calibration? {
            if (s == null) return null
            return try {
                val o = JSONObject(s)
                Calibration(o.getInt("bin"), o.getDouble("thr"), o.getDouble("noise"), o.getDouble("beep"))
            } catch (e: Exception) { null }
        }
    }
}

class Store(context: Context) {
    private val file = File(context.filesDir, "remotes.json")
    private val p = context.getSharedPreferences("amalia", Context.MODE_PRIVATE)
    private val list = ArrayList<Remote>()

    init {
        load()
        migrateV1()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) list.add(Remote.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) { }
    }

    /** Version 1 kept one flat list of codes; move it into a remote called "נדנדה". */
    private fun migrateV1() {
        if (p.getBoolean("migrated", false)) return
        try {
            val arr = JSONArray(p.getString("codes", "[]"))
            val scanIndex = p.getInt("scanIndex", 0)
            val locked = p.getInt("lockedAddress", -1)
            if (arr.length() > 0 || scanIndex > 0 || locked >= 0) {
                val r = Remote(name = "נדנדה", scanIndex = scanIndex, lockedAddress = locked,
                    lockedIndex = p.getInt("lockedIndex", 0))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    r.buttons.add(Btn(address = o.getInt("a"), command = o.getInt("c"), name = o.getString("n")))
                }
                list.add(r)
                save()
            }
        } catch (e: Exception) { }
        p.edit().putBoolean("migrated", true).apply()
    }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) { file.writeText(arr.toString()); tmp.delete() }
    }

    /** Run a change under the lock and save. */
    @Synchronized
    fun update(block: () -> Unit) { block(); save() }

    @Synchronized fun remotes(): List<Remote> = ArrayList(list)
    @Synchronized fun remote(id: String?): Remote? = list.firstOrNull { it.id == id }

    @Synchronized
    fun addRemote(name: String): Remote = Remote(name = name).also { list.add(it); save() }

    @Synchronized
    fun addRemote(r: Remote) { list.add(r); save() }

    @Synchronized
    fun deleteRemote(id: String) { list.removeAll { it.id == id }; save() }

    @Synchronized
    fun addButton(r: Remote, a: Int, c: Int): Btn? {
        if (r.find(a, c) != null) return null
        val b = Btn(address = a, command = c, name = "כפתור ${r.buttons.size + 1}")
        r.buttons.add(b)
        save()
        return b
    }

    /** "button" or "remote/button" → code. */
    @Synchronized
    fun findCode(spec: String): Pair<Int, Int>? {
        val s = spec.trim()
        val slash = s.indexOf('/')
        val candidates = if (slash > 0) {
            val rn = s.substring(0, slash).trim()
            val bn = s.substring(slash + 1).trim()
            list.filter { it.name.trim().equals(rn, true) }
                .flatMap { r -> r.buttons.filter { it.name.trim().equals(bn, true) } }
        } else {
            list.flatMap { r -> r.buttons.filter { it.name.trim().equals(s, true) } }
        }
        val b = candidates.firstOrNull()
            ?: list.flatMap { r -> r.buttons.filter { it.name.trim().equals(s, true) } }.firstOrNull()
            ?: return null
        return b.address to b.command
    }

    // ---- settings ----

    var micEnabled: Boolean
        get() = p.getBoolean("mic", true)
        set(v) { p.edit().putBoolean("mic", v).apply() }

    var motionEnabled: Boolean
        get() = p.getBoolean("motion", false)
        set(v) { p.edit().putBoolean("motion", v).apply() }

    /** 1 … 9, higher = more sensitive. */
    var sensitivity: Int
        get() = p.getInt("sens2", 5)
        set(v) { p.edit().putInt("sens2", v.coerceIn(1, 9)).apply() }

    var calibration: Calibration?
        get() = Calibration.parse(p.getString("calibration", null))
        set(v) { p.edit().putString("calibration", v?.toJson()).apply() }

    // ---- programs ----

    @Synchronized
    fun programs(): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        try {
            val o = JSONObject(p.getString("programs", "{}"))
            o.keys().forEach { k -> map[k] = o.getString(k) }
        } catch (e: Exception) { }
        return map
    }

    @Synchronized
    fun savePrograms(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        p.edit().putString("programs", o.toString()).apply()
    }
}

package com.ofir.amaliaseatwell

import java.util.Calendar

/**
 * Runs small text programs, e.g.
 *
 *   שלח הדלקה
 *   חכה 3
 *   חזור 2
 *     שלח מהירות+
 *     חכה 10 דקות
 *   סוף
 *
 * Commands: send/שלח <button | remote/button | AA:CC>, wait/חכה <time>, repeat/חזור <n> … end/סוף
 */
class ProgramRunner(
    private val store: Store,
    private val ir: IrSender
) {
    @Volatile var statusListener: ((String) -> Unit)? = null
    @Volatile var lastStatus: String = "אין תוכנית פעילה."
        private set

    private fun status(s: String) {
        lastStatus = s
        statusListener?.invoke(s)
    }

    sealed class Step {
        class Send(val address: Int, val command: Int, val label: String) : Step()
        class Wait(val ms: Long) : Step()
        class Repeat(val times: Int, val body: List<Step>) : Step()
    }

    private class Frame(val times: Int, val parent: ArrayList<Step>)

    @Volatile private var thread: Thread? = null
    val isRunning: Boolean get() = thread?.isAlive == true

    fun parse(text: String): List<Step> {
        val root = ArrayList<Step>()
        val stack = ArrayList<Frame>()
        var cur = root
        text.lines().forEachIndexed { idx, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(Regex("\\s+"), limit = 2)
            val cmd = parts[0].lowercase()
            val arg = parts.getOrNull(1)?.trim() ?: ""
            fun err(msg: String): Nothing = throw IllegalArgumentException("שורה ${idx + 1}: $msg")
            when (cmd) {
                "send", "שלח" -> {
                    val code = resolve(arg) ?: err("לא מכיר כפתור בשם \"$arg\"")
                    cur.add(Step.Send(code.first, code.second, arg))
                }
                "wait", "חכה" -> cur.add(Step.Wait(parseDuration(arg) ?: err("זמן לא תקין: \"$arg\"")))
                "repeat", "חזור" -> {
                    val n = arg.toIntOrNull()?.takeIf { it > 0 } ?: err("מספר חזרות לא תקין: \"$arg\"")
                    stack.add(Frame(n, cur))
                    cur = ArrayList()
                }
                "end", "סוף" -> {
                    if (stack.isEmpty()) err("'סוף' בלי 'חזור'")
                    val f = stack.removeAt(stack.size - 1)
                    f.parent.add(Step.Repeat(f.times, cur))
                    cur = f.parent
                }
                else -> err("פקודה לא מוכרת: \"${parts[0]}\"")
            }
        }
        if (stack.isNotEmpty()) throw IllegalArgumentException("חסרה שורת 'סוף' ל-'חזור'")
        return root
    }

    private fun resolve(arg: String): Pair<Int, Int>? {
        val m = Regex("^([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2})$").find(arg)
        if (m != null) return m.groupValues[1].toInt(16) to m.groupValues[2].toInt(16)
        return store.findCode(arg)
    }

    private fun parseDuration(arg: String): Long? {
        val m = Regex("^(\\d+(?:\\.\\d+)?)\\s*([a-zא-ת']*)$").find(arg.lowercase()) ?: return null
        val n = m.groupValues[1].toDouble()
        val mult = when (m.groupValues[2]) {
            "", "s", "sec", "שניות", "שניה", "שנייה", "שנ'" -> 1000.0
            "m", "min", "דקות", "דקה", "דק'" -> 60_000.0
            "h", "שעה", "שעות" -> 3_600_000.0
            else -> return null
        }
        return (n * mult).toLong()
    }

    /** @param dailyAt if set (hour, minute), the program repeats every day at that time. */
    fun start(name: String, steps: List<Step>, firstDelayMs: Long, dailyAt: IntArray?) {
        stop()
        thread = Thread({
            try {
                var delay = firstDelayMs
                while (true) {
                    countdown(name, delay)
                    status("▶ מריץ: $name")
                    exec(steps)
                    if (dailyAt == null) {
                        status("✓ \"$name\" הסתיימה")
                        break
                    }
                    delay = msUntil(dailyAt[0], dailyAt[1])
                }
            } catch (e: InterruptedException) {
                status("⏹ נעצר.")
            }
        }, "program").also { it.start() }
    }

    fun stop() {
        thread?.interrupt()
        thread?.join(1000)
        thread = null
    }

    private fun countdown(name: String, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return
            status("⏳ \"$name\" תתחיל בעוד ${fmt(left)}")
            Thread.sleep(minOf(left, 1000L))
        }
    }

    private fun exec(steps: List<Step>) {
        for (s in steps) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            when (s) {
                is Step.Send -> {
                    status("📡 שולח: ${s.label}")
                    ir.send(s.address, s.command)
                    Thread.sleep(300)
                }
                is Step.Wait -> {
                    val end = System.currentTimeMillis() + s.ms
                    while (true) {
                        val left = end - System.currentTimeMillis()
                        if (left <= 0) break
                        status("⏱ ממתין ${fmt(left)}")
                        Thread.sleep(minOf(left, 1000L))
                    }
                }
                is Step.Repeat -> repeat(s.times) { exec(s.body) }
            }
        }
    }

    companion object {
        fun msUntil(hour: Int, minute: Int): Long {
            val now = Calendar.getInstance()
            val t = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (!t.after(now)) t.add(Calendar.DAY_OF_MONTH, 1)
            return t.timeInMillis - now.timeInMillis
        }

        fun fmt(ms: Long): String {
            val s = (ms + 999) / 1000
            return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
            else String.format("%d:%02d", s / 60, s % 60)
        }
    }
}

package com.ofir.amaliaseatwell

import android.os.Handler
import android.os.Looper

/**
 * Finds a device's IR codes without its remote, and verifies codes already found.
 *
 * SCAN  stage 1: likely NEC codes (common commands × every address, then all the rest)
 *                until the device reacts → the device address is known.
 *       stage 2: all 256 commands of that address.
 *       Every suspected reaction is confirmed with control windows (nothing sent) —
 *       a code counts only if the device reacts when it's sent and stays quiet when not.
 * VERIFY: re-tests every button of a remote and marks it works / didn't react.
 */
class Scanner(private val store: Store, private val ir: IrSender, private val det: ReactionDetector) {

    interface Listener {
        fun onStatus(text: String)
        fun onProgress(text: String)
        fun onFound(b: Btn)
        fun onFinished(text: String)
    }

    enum class Job { SCAN, VERIFY }

    @Volatile var listener: Listener? = null
    @Volatile var lastStatus = "מוכן."
        private set
    @Volatile var lastProgress = ""
        private set
    @Volatile var remoteId: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    val isRunning: Boolean get() = running

    private fun status(s: String) { lastStatus = s; main.post { listener?.onStatus(s) } }
    private fun progress(s: String) { lastProgress = s; main.post { listener?.onProgress(s) } }
    private fun found(b: Btn) { main.post { listener?.onFound(b) } }
    private fun finished(s: String) { lastStatus = s; main.post { listener?.onFinished(s) } }

    fun start(r: Remote, job: Job) {
        if (running) return
        running = true
        remoteId = r.id
        thread = Thread({
            try {
                if (job == Job.SCAN) runScan(r) else runVerify(r)
            } catch (e: InterruptedException) {
                finished("⏸ נעצר. אפשר להמשיך מאותה נקודה.")
            } catch (e: Exception) {
                finished("שגיאה: ${e.message}")
            } finally {
                running = false
                store.save()
            }
        }, "scanner").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1500)
        thread = null
    }

    private fun now() = System.currentTimeMillis()
    private fun fastWindow(): Long = if (det.useMotion) 1300L else 450L

    // ------------------------------------------------------------------ scan

    private fun runScan(r: Remote) {
        status("מתכונן… שקט בבקשה 🤫")
        Thread.sleep(2500)

        if (r.lockedAddress < 0) {
            status("🔍 שלב 1: מחפש את הכתובת של המכשיר…")
            var i = r.scanIndex
            while (running && i < TOTAL) {
                val (a, c) = codeAt(i)
                if (i % 2 == 0) progress(describe(r))
                val t = now()
                ir.send(a, c)
                if (det.reacted(t, fastWindow(), false)) {
                    val hit = locate(r, i) { k -> codeAt(k) }
                    if (hit != null) {
                        val (ha, hc) = codeAt(hit)
                        store.addButton(r, ha, hc)?.let { it.status = WORKS; found(it) }
                        store.update {
                            r.scanIndex = hit + 1
                            r.lockedIndex = 0
                            r.lockedAddress = ha
                        }
                        break
                    }
                    status("🔍 לא אומת (כנראה רעש) — ממשיך לחפש…")
                }
                i++
                r.scanIndex = i
                if (i % 25 == 0) store.save()
            }
            store.save()
            if (r.lockedAddress < 0) {
                progress(describe(r))
                finished(
                    if (running) "הסריקה הסתיימה בלי תגובה. כיילו שוב, קרבו את הטלפון ונסו 'סריקה חוזרת'."
                    else "⏸ נעצר. אפשר להמשיך מאותה נקודה."
                )
                return
            }
        }

        val addr = r.lockedAddress
        status("🎯 כתובת המכשיר: ${String.format("%02X", addr)}. מחפש את כל הכפתורים…")
        Thread.sleep(1500)
        var c = r.lockedIndex
        while (running && c < 256) {
            if (r.find(addr, c) == null) {
                progress(describe(r))
                val t = now()
                ir.send(addr, c)
                if (det.reacted(t, fastWindow() + 150, false)) {
                    val hit = locate(r, c) { k -> addr to k }
                    if (hit != null) store.addButton(r, addr, hit)?.let { it.status = WORKS; found(it) }
                    status("🎯 ממשיך לחפש כפתורים נוספים…")
                }
            }
            c++
            r.lockedIndex = c
            if (c % 16 == 0) store.save()
        }
        store.save()
        progress(describe(r))
        finished(
            if (c >= 256) "✅ סיום! יש לשלט ${r.buttons.size} כפתורים. מומלץ עכשיו: 🧪 בדיקה מודרכת בשלט."
            else "⏸ נעצר. אפשר להמשיך מאותה נקודה."
        )
    }

    /** The reaction may lag the code that caused it: re-test the last few slowly, then confirm. */
    private fun locate(r: Remote, i: Int, codeOf: (Int) -> Pair<Int, Int>): Int? {
        val manual = det.consumeManual()
        val back = if (manual) LOOKBACK_MANUAL else LOOKBACK
        status("👂 זוהתה תגובה! בודק איזה קוד גרם לה…")
        Thread.sleep(2500)
        for (k in i downTo maxOf(0, i - back)) {
            if (!running) return null
            val (a, c) = codeOf(k)
            if (r.find(a, c) != null) continue
            status("בודק את ${hex(a, c)}…")
            val t = now()
            ir.send(a, c)
            if (det.reacted(t, SLOW_WINDOW, true)) {
                status("${hex(a, c)} הגיב — מאמת מול בדיקות ביקורת…")
                if (confirm(a, c)) {
                    det.consumeManual()
                    return k
                }
                status("${hex(a, c)} לא עבר אימות")
            }
            Thread.sleep(1200)
        }
        det.consumeManual()
        return null
    }

    /**
     * Send / pause / send / pause / send. Real code: reacts on ≥2 of 3 sends and never in the
     * pauses. A reaction during a pause means the room is noisy → try once more.
     */
    private fun confirm(a: Int, c: Int): Boolean {
        repeat(2) {
            var hits = 0
            var falseHits = 0
            for (send in booleanArrayOf(true, false, true, false, true)) {
                if (!running) return false
                Thread.sleep(1800)
                val t = now()
                if (send) ir.send(a, c)
                val reacted = det.reacted(t, CONFIRM_WINDOW, true)
                if (send && reacted) hits++
                if (!send && reacted) falseHits++
            }
            if (falseHits == 0) return hits >= 2
            status("רעש ברקע הפריע לבדיקה — מנסה שוב…")
        }
        return false
    }

    // ------------------------------------------------------------------ verify

    private fun runVerify(r: Remote) {
        status("🧪 בודק את כל הכפתורים… שקט בבקשה, והמכשיר דלוק")
        Thread.sleep(2500)
        val list = r.buttons.filter { !it.hidden }
        var ok = 0
        var done = 0
        for ((idx, b) in list.withIndex()) {
            if (!running) break
            progress("בודק ${idx + 1}/${list.size}: ${b.emoji} ${b.name} (${b.hex})")
            val works = confirm(b.address, b.command)
            if (!running) break
            store.update { b.status = if (works) WORKS else DEAD }
            if (works) ok++
            done++
            found(b)
        }
        finished(
            if (done == list.size) "✅ הבדיקה הסתיימה: $ok עובדים, ${done - ok} לא הגיבו (סומנו ⚠️ בשלט)."
            else "⏸ הבדיקה נעצרה ($done/${list.size})."
        )
    }

    // ------------------------------------------------------------------ progress text

    fun describe(r: Remote): String {
        val n = r.buttons.size
        val per = (fastWindow() + 90) / 1000.0
        return if (r.lockedAddress >= 0) {
            val left = ((256 - r.lockedIndex).coerceAtLeast(0) * per / 60).toInt()
            "שלב 2 · כתובת ${String.format("%02X", r.lockedAddress)} · פקודה ${r.lockedIndex.coerceAtMost(256)}/256 · " +
                "נמצאו $n · נותרו כ-$left דק'"
        } else {
            val i = r.scanIndex
            val phase = if (i < PHASE1) "שלב 1א · קודים נפוצים" else "שלב 1ב · כל הקודים"
            val stageEnd = if (i < PHASE1) PHASE1 else TOTAL
            val left = ((stageEnd - i) * per / 60).toInt()
            val (a, c) = codeAt(minOf(i, TOTAL - 1))
            "$phase · ${hex(a, c)} · ${String.format("%.1f", i * 100.0 / TOTAL)}% · עד סוף השלב: עד כ-$left דק'"
        }
    }

    companion object {
        val COMMON = intArrayOf(
            0x45, 0x46, 0x47, 0x44, 0x40, 0x43, 0x07, 0x15, 0x09, 0x16, 0x19,
            0x0D, 0x0C, 0x18, 0x5E, 0x08, 0x1C, 0x5A, 0x42, 0x52, 0x4A,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x0A, 0x0B, 0x12, 0x1A, 0x1E, 0x1F
        )
        private val ADDR_PRIORITY = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x7F, 0xFF, 0xBF, 0xEF, 0xFE
        )
        val ADDR_ORDER: IntArray =
            (ADDR_PRIORITY.toList() + (0..255).filter { a -> ADDR_PRIORITY.none { it == a } }).toIntArray()
        private val REST: IntArray = (0..255).filter { c -> COMMON.none { it == c } }.toIntArray()

        val PHASE1 = 256 * COMMON.size
        const val TOTAL = 256 * 256
        const val SLOW_WINDOW = 2500L
        const val CONFIRM_WINDOW = 1600L
        const val LOOKBACK = 4
        const val LOOKBACK_MANUAL = 8

        fun codeAt(i: Int): Pair<Int, Int> =
            if (i < PHASE1) ADDR_ORDER[i / COMMON.size] to COMMON[i % COMMON.size]
            else {
                val j = i - PHASE1
                ADDR_ORDER[j / REST.size] to REST[j % REST.size]
            }
    }
}

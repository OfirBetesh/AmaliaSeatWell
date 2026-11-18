package com.ofir.amaliaseatwell

/**
 * Finds the swing's IR codes without the original remote.
 *
 * Stage 1: send likely NEC codes (common cheap-remote commands × every address, then every
 *          remaining code) until the swing reacts. Each reaction is re-tested slowly
 *          and confirmed twice so a random noise doesn't count.
 * Stage 2: once one code works, the address is known — try all 256 commands on it
 *          to discover every other button.
 * Progress is saved after every code, so a stopped scan resumes where it left off.
 */
class Scanner(
    private val store: Store,
    private val ir: IrSender,
    private val det: ReactionDetector,
    private val ui: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onProgress(text: String)
        fun onFound(code: FoundCode)
        fun onFinished(text: String)
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                run()
            } catch (e: InterruptedException) {
                ui.onFinished("נעצר. אפשר להמשיך מאותה נקודה.")
            } catch (e: Exception) {
                ui.onFinished("שגיאה: ${e.message}")
            } finally {
                running = false
            }
        }, "scanner").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1500)
        thread = null
    }

    private fun fastWindow(): Long = if (det.useMotion) 1300L else 450L

    private fun run() {
        ui.onStatus("מכייל רעשי רקע… שקט בבקשה 🤫")
        Thread.sleep(2500)

        if (store.lockedAddress < 0) {
            ui.onStatus("סורק… מחכה שהנדנדה תגיב")
            var i = store.scanIndex
            while (running && i < TOTAL) {
                val (a, c) = codeAt(i)
                ui.onProgress(describeProgress())
                val t = System.currentTimeMillis()
                ir.send(a, c)
                if (det.reacted(t, fastWindow(), false)) {
                    val hit = locate(i) { k -> codeAt(k) }
                    if (hit != null) {
                        val (ha, hc) = codeAt(hit)
                        found(ha, hc)
                        store.scanIndex = hit + 1
                        store.lockedIndex = 0
                        store.lockedAddress = ha
                        break
                    }
                    ui.onStatus("לא אומת (כנראה רעש) — ממשיך לסרוק")
                }
                i++
                store.scanIndex = i
            }
            if (store.lockedAddress < 0) {
                ui.onProgress(describeProgress())
                ui.onFinished(
                    if (running) "הסריקה הסתיימה בלי תגובה. נסו רגישות גבוהה יותר, לקרב את הטלפון, ולאפס ולהתחיל מחדש."
                    else "נעצר. אפשר להמשיך מאותה נקודה."
                )
                return
            }
        }

        val addr = store.lockedAddress
        ui.onStatus("🎯 נמצאה כתובת הנדנדה (${String.format("%02X", addr)})! מחפש את כל שאר הכפתורים…")
        Thread.sleep(2000)
        var c = store.lockedIndex
        while (running && c < 256) {
            if (!store.hasCode(addr, c)) {
                ui.onProgress(describeProgress())
                val t = System.currentTimeMillis()
                ir.send(addr, c)
                if (det.reacted(t, fastWindow() + 150, false)) {
                    val hit = locate(c) { k -> addr to k }
                    if (hit != null) found(addr, hit)
                    ui.onStatus("ממשיך לחפש כפתורים נוספים…")
                }
            }
            c++
            store.lockedIndex = c
        }
        ui.onProgress(describeProgress())
        ui.onFinished(
            if (c >= 256) "✅ סיום! נמצאו ${store.codes().size} כפתורים. עברו ללשונית 'שלט' ותנו להם שמות."
            else "נעצר. אפשר להמשיך מאותה נקודה."
        )
    }

    /** The reaction may lag behind the code that caused it: re-test the last few codes slowly. */
    private fun locate(i: Int, codeOf: (Int) -> Pair<Int, Int>): Int? {
        val manual = det.consumeManual()
        val back = if (manual) LOOKBACK_MANUAL else LOOKBACK
        ui.onStatus("👂 זוהתה תגובה! בודק לאט איזה קוד גרם לה…")
        Thread.sleep(3000)
        for (k in i downTo maxOf(0, i - back)) {
            if (!running) return null
            val (a, c) = codeOf(k)
            if (store.hasCode(a, c)) continue
            ui.onStatus("בודק את הקוד ${hex(a, c)}…")
            val t = System.currentTimeMillis()
            ir.send(a, c)
            if (det.reacted(t, SLOW_WINDOW, true)) {
                ui.onStatus("הקוד ${hex(a, c)} הגיב — מאמת פעם נוספת…")
                Thread.sleep(3000)
                val t2 = System.currentTimeMillis()
                ir.send(a, c)
                if (det.reacted(t2, SLOW_WINDOW, true)) {
                    det.consumeManual()
                    return k
                }
            }
            Thread.sleep(1500)
        }
        det.consumeManual()
        return null
    }

    private fun found(a: Int, c: Int) {
        store.addCode(a, c, null)?.let { ui.onFound(it) }
    }

    fun describeProgress(): String {
        val n = store.codes().size
        val per = (fastWindow() + 90) / 1000.0
        return if (store.lockedAddress >= 0) {
            val left = ((256 - store.lockedIndex) * per / 60).toInt()
            "שלב 2: כל הפקודות של הכתובת ${String.format("%02X", store.lockedAddress)} · " +
                "${store.lockedIndex}/256 · נמצאו $n כפתורים · נותרו כ-$left דק'"
        } else {
            val i = store.scanIndex
            val phase = if (i < PHASE1) "שלב 1א: קודים נפוצים" else "שלב 1ב: כל הקודים"
            val stageEnd = if (i < PHASE1) PHASE1 else TOTAL
            val left = ((stageEnd - i) * per / 60).toInt()
            val (a, c) = codeAt(minOf(i, TOTAL - 1))
            "$phase · קוד ${hex(a, c)} · $i/$TOTAL (${String.format("%.1f", i * 100.0 / TOTAL)}%) · " +
                "עד סוף השלב: עד כ-$left דק'"
        }
    }

    companion object {
        /** Command bytes that show up again and again on generic Chinese NEC remotes. */
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

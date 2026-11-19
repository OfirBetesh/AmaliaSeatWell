package com.ofir.amaliaseatwell

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Calibrate → scan → verify, for one remote. */
class ScanActivity : Activity(), Scanner.Listener {

    private lateinit var remote: Remote
    private lateinit var calStatus: TextView
    private lateinit var meter: TextView
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var found: TextView
    private lateinit var controls: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var calibrating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        val r = Core.store.remote(intent.getStringExtra(EXTRA_REMOTE))
        if (r == null) { toast("השלט לא נמצא"); finish(); return }
        remote = r
        title = "סריקה · ${r.name}"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Core.detector.start()

        val p = vbox()
        p.addView(label(
            "📍 כוונו את הקצה העליון של הטלפון (שם משדר ה-IR) אל המכשיר, במרחק של עד 2 מטר.\n" +
                "🔌 חברו למטען, הדליקו את המכשיר ושמרו על שקט בחדר."
        ))

        p.addView(header("שלב א · כיול הצפצוף (מומלץ מאוד)"))
        p.addView(label(
            "האפליקציה לומדת את התדר של הצפצוף של המכשיר ומקשיבה רק לו. כך דיבור, טלוויזיה ורעשים " +
                "לא ייחשבו כתגובה. זה מונע כפתורים ריקים או כפולים.", 14f
        ))
        calStatus = label("", 15f, true)
        p.addView(calStatus)
        p.addView(hrow(
            btn("🎚 כייל עכשיו") { calibrate() },
            btn("✕ בטל כיול") { Core.detector.clearCalibration(); showCal() }
        ))
        showCal()

        p.addView(header("זיהוי תגובה"))
        p.addView(CheckBox(this).apply {
            text = "לפי צליל (מיקרופון)"
            isChecked = Core.store.micEnabled
            setOnCheckedChangeListener { _, b -> Core.store.micEnabled = b; Core.detector.useMic = b }
        })
        p.addView(CheckBox(this).apply {
            text = "לפי תנועה (הטלפון מונח על המכשיר ועדיין מכוון לחיישן)"
            isChecked = Core.store.motionEnabled
            setOnCheckedChangeListener { _, b -> Core.store.motionEnabled = b; Core.detector.useMotion = b }
        })
        val sens = label("", 14f)
        fun showSens() {
            sens.text = "רגישות: ${Core.store.sensitivity}/9  (גבוה = שומע גם צפצופים חלשים, אבל גם יותר רעש. ברירת מחדל: 5)"
        }
        showSens()
        p.addView(sens)
        p.addView(SeekBar(this).apply {
            max = 8
            progress = Core.store.sensitivity - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    Core.store.sensitivity = v + 1
                    showSens()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        meter = label("", 14f)
        p.addView(meter)

        p.addView(header("שלב ב · סריקה"))
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(controls)
        p.addView(btn("👋 המכשיר הגיב!") {
            Core.detector.markManual()
            toast("נרשם, בודק את הקודים האחרונים")
        }.apply {
            textSize = 20f
            minHeight = dp(64)
            background = rounded(0xFFFFCDD2.toInt(), dp(16).toFloat())
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        status = label("", 16f, true)
        progress = label("", 14f)
        found = label("", 14f)
        p.addView(status)
        p.addView(progress)
        p.addView(found)
        p.addView(btn("🎮 מעבר לשלט") { openRemote(remote) })
        setContentView(page(p))

        if (intent.getBooleanExtra(EXTRA_VERIFY, false) && savedInstanceState == null) {
            intent.removeExtra(EXTRA_VERIFY)
            start(Scanner.Job.VERIFY)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::remote.isInitialized) return
        Core.scanner.listener = this
        val mine = Core.scanner.remoteId == remote.id
        status.text = if (mine) Core.scanner.lastStatus else "מוכן."
        progress.text = if (mine && Core.scanner.lastProgress.isNotEmpty()) Core.scanner.lastProgress
            else if (remote.hasProgress) Core.scanner.describe(remote) else ""
        showFound()
        updateControls()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        if (Core.scanner.listener === this) Core.scanner.listener = null
        handler.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            meter.text = Core.detector.meterText()
            handler.postDelayed(this, 250)
        }
    }

    // ------------------------------------------------------------------ calibration

    private fun showCal() {
        val c = Core.store.calibration
        calStatus.text = if (c != null) "✅ מכויל: צפצוף ב-~${c.hz}Hz" else "⚪ לא מכויל (הזיהוי יהיה לפי עוצמה כללית)"
        calStatus.setTextColor(if (c != null) 0xFF2E7D32.toInt() else Color.DKGRAY)
    }

    private fun calibrate() {
        if (Core.scanner.isRunning) { toast("עצרו קודם את הסריקה"); return }
        if (calibrating) return
        calibrating = true
        Thread {
            val res = try {
                Core.detector.calibrate { s -> runOnUiThread { calStatus.text = s; calStatus.setTextColor(Color.BLUE) } }
            } catch (e: Exception) { "שגיאה: ${e.message}" }
            runOnUiThread {
                calibrating = false
                showCal()
                toast(res)
                status.text = res
            }
        }.start()
    }

    // ------------------------------------------------------------------ scan controls

    private fun updateControls() {
        controls.removeAllViews()
        val sc = Core.scanner
        if (sc.isRunning) {
            if (sc.remoteId == remote.id) {
                controls.addView(btn("⏸ עצור") { sc.stop(); updateControls() }.apply { textSize = 18f })
            } else {
                val other = Core.store.remote(sc.remoteId)?.name ?: "שלט אחר"
                controls.addView(label("כרגע רצה סריקה של \"$other\"."))
                controls.addView(btn("⏹ עצור אותה") { sc.stop(); updateControls() })
            }
            return
        }
        val r = remote
        if (!r.hasProgress) {
            controls.addView(btn("▶ התחל סריקה") { start(Scanner.Job.SCAN) }.apply { textSize = 18f })
        } else {
            controls.addView(btn("▶ המשך מאיפה שעצרתי") { start(Scanner.Job.SCAN) }.apply { textSize = 18f })
            if (r.lockedAddress >= 0) {
                controls.addView(btn("🔄 סרוק שוב את כל הפקודות (הכתובת ידועה, שומר כפתורים)") {
                    Core.store.update { r.lockedIndex = 0 }
                    start(Scanner.Job.SCAN)
                })
            }
            controls.addView(btn("🔄 חפש הכל מחדש, כולל כתובת (שומר כפתורים)") {
                Core.store.update { r.scanIndex = 0; r.lockedAddress = -1; r.lockedIndex = 0 }
                start(Scanner.Job.SCAN)
            })
            controls.addView(btn("🧹 התחל מאפס (מוחק את כל הכפתורים)") {
                confirm("למחוק את כל ${r.buttons.size} הכפתורים של \"${r.name}\" ולהתחיל סריקה חדשה?") {
                    Core.store.update {
                        r.buttons.clear()
                        r.scanIndex = 0; r.lockedAddress = -1; r.lockedIndex = 0
                    }
                    showFound()
                    start(Scanner.Job.SCAN)
                }
            })
        }
        if (r.buttons.isNotEmpty()) {
            controls.addView(btn("🤖 בדוק את כל ${r.buttons.size} הכפתורים שנמצאו") { start(Scanner.Job.VERIFY) })
        }
        controls.addView(btn("🔢 יודע את כתובת המכשיר? (מתקדם)") {
            ask("כתובת NEC (00–FF)", if (r.lockedAddress >= 0) String.format("%02X", r.lockedAddress) else "") { s ->
                val v = s.trim().toIntOrNull(16)
                if (v == null || v !in 0..255) toast("כתובת לא תקינה")
                else {
                    Core.store.update { r.lockedAddress = v; r.lockedIndex = 0 }
                    start(Scanner.Job.SCAN)
                }
            }
        })
    }

    private fun start(job: Scanner.Job) {
        if (!Core.ir.available) { toast("אין משדר IR במכשיר"); return }
        if (calibrating) { toast("חכו לסיום הכיול"); return }
        if (!Core.store.micEnabled && !Core.store.motionEnabled) toast("הזיהוי האוטומטי כבוי, תצטרכו ללחוץ 'המכשיר הגיב!'")
        Core.detector.start()
        Core.scanner.listener = this
        Core.scanner.start(remote, job)
        updateControls()
    }

    private fun showFound() {
        val bs = remote.buttons
        found.text = if (bs.isEmpty()) "" else "נמצאו ${bs.size}: " + bs.joinToString(" · ") {
            (if (it.status == DEAD) "⚠️" else if (it.status == WORKS) "✓" else "") + it.name
        }
    }

    // ------------------------------------------------------------------ Scanner.Listener

    override fun onStatus(text: String) { status.text = text }
    override fun onProgress(text: String) { progress.text = text }
    override fun onFound(b: Btn) {
        showFound()
        if (Core.scanner.remoteId == remote.id) toast("🎉 ${b.name}: ${b.hex}")
    }
    override fun onFinished(text: String) {
        status.text = text
        showFound()
        updateControls()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

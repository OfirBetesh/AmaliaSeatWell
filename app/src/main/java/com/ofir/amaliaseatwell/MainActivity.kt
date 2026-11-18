package com.ofir.amaliaseatwell

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), Scanner.Listener {

    private lateinit var store: Store
    private lateinit var ir: IrSender
    private lateinit var detector: ReactionDetector
    private lateinit var scanner: Scanner
    private lateinit var runner: ProgramRunner
    private val handler = Handler(Looper.getMainLooper())

    private val pages = ArrayList<View>()
    private val tabButtons = ArrayList<Button>()

    private lateinit var scanStatus: TextView
    private lateinit var scanProgress: TextView
    private lateinit var levelView: TextView
    private lateinit var scanButton: Button
    private lateinit var remoteList: LinearLayout
    private lateinit var progStatus: TextView
    private lateinit var progSpinner: Spinner
    private lateinit var progName: EditText
    private lateinit var progBody: EditText
    private var editingNew = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = Store(this)
        ir = IrSender(this)
        detector = ReactionDetector(this).apply {
            useMic = store.micEnabled
            useMotion = store.motionEnabled
            factor = store.sensitivity.toDouble()
        }
        scanner = Scanner(store, ir, detector, this)
        runner = ProgramRunner(store, ir) { s -> runOnUiThread { progStatus.text = s } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val frame = FrameLayout(this)
        listOf(
            "🔍 סריקה" to buildScanPage(),
            "🎮 שלט" to buildRemotePage(),
            "⏰ תוכניות" to buildProgramsPage()
        ).forEachIndexed { i, (title, page) ->
            val sv = ScrollView(this).apply { addView(page) }
            frame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
            pages.add(sv)
            val b = button(title) { showPage(i) }
            tabButtons.add(b)
            tabs.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        showPage(if (store.codes().isEmpty()) 0 else 1)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        detector.start()
        scanProgress.text = scanner.describeProgress()
        handler.post(levelTick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        detector.start()
    }

    override fun onDestroy() {
        handler.removeCallbacks(levelTick)
        scanner.stop()
        runner.stop()
        detector.stop()
        super.onDestroy()
    }

    private fun showPage(i: Int) {
        pages.forEachIndexed { j, v -> v.visibility = if (i == j) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { j, b -> b.setTypeface(null, if (i == j) Typeface.BOLD else Typeface.NORMAL) }
        if (i == 1) refreshRemote()
    }

    private val levelTick = object : Runnable {
        override fun run() {
            levelView.text = if (detector.micActive)
                "🎤 עוצמה ביחס לרקע: ${String.format("%.1f", detector.micRatio())}×   (סף תגובה: ${store.sensitivity}×)"
            else "🎤 המיקרופון לא פעיל — אשרו הרשאת מיקרופון בהגדרות האפליקציה"
            handler.postDelayed(this, 300)
        }
    }

    // ---------------- Scanner callbacks ----------------

    override fun onStatus(text: String) = runOnUiThread { scanStatus.text = text }
    override fun onProgress(text: String) = runOnUiThread { scanProgress.text = text }
    override fun onFound(code: FoundCode) = runOnUiThread {
        refreshRemote()
        toast("🎉 נמצא קוד ${code.hex}!")
    }
    override fun onFinished(text: String) = runOnUiThread {
        scanStatus.text = text
        scanButton.text = START_TEXT
        scanProgress.text = scanner.describeProgress()
    }

    // ---------------- Scan page ----------------

    private fun buildScanPage(): View {
        val p = vbox()
        p.addView(label("סריקת קודי השלט", 20f, true))
        p.addView(label(
            "1. הניחו את הטלפון כשהקצה העליון שלו (שם נמצא משדר ה-IR) מכוון לנדנדה, במרחק של עד 2 מטר.\n" +
            "2. חברו למטען — הסריקה יכולה לקחת זמן. המסך יישאר דולק.\n" +
            "3. הדליקו את הנדנדה בכפתור שעליה (כך היא תגיב גם לפקודות כמו מהירות ומוזיקה), ושמרו על שקט בחדר.\n" +
            "4. לחצו 'התחל סריקה' ותנו לה לעבוד. כשהנדנדה מצפצפת או משנה תנועה — האפליקציה מזהה לבד.\n" +
            "   ראיתם תגובה שלא זוהתה? לחצו 'הגיבה!'.\n" +
            "5. אם בדיקה של קוד בודד מגיבה — היא תיבדק שוב כדי לוודא. אחרי שנמצא קוד ראשון, כל שאר הכפתורים נמצאים תוך דקות."
        ))
        if (!ir.available) {
            p.addView(label("⚠️ לא נמצא משדר IR במכשיר הזה.", 16f, true).apply { setTextColor(Color.RED) })
        }
        p.addView(CheckBox(this).apply {
            text = "זיהוי לפי צליל (מיקרופון)"
            isChecked = store.micEnabled
            setOnCheckedChangeListener { _, b -> store.micEnabled = b; detector.useMic = b }
        })
        p.addView(CheckBox(this).apply {
            text = "זיהוי לפי תנועה (הטלפון מונח על מסגרת הנדנדה ועדיין מכוון לחיישן)"
            isChecked = store.motionEnabled
            setOnCheckedChangeListener { _, b -> store.motionEnabled = b; detector.useMotion = b }
        })
        val sens = label("")
        fun showSens() { sens.text = "רגישות: תגובה = פי ${store.sensitivity} מרעש הרקע (מספר נמוך = רגיש יותר)" }
        showSens()
        p.addView(sens)
        p.addView(SeekBar(this).apply {
            max = 8
            progress = (store.sensitivity - 2).coerceIn(0, 8)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    store.sensitivity = v + 2
                    detector.factor = (v + 2).toDouble()
                    showSens()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        levelView = label("")
        p.addView(levelView)

        scanButton = button(START_TEXT) { toggleScan() }.apply { textSize = 18f }
        p.addView(scanButton)
        p.addView(button("👋 הגיבה!") {
            detector.markManual()
            toast("נרשם — בודק את הקודים האחרונים")
        }.apply {
            textSize = 22f
            setBackgroundColor(Color.rgb(255, 205, 210))
            minHeight = dp(72)
        })

        scanStatus = label("מוכן.", 16f, true)
        scanProgress = label("")
        p.addView(scanStatus)
        p.addView(scanProgress)

        p.addView(button("🔁 סרוק שוב את כל הפקודות של הכתובת שנמצאה") {
            if (store.lockedAddress < 0) toast("עוד לא נמצאה כתובת")
            else {
                if (scanner.isRunning) scanner.stop()
                store.lockedIndex = 0
                toast("הדליקו את הנדנדה ידנית לפני הסריקה")
                toggleScan()
            }
        })
        p.addView(button("🗑 אפס סריקה (הקודים שנמצאו נשמרים)") {
            confirm("לאפס את התקדמות הסריקה ולהתחיל מההתחלה?") {
                scanner.stop()
                store.resetScan()
                scanButton.text = START_TEXT
                scanStatus.text = "אופס. מוכן."
                scanProgress.text = scanner.describeProgress()
            }
        })
        return p
    }

    private fun toggleScan() {
        if (scanner.isRunning) {
            scanner.stop()
            scanButton.text = START_TEXT
        } else {
            if (!ir.available) { toast("אין משדר IR במכשיר"); return }
            if (!store.micEnabled && !store.motionEnabled) toast("זיהוי אוטומטי כבוי — תצטרכו ללחוץ 'הגיבה!'")
            detector.start()
            scanner.start()
            scanButton.text = STOP_TEXT
        }
    }

    // ---------------- Remote page ----------------

    private fun buildRemotePage(): View {
        val p = vbox()
        p.addView(label("השלט של עמליה", 20f, true))
        p.addView(label("לחיצה על כפתור שולחת את הקוד. ✎ כדי לתת שם (למשל: הדלקה, מהירות+, מוזיקה) — השמות משמשים בתוכניות."))
        remoteList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(remoteList)
        p.addView(button("➕ הוסף קוד ידנית") { addManualCode() })
        refreshRemote()
        return p
    }

    private fun refreshRemote() {
        remoteList.removeAllViews()
        val codes = store.codes()
        if (codes.isEmpty()) {
            remoteList.addView(label("עוד לא נמצאו קודים — התחילו סריקה בלשונית 'סריקה'."))
            return
        }
        codes.forEach { c ->
            val send = button("${c.name}   (${c.hex})") {
                if (!ir.send(c.address, c.command)) toast("השליחה נכשלה")
            }.apply { textSize = 18f }
            val ren = button("✎") { rename(c) }
            val del = button("✕") {
                confirm("למחוק את \"${c.name}\"?") { store.removeCode(c.address, c.command); refreshRemote() }
            }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(send, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(ren, LinearLayout.LayoutParams(dp(56), WRAP))
            r.addView(del, LinearLayout.LayoutParams(dp(56), WRAP))
            remoteList.addView(r)
        }
    }

    private fun rename(c: FoundCode) {
        val input = EditText(this).apply { setText(c.name); setSingleLine(); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("שם לכפתור ${c.hex}")
            .setView(input)
            .setPositiveButton("שמור") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { store.renameCode(c.address, c.command, n); refreshRemote() }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun addManualCode() {
        val input = EditText(this).apply { hint = "כתובת:פקודה, למשל 00:45"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("הוספת קוד NEC")
            .setView(input)
            .setPositiveButton("הוסף") { _, _ ->
                val m = Regex("^\\s*([0-9A-Fa-f]{1,2})\\s*:\\s*([0-9A-Fa-f]{1,2})\\s*$").find(input.text.toString())
                if (m == null) toast("פורמט לא תקין")
                else {
                    store.addCode(m.groupValues[1].toInt(16), m.groupValues[2].toInt(16), null)
                    refreshRemote()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ---------------- Programs page ----------------

    private fun buildProgramsPage(): View {
        val p = vbox()
        p.addView(label("תוכניות ותזמונים", 20f, true))
        p.addView(label(HELP_TEXT))
        progSpinner = Spinner(this)
        p.addView(progSpinner)
        progName = EditText(this).apply { hint = "שם התוכנית"; setSingleLine() }
        progBody = EditText(this).apply {
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        p.addView(progName)
        p.addView(progBody)
        p.addView(row(
            button("💾 שמור") { saveProgram() },
            button("➕ חדשה") { newProgram() },
            button("🗑 מחק") { deleteProgram() }
        ))
        p.addView(button("▶ הפעל עכשיו") { startProgram(0L, null) }.apply { textSize = 18f })

        val minutes = EditText(this).apply { hint = "דקות"; inputType = InputType.TYPE_CLASS_NUMBER }
        p.addView(row(minutes, button("⏳ הפעל בעוד") {
            val m = minutes.text.toString().toIntOrNull()
            if (m == null) toast("כתבו מספר דקות") else startProgram(m * 60_000L, null)
        }))

        val time = EditText(this).apply { hint = "20:30"; inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME }
        val daily = CheckBox(this).apply { text = "כל יום" }
        p.addView(row(time, daily, button("⏰ הפעל בשעה") {
            val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$").find(time.text.toString())
            val h = m?.groupValues?.get(1)?.toIntOrNull()
            val mi = m?.groupValues?.get(2)?.toIntOrNull()
            if (h == null || mi == null || h > 23 || mi > 59) toast("כתבו שעה בפורמט 20:30")
            else startProgram(ProgramRunner.msUntil(h, mi), if (daily.isChecked) intArrayOf(h, mi) else null)
        }))
        p.addView(button("⏹ עצור תוכנית") { runner.stop(); progStatus.text = "⏹ נעצר." })
        progStatus = label("אין תוכנית פעילה.", 16f, true)
        p.addView(progStatus)
        p.addView(label("💡 התזמון עובד כל עוד האפליקציה פתוחה (המסך נשאר דולק). השאירו את הטלפון על מטען, מכוון לנדנדה."))
        reloadPrograms(null)
        return p
    }

    private fun reloadPrograms(select: String?) {
        var progs = store.programs()
        if (progs.isEmpty()) {
            store.savePrograms(linkedMapOf(DEFAULT_NAME to DEFAULT_PROGRAM))
            progs = store.programs()
        }
        val names = progs.keys.toList()
        progSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val idx = names.indexOf(select).coerceAtLeast(0)
        progSpinner.onItemSelectedListener = null
        progSpinner.setSelection(idx, false)
        progName.setText(names[idx])
        progBody.setText(progs[names[idx]])
        editingNew = false
        progSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val n = names[pos]
                progName.setText(n)
                progBody.setText(store.programs()[n] ?: "")
                editingNew = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun saveProgram() {
        val name = progName.text.toString().trim()
        if (name.isEmpty()) { toast("תנו שם לתוכנית"); return }
        val body = progBody.text.toString()
        val map = store.programs()
        val old = progSpinner.selectedItem as? String
        if (!editingNew && old != null && old != name) map.remove(old)
        map[name] = body
        store.savePrograms(map)
        reloadPrograms(name)
        try { runner.parse(body); toast("נשמר ✓") } catch (e: IllegalArgumentException) { toast("נשמר, אבל: ${e.message}") }
    }

    private fun newProgram() {
        editingNew = true
        progName.setText("תוכנית חדשה")
        progBody.setText("# כתבו כאן את הפקודות\n")
    }

    private fun deleteProgram() {
        val sel = progSpinner.selectedItem as? String ?: return
        confirm("למחוק את התוכנית \"$sel\"?") {
            val map = store.programs()
            map.remove(sel)
            store.savePrograms(map)
            reloadPrograms(null)
        }
    }

    private fun startProgram(delayMs: Long, dailyAt: IntArray?) {
        val name = progName.text.toString().trim().ifEmpty { "תוכנית" }
        val steps = try {
            runner.parse(progBody.text.toString())
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "שגיאה בתוכנית"); return
        }
        if (steps.isEmpty()) { toast("התוכנית ריקה"); return }
        runner.start(name, steps, delayMs, dailyAt)
    }

    // ---------------- UI helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun vbox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(24))
    }

    private fun label(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("כן") { _, _ -> onYes() }
            .setNegativeButton("לא", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val START_TEXT = "▶ התחל / המשך סריקה"
        private const val STOP_TEXT = "⏸ עצור סריקה"
        private const val DEFAULT_NAME = "הרדמה (דוגמה)"
        private const val DEFAULT_PROGRAM =
            "# דוגמה — החליפו את השמות בשמות הכפתורים מלשונית 'שלט'\n" +
            "שלח הדלקה\n" +
            "חכה 3\n" +
            "שלח מהירות+\n" +
            "חכה 20 דקות\n" +
            "שלח הדלקה\n"
        private const val HELP_TEXT =
            "פקודות (אחת בכל שורה):\n" +
            "• שלח <שם כפתור>  — או קוד כמו 00:45\n" +
            "• חכה <זמן>  — למשל: חכה 10 / חכה 5 דקות / חכה 1 שעה\n" +
            "• חזור <מספר> … סוף  — חזרה על קבוצת שורות\n" +
            "• # הערה"
    }
}

package com.ofir.amaliaseatwell

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView

/** Text programs that send buttons of any remote, now / after a delay / at a time (daily). */
class ProgramsActivity : Activity() {

    private lateinit var spinner: Spinner
    private lateinit var progName: EditText
    private lateinit var progBody: EditText
    private lateinit var progStatus: TextView
    private var editingNew = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        title = "תוכניות ותזמונים"
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val p = vbox()
        p.addView(label(HELP, 14f))
        p.addView(label("הכפתורים שלכם: " + buttonsList(), 13f))
        spinner = Spinner(this)
        p.addView(spinner)
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
        p.addView(hrow(
            btn("💾 שמור") { save() },
            btn("➕ חדשה") { newProgram() },
            btn("🗑 מחק") { delete() }
        ))
        p.addView(btn("▶ הפעל עכשיו") { startProgram(0L, null) }.apply { textSize = 18f })

        val minutes = EditText(this).apply { hint = "דקות"; inputType = InputType.TYPE_CLASS_NUMBER }
        p.addView(hrow(minutes, btn("⏳ הפעל בעוד") {
            val m = minutes.text.toString().toIntOrNull()
            if (m == null) toast("כתבו מספר דקות") else startProgram(m * 60_000L, null)
        }))
        val time = EditText(this).apply {
            hint = "20:30"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        val daily = CheckBox(this).apply { text = "כל יום" }
        p.addView(hrow(time, daily, btn("⏰ בשעה") {
            val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*$").find(time.text.toString())
            val h = m?.groupValues?.get(1)?.toIntOrNull()
            val mi = m?.groupValues?.get(2)?.toIntOrNull()
            if (h == null || mi == null || h > 23 || mi > 59) toast("כתבו שעה בפורמט 20:30")
            else startProgram(ProgramRunner.msUntil(h, mi), if (daily.isChecked) intArrayOf(h, mi) else null)
        }))
        p.addView(btn("⏹ עצור תוכנית") { Core.runner.stop() })
        progStatus = label("", 16f, true)
        p.addView(progStatus)
        p.addView(label("💡 התזמון עובד כל עוד האפליקציה פתוחה. השאירו את הטלפון על מטען, מכוון למכשיר.", 13f))
        setContentView(page(p))
        reload(null)
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progStatus.text = Core.runner.lastStatus
        Core.runner.statusListener = { s -> runOnUiThread { progStatus.text = s } }
    }

    override fun onPause() {
        super.onPause()
        Core.runner.statusListener = null
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun buttonsList(): String {
        val s = Core.store.remotes().flatMap { r -> r.buttons.filter { !it.hidden }.map { "${r.name}/${it.name}" } }
        return if (s.isEmpty()) "(עוד אין)" else s.joinToString(" · ")
    }

    private fun reload(select: String?) {
        var progs = Core.store.programs()
        if (progs.isEmpty()) {
            Core.store.savePrograms(linkedMapOf(DEFAULT_NAME to DEFAULT_PROGRAM))
            progs = Core.store.programs()
        }
        val names = progs.keys.toList()
        spinner.onItemSelectedListener = null
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val idx = names.indexOf(select).coerceAtLeast(0)
        spinner.setSelection(idx, false)
        progName.setText(names[idx])
        progBody.setText(progs[names[idx]])
        editingNew = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                progName.setText(names[pos])
                progBody.setText(Core.store.programs()[names[pos]] ?: "")
                editingNew = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun save() {
        val name = progName.text.toString().trim()
        if (name.isEmpty()) { toast("תנו שם לתוכנית"); return }
        val body = progBody.text.toString()
        val map = Core.store.programs()
        val old = spinner.selectedItem as? String
        if (!editingNew && old != null && old != name) map.remove(old)
        map[name] = body
        Core.store.savePrograms(map)
        reload(name)
        try { Core.runner.parse(body); toast("נשמר ✓") } catch (e: IllegalArgumentException) { toast("נשמר, אבל: ${e.message}") }
    }

    private fun newProgram() {
        editingNew = true
        progName.setText("תוכנית חדשה")
        progBody.setText("# כתבו כאן את הפקודות\n")
    }

    private fun delete() {
        val sel = spinner.selectedItem as? String ?: return
        confirm("למחוק את התוכנית \"$sel\"?") {
            val map = Core.store.programs()
            map.remove(sel)
            Core.store.savePrograms(map)
            reload(null)
        }
    }

    private fun startProgram(delayMs: Long, dailyAt: IntArray?) {
        val name = progName.text.toString().trim().ifEmpty { "תוכנית" }
        val steps = try {
            Core.runner.parse(progBody.text.toString())
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "שגיאה בתוכנית"); return
        }
        if (steps.isEmpty()) { toast("התוכנית ריקה"); return }
        Core.runner.start(name, steps, delayMs, dailyAt)
    }

    companion object {
        private const val DEFAULT_NAME = "הרדמה (דוגמה)"
        private const val DEFAULT_PROGRAM =
            "# דוגמה: החליפו בשמות מהשלט שלכם\n" +
                "שלח נדנדה/הדלקה\n" +
                "חכה 3\n" +
                "שלח נדנדה/מהירות +\n" +
                "חכה 20 דקות\n" +
                "שלח נדנדה/הדלקה\n"
        private const val HELP =
            "פקודות (אחת בכל שורה):\n" +
                "• שלח <שלט>/<כפתור>  (או רק שם כפתור, או קוד כמו 00:45)\n" +
                "• חכה <זמן>  (למשל: חכה 10 / חכה 5 דקות / חכה 1 שעה)\n" +
                "• חזור <מספר> … סוף  (חזרה על קבוצת שורות)\n" +
                "• # הערה"
    }
}

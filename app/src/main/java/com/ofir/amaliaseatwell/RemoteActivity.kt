package com.ofir.amaliaseatwell

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** The remote itself: a customizable grid of buttons, plus edit / test / export tools. */
class RemoteActivity : Activity() {

    private lateinit var remote: Remote
    private lateinit var grid: GridLayout
    private lateinit var banner: TextView
    private var editMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        val r = Core.store.remote(intent.getStringExtra(EXTRA_REMOTE))
        if (r == null) { toast("השלט לא נמצא"); finish(); return }
        remote = r
        title = r.name
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val p = vbox(10)
        banner = label("", 14f)
        p.addView(banner)
        grid = GridLayout(this).apply { columnCount = 3 }
        p.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(page(p))
    }

    override fun onResume() {
        super.onResume()
        if (::remote.isInitialized) render()
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_EDIT, 0, "✏️ עריכה").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, M_GUIDED, 1, "🧪 בדיקה מודרכת (תן שמות, אחד כפולים)")
        menu.add(0, M_VERIFY, 2, "🤖 בדיקה אוטומטית של כל הכפתורים")
        menu.add(0, M_CLEAN, 3, "🧹 מחק כפתורים שלא הגיבו")
        menu.add(0, M_SCAN, 4, "🔍 סריקה")
        menu.add(0, M_PIN, 5, "📌 הוסף למסך הבית")
        menu.add(0, M_EXPORT, 6, "📤 ייצוא")
        menu.add(0, M_ADD, 7, "➕ הוספת קוד ידנית")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_EDIT)?.title = if (editMode) "✓ סיום" else "✏️ עריכה"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_EDIT -> { editMode = !editMode; invalidateOptionsMenu(); render() }
            M_GUIDED -> guided(0)
            M_VERIFY -> confirm(
                "הבדיקה שולחת כל כפתור 3 פעמים עם הפסקות ביקורת (כ-12 שניות לכפתור) ומסמנת מה עובד.\n\n" +
                    "לפני: הדליקו את המכשיר ושמרו על שקט. להתחיל?"
            ) { openScan(remote, verify = true) }
            M_CLEAN -> {
                val dead = remote.buttons.count { it.status == DEAD }
                if (dead == 0) toast("אין כפתורים שסומנו כלא מגיבים")
                else confirm("למחוק $dead כפתורים שלא הגיבו בבדיקה?") {
                    Core.store.update { remote.buttons.removeAll { it.status == DEAD } }
                    render()
                }
            }
            M_SCAN -> openScan(remote)
            M_PIN -> pinShortcut(remote)
            M_EXPORT -> exportRemote(remote)
            M_ADD -> ask("הוספת קוד NEC", hint = "כתובת:פקודה, למשל 00:45") { s ->
                val m = Regex("^\\s*([0-9A-Fa-f]{1,2})\\s*:\\s*([0-9A-Fa-f]{1,2})\\s*$").find(s)
                if (m == null) toast("פורמט לא תקין")
                else {
                    Core.store.addButton(remote, m.groupValues[1].toInt(16), m.groupValues[2].toInt(16))
                        ?: toast("הקוד כבר קיים בשלט")
                    render()
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ grid

    private fun render() {
        grid.removeAllViews()
        val shown = remote.buttons.filter { editMode || !it.hidden }
        val untested = remote.buttons.count { it.status == UNTESTED && !it.hidden }
        val dead = remote.buttons.count { it.status == DEAD && !it.hidden }
        banner.text = when {
            editMode -> "✏️ מצב עריכה: לחצו על כפתור כדי לשנות שם, אייקון, צבע, גודל או מיקום, או כדי להסתיר אותו."
            remote.buttons.isEmpty() -> "אין עדיין כפתורים. בחרו 🔍 סריקה מהתפריט (⋮)."
            untested > 0 -> "💡 יש $untested כפתורים שעוד לא נבדקו. מומלץ: ⋮ ← 🧪 בדיקה מודרכת."
            dead > 0 -> "⚠️ $dead כפתורים לא הגיבו בבדיקה. ⋮ ← 🧹 כדי למחוק אותם."
            else -> "לחיצה = שליחה · לחיצה ארוכה = עריכה"
        }
        for (b in shown) {
            val v = Button(this).apply {
                isAllCaps = false
                text = (if (b.emoji.isNotBlank()) "${b.emoji}\n" else "") + b.name +
                    (if (b.status == DEAD) "\n⚠️" else "")
                textSize = if (b.emoji.isNotBlank()) 16f else 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = rounded(if (b.color != 0) b.color else PALETTE[0], dp(18).toFloat())
                alpha = if (b.hidden) 0.35f else if (b.status == DEAD) 0.55f else 1f
                setOnClickListener { view ->
                    if (editMode) editButton(b)
                    else {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (!Core.ir.send(b.address, b.command)) toast("השליחה נכשלה")
                    }
                }
                setOnLongClickListener { editButton(b); true }
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1),
                GridLayout.spec(GridLayout.UNDEFINED, if (b.wide) 3 else 1, 1f)
            ).apply {
                width = 0
                height = dp(if (b.emoji.isNotBlank()) 92 else 76)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(v, lp)
        }
    }

    // ------------------------------------------------------------------ edit a button

    private fun editButton(b: Btn) {
        val p = vbox(18)
        val name = EditText(this).apply { setText(b.name); setSingleLine(); hint = "שם" }
        val emoji = EditText(this).apply { setText(b.emoji); setSingleLine(); hint = "אייקון (אימוג'י)" }
        p.addView(label("קוד ${b.hex}" + aliasText(b) + " · " + statusText(b.status), 13f))
        p.addView(name)
        p.addView(presetRow { e, n ->
            emoji.setText(e)
            if (isDefaultName(name.text.toString())) name.setText(n)
        })
        p.addView(emoji)
        var color = if (b.color != 0) b.color else PALETTE[0]
        p.addView(label("צבע:", 14f))
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun paintColors() {
            for (i in 0 until colors.childCount) {
                val c = PALETTE[i]
                colors.getChildAt(i).background = rounded(c, dp(16).toFloat(), if (c == color) dp(3) else 0, Color.BLACK)
            }
        }
        PALETTE.forEach { c ->
            colors.addView(View(this).apply { setOnClickListener { color = c; paintColors() } },
                LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        paintColors()
        p.addView(HorizontalScrollView(this).apply { addView(colors) })
        val wide = CheckBox(this).apply { text = "כפתור רחב (שורה שלמה)"; isChecked = b.wide }
        val hidden = CheckBox(this).apply { text = "הסתר מהשלט"; isChecked = b.hidden }
        p.addView(wide)
        p.addView(hidden)

        lateinit var dialog: AlertDialog
        p.addView(hrow(
            btn("⬆ הזז") { move(b, -1); dialog.dismiss() },
            btn("⬇ הזז") { move(b, 1); dialog.dismiss() },
            btn("📡 נסה") { Core.ir.send(b.address, b.command) }
        ))
        p.addView(hrow(
            btn("🔗 אחד עם…") { dialog.dismiss(); mergeInto(b) { render() } },
            btn("🗑 מחק") {
                dialog.dismiss()
                confirm("למחוק את \"${b.name}\"?") { Core.store.update { remote.buttons.remove(b) }; render() }
            }
        ))
        dialog = AlertDialog.Builder(this)
            .setTitle("עריכת כפתור")
            .setView(page(p))
            .setPositiveButton("שמור") { _, _ ->
                Core.store.update {
                    b.name = name.text.toString().trim().ifEmpty { b.name }
                    b.emoji = emoji.text.toString().trim()
                    b.color = color
                    b.wide = wide.isChecked
                    b.hidden = hidden.isChecked
                }
                render()
            }
            .setNegativeButton("ביטול", null)
            .create()
        dialog.show()
    }

    private fun move(b: Btn, dir: Int) {
        Core.store.update {
            val i = remote.buttons.indexOf(b)
            val j = i + dir
            if (i >= 0 && j in remote.buttons.indices) {
                remote.buttons.removeAt(i)
                remote.buttons.add(j, b)
            }
        }
        render()
    }

    /** Merge [src] into another button: src's code(s) become aliases of the target. */
    private fun mergeInto(src: Btn, after: () -> Unit) {
        val targets = remote.buttons.filter { it !== src }
        if (targets.isEmpty()) { toast("אין כפתור אחר לאחד איתו"); return }
        AlertDialog.Builder(this)
            .setTitle("\"${src.name}\" עושה את אותו הדבר כמו:")
            .setItems(targets.map { "${it.emoji} ${it.name} (${it.hex})" }.toTypedArray()) { _, w ->
                val t = targets[w]
                Core.store.update {
                    t.aliases.add(pack(src.address, src.command))
                    t.aliases.addAll(src.aliases)
                    if (t.status != WORKS && src.status == WORKS) t.status = WORKS
                    remote.buttons.remove(src)
                }
                toast("אוחד לתוך \"${t.name}\"")
                after()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ------------------------------------------------------------------ guided test

    /** Sends each button and asks the user what happened: name it, mark dead, or merge duplicates. */
    private fun guided(index: Int) {
        val list = remote.buttons.filter { !it.hidden }
        if (index >= list.size) {
            render()
            toast(if (list.isEmpty()) "אין כפתורים לבדוק" else "🎉 הבדיקה המודרכת הסתיימה")
            return
        }
        val b = list[index]
        Core.ir.send(b.address, b.command)

        val p = vbox(18)
        p.addView(label("כפתור ${index + 1} מתוך ${list.size} · קוד ${b.hex}", 14f))
        p.addView(label("שלחתי את הקוד. מה המכשיר עשה?", 17f, true))
        val name = EditText(this).apply {
            setText(if (isDefaultName(b.name)) "" else b.name)
            setSingleLine()
            hint = "מה הכפתור עושה? (למשל: מהירות +)"
        }
        var emoji = b.emoji
        p.addView(presetRow { e, n -> emoji = e; name.setText(n) })
        p.addView(name)

        lateinit var dialog: AlertDialog
        p.addView(btn("🔁 שלח שוב") { Core.ir.send(b.address, b.command) })
        p.addView(btn("✅ שמור והמשך") {
            Core.store.update {
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) b.name = n
                b.emoji = emoji
                b.status = WORKS
            }
            dialog.dismiss()
            guided(index + 1)
        })
        p.addView(btn("🔗 עושה בדיוק כמו כפתור אחר…") {
            dialog.dismiss()
            mergeInto(b) { guided(index) }       // b was removed, so the same index is the next button
        })
        p.addView(btn("❌ לא קרה כלום") {
            Core.store.update { b.status = DEAD }
            dialog.dismiss()
            guided(index + 1)
        })
        p.addView(btn("⏭ דלג") { dialog.dismiss(); guided(index + 1) })
        dialog = AlertDialog.Builder(this)
            .setTitle("🧪 בדיקה מודרכת")
            .setView(page(p))
            .setNegativeButton("⏹ סיום") { _, _ -> render() }
            .setCancelable(false)
            .create()
        dialog.show()
    }

    // ------------------------------------------------------------------ helpers

    private fun presetRow(onPick: (String, String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PRESETS.forEach { (e, n) ->
            row.addView(Button(this).apply {
                text = e
                textSize = 20f
                setOnClickListener { onPick(e, n) }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
        }
        return HorizontalScrollView(this).apply { addView(row) }
    }

    private fun isDefaultName(n: String) = n.isBlank() || Regex("^כפתור \\d+$").matches(n.trim())

    private fun aliasText(b: Btn) =
        if (b.aliases.isEmpty()) "" else " (+ ${b.aliases.joinToString { hex(it shr 8, it and 0xFF) }})"

    private fun statusText(s: Int) = when (s) {
        WORKS -> "✓ עובד"
        DEAD -> "⚠️ לא הגיב בבדיקה"
        else -> "? לא נבדק"
    }

    companion object {
        private const val M_EDIT = 1
        private const val M_GUIDED = 2
        private const val M_VERIFY = 3
        private const val M_CLEAN = 4
        private const val M_SCAN = 5
        private const val M_PIN = 6
        private const val M_EXPORT = 7
        private const val M_ADD = 8
    }
}

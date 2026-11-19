package com.ofir.amaliaseatwell

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout

/** Home: list of remotes. Each remote = one device, with its own scan, layout and shortcut. */
class MainActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.init(this)
        title = "IR Lab · השלטים שלי"

        val p = vbox()
        p.addView(label("כל שלט כאן הוא מכשיר אחד. אין שלט מקורי? צרו שלט חדש והאפליקציה תמצא את הקודים בעצמה."))
        if (!Core.ir.available) {
            p.addView(label("⚠️ לא נמצא משדר IR בטלפון הזה — אפשר לנהל שלטים, אבל לא לשדר.", 15f, true)
                .apply { setTextColor(Color.RED) })
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(list)
        p.addView(btn("➕ שלט חדש") { newRemote() }.apply { textSize = 18f })
        p.addView(btn("📥 ייבוא שלט (הדבקת קובץ שלט)") { importRemote() })
        p.addView(btn("⏰ תוכניות ותזמונים") { startActivity(Intent(this, ProgramsActivity::class.java)) })
        setContentView(page(p))

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        Core.detector.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Core.detector.start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val remotes = Core.store.remotes()
        if (remotes.isEmpty()) {
            list.addView(label("עוד אין שלטים. לחצו '➕ שלט חדש' כדי להתחיל.", 16f))
            return
        }
        remotes.forEach { r ->
            val card = vbox(14).apply {
                background = rounded(0xFFF1F3F8.toInt(), dp(16).toFloat())
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { openRemote(r) }
            }
            val works = r.buttons.count { it.status == WORKS }
            val dead = r.buttons.count { it.status == DEAD }
            val scanning = Core.scanner.isRunning && Core.scanner.remoteId == r.id
            card.addView(label(r.name, 19f, true))
            card.addView(label(
                "${r.buttons.size} כפתורים · $works עובדים" +
                    (if (dead > 0) " · $dead לא הגיבו" else "") +
                    (if (scanning) " · 🔍 סורק עכשיו" else ""), 14f
            ))
            card.addView(hrow(
                btn("🎮 שלט") { openRemote(r) },
                btn("🔍 סריקה") { openScan(r) },
                btn("⋯") { menu(r) }
            ))
            list.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        }
    }

    private fun menu(r: Remote) {
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(arrayOf("✏️ שינוי שם", "📌 הוסף למסך הבית", "📤 ייצוא", "🗑 מחיקת השלט")) { _, w ->
                when (w) {
                    0 -> ask("שם השלט", r.name) { n ->
                        if (n.isNotBlank()) { Core.store.update { r.name = n.trim() }; refresh() }
                    }
                    1 -> pinShortcut(r)
                    2 -> exportRemote(r)
                    3 -> confirm("למחוק את השלט \"${r.name}\" וכל הכפתורים שלו?") {
                        if (Core.scanner.remoteId == r.id) Core.scanner.stop()
                        Core.store.deleteRemote(r.id)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun newRemote() {
        ask("שלט חדש", hint = "שם המכשיר, למשל: נדנדה, מזגן בסלון") { n ->
            if (n.isBlank()) return@ask
            val r = Core.store.addRemote(n.trim())
            openScan(r)
        }
    }

    private fun importRemote() {
        ask("ייבוא שלט", hint = "הדביקו כאן את קובץ השלט (JSON) שייצאתם", multiLine = true) { text ->
            try {
                val r = Remote.fromExport(text)
                Core.store.addRemote(r)
                refresh()
                toast("יובא: ${r.name} (${r.buttons.size} כפתורים)")
            } catch (e: Exception) {
                toast("זה לא נראה כמו קובץ שלט תקין")
            }
        }
    }
}

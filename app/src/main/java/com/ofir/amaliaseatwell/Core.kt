package com.ofir.amaliaseatwell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build

/** App-wide singletons, shared by all screens (a scan keeps running when you switch screens). */
object Core {
    lateinit var store: Store
    lateinit var ir: IrSender
    lateinit var detector: ReactionDetector
    lateinit var scanner: Scanner
    lateinit var runner: ProgramRunner
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        val app = ctx.applicationContext
        store = Store(app)
        ir = IrSender(app)
        detector = ReactionDetector(app, store).apply {
            useMic = store.micEnabled
            useMotion = store.motionEnabled
        }
        scanner = Scanner(store, ir, detector)
        runner = ProgramRunner(store, ir)
        ready = true
    }
}

fun Activity.openRemote(r: Remote) =
    startActivity(Intent(this, RemoteActivity::class.java).putExtra(EXTRA_REMOTE, r.id))

fun Activity.openScan(r: Remote, verify: Boolean = false) =
    startActivity(Intent(this, ScanActivity::class.java).putExtra(EXTRA_REMOTE, r.id).putExtra(EXTRA_VERIFY, verify))

const val EXTRA_REMOTE = "remote"
const val EXTRA_VERIFY = "verify"

/** Puts a remote on the phone's home screen as its own icon. */
fun Activity.pinShortcut(r: Remote) {
    if (Build.VERSION.SDK_INT < 26) { toast("נדרש Android 8 ומעלה"); return }
    val sm = getSystemService(ShortcutManager::class.java)
    if (sm == null || !sm.isRequestPinShortcutSupported) { toast("מסך הבית לא תומך בקיצורי דרך"); return }
    val intent = Intent(this, RemoteActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(EXTRA_REMOTE, r.id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val info = ShortcutInfo.Builder(this, "remote-${r.id}")
        .setShortLabel(r.name.take(12))
        .setLongLabel(r.name)
        .setIcon(Icon.createWithBitmap(iconBitmap(r)))
        .setIntent(intent)
        .build()
    sm.requestPinShortcut(info, null)
}

private fun iconBitmap(r: Remote): Bitmap {
    val size = 192
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val color = r.buttons.firstOrNull { it.color != 0 }?.color ?: PALETTE[0]
    c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    val label = r.buttons.firstOrNull { it.emoji.isNotBlank() }?.emoji ?: r.name.take(1)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textSize = 96f
        textAlign = Paint.Align.CENTER
    }
    c.drawText(label, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
    return bmp
}

/** Share a remote as JSON (for backup / another phone) or as a readable list. */
fun Activity.exportRemote(r: Remote) {
    android.app.AlertDialog.Builder(this)
        .setTitle("ייצוא \"${r.name}\"")
        .setItems(arrayOf("📄 רשימה קריאה (לקריאה ולתכנון)", "🧩 קובץ שלט (JSON, לגיבוי ולייבוא)")) { _, which ->
            val text = if (which == 0) r.toReadable() else r.toExport()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "שלט: ${r.name}")
                .putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, "שיתוף השלט"))
        }
        .show()
}

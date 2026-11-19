package com.ofir.amaliaseatwell

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

val PALETTE = intArrayOf(
    0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF5350.toInt(), 0xFFFFA726.toInt(),
    0xFFAB47BC.toInt(), 0xFF42A5F5.toInt(), 0xFF8D6E63.toInt(), 0xFF78909C.toInt()
)

/** Quick presets for naming buttons: icon to name. */
val PRESETS = listOf(
    "⏻" to "הדלקה", "➕" to "מהירות +", "➖" to "מהירות -", "🎵" to "מוזיקה",
    "🔊" to "ווליום +", "🔉" to "ווליום -", "⏱" to "טיימר", "⏭" to "הבא",
    "⏮" to "הקודם", "💡" to "תאורה", "🌊" to "מצב", "🔁" to "חזרה"
)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.label(s: String, size: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
    text = s
    textSize = size
    if (bold) setTypeface(null, Typeface.BOLD)
    setPadding(0, dp(6), 0, dp(6))
}

fun Context.header(s: String): TextView = label(s, 18f, true).apply { setPadding(0, dp(18), 0, dp(4)) }

fun Context.btn(s: String, onClick: () -> Unit): Button = Button(this).apply {
    text = s
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.vbox(pad: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(12), dp(pad), dp(24))
}

fun Context.hrow(vararg views: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
}

fun Context.page(content: View): ScrollView = ScrollView(this).apply {
    layoutDirection = View.LAYOUT_DIRECTION_RTL
    addView(content)
}

fun Context.confirm(msg: String, onYes: () -> Unit) {
    AlertDialog.Builder(this)
        .setMessage(msg)
        .setPositiveButton("כן") { _, _ -> onYes() }
        .setNegativeButton("ביטול", null)
        .show()
}

fun Context.toast(s: String) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

fun Context.ask(title: String, initial: String = "", hint: String = "", multiLine: Boolean = false, onOk: (String) -> Unit) {
    val e = EditText(this).apply {
        setText(initial)
        this.hint = hint
        if (multiLine) { minLines = 6; gravity = Gravity.TOP or Gravity.START } else setSingleLine()
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) })
        .setPositiveButton("אישור") { _, _ -> onOk(e.text.toString()) }
        .setNegativeButton("ביטול", null)
        .show()
}

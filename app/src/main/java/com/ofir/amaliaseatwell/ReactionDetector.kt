package com.ofir.amaliaseatwell

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Notices when the swing reacts to a code: a beep / motor sound (microphone),
 * a change in movement (accelerometer, phone placed on the swing), or a manual tap.
 */
class ReactionDetector(private val ctx: Context) : SensorEventListener {

    @Volatile var useMic = true
    @Volatile var useMotion = false
    @Volatile var factor = 4.0

    private class Sample(val t: Long, val v: Double)

    private val mic = ArrayDeque<Sample>()
    private val motion = ArrayDeque<Sample>()
    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var micThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var manualAt = 0L
    private var gravity = SensorManager.GRAVITY_EARTH.toDouble()

    val micActive: Boolean get() = micThread?.isAlive == true

    fun start() {
        if (!running) {
            running = true
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if (!micActive &&
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) startMic()
    }

    fun stop() {
        running = false
        sensors.unregisterListener(this)
        micThread?.join(500)
        micThread = null
    }

    fun markManual() { manualAt = System.currentTimeMillis() }

    /** Returns true if a manual tap was pending, and clears it. */
    fun consumeManual(): Boolean {
        val had = manualAt != 0L
        manualAt = 0L
        return had
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        micThread = Thread({
            val rate = 16000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192))
            } catch (e: Exception) { null } ?: return@Thread
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@Thread }
            val buf = ShortArray(512)   // ~32 ms per frame
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
                    add(mic, sqrt(sum / n))
                }
            } catch (e: Exception) {
            } finally {
                try { rec.stop() } catch (e: Exception) {}
                rec.release()
            }
        }, "mic").also { it.start() }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
        val m = sqrt(x * x + y * y + z * z)
        gravity = gravity * 0.95 + m * 0.05
        add(motion, abs(m - gravity))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun add(q: ArrayDeque<Sample>, v: Double) {
        val now = System.currentTimeMillis()
        synchronized(q) {
            q.addLast(Sample(now, v))
            while (q.isNotEmpty() && q.first().t < now - 8000) q.removeFirst()
        }
    }

    private fun values(q: ArrayDeque<Sample>, from: Long, to: Long): List<Double> =
        synchronized(q) { q.filter { it.t in from..to }.map { it.v } }

    private fun median(l: List<Double>): Double {
        if (l.isEmpty()) return 0.0
        val s = l.sorted()
        return s[s.size / 2]
    }

    /** Live loudness relative to the recent background (for the on-screen meter). */
    fun micRatio(): Double {
        val now = System.currentTimeMillis()
        val before = values(mic, now - 3000, now - 500)
        val cur = values(mic, now - 300, now)
        if (cur.isEmpty()) return 0.0
        return (cur.maxOrNull() ?: 0.0) / max(median(before), MIN_MIC)
    }

    /**
     * Blocks until [sendAt] + [windowMs] and reports whether the swing reacted in that window.
     * [slow] enables extra checks for sustained changes (motor starting or stopping).
     */
    fun reacted(sendAt: Long, windowMs: Long, slow: Boolean): Boolean {
        val end = sendAt + windowMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            Thread.sleep(minOf(left, 25L))
        }
        if (manualAt != 0L && manualAt >= sendAt) return true
        if (useMic && check(mic, sendAt, end, MIN_MIC, slow)) return true
        if (useMotion && check(motion, sendAt, end, MIN_MOTION, slow)) return true
        return false
    }

    private fun check(q: ArrayDeque<Sample>, sendAt: Long, end: Long, minLevel: Double, slow: Boolean): Boolean {
        val before = values(q, sendAt - 1500, sendAt - 1)
        val after = values(q, sendAt + 40, end)
        if (before.size < 3 || after.size < 2) return false
        val base = max(median(before), minLevel)
        val sorted = after.sorted()
        val peak = sorted[sorted.size - 2]          // 2nd highest: ignore a single glitch
        if (peak / base >= factor) return true      // beep / sudden sound or jolt
        if (slow && after.size >= 6) {
            val late = median(after.subList(after.size / 2, after.size))
            if (late / base >= sqrt(factor) * 1.2) return true                       // motor started
            if (base >= minLevel * 3 && late <= base / sqrt(factor)) return true     // motor stopped
        }
        return false
    }

    companion object {
        const val MIN_MIC = 40.0       // raw 16-bit RMS floor for a very quiet room
        const val MIN_MOTION = 0.015   // m/s² floor for a phone lying still
    }
}

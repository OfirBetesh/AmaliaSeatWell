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
import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides whether the device reacted to a code.
 *
 * With calibration it listens only to the frequency of the device's own beep (learned from
 * a few presses on the device), so room noise, talking or a TV don't count. Without it, it
 * falls back to overall loudness. Optionally also motion (accelerometer) or a manual tap.
 */
class ReactionDetector(private val ctx: Context, private val store: Store) : SensorEventListener {

    @Volatile var useMic = true
    @Volatile var useMotion = false

    private class Sample(val t: Long, val rms: Double, val band: Double)
    private class Frame(val rms: Double, val mag: DoubleArray)

    private val mic = ArrayDeque<Sample>()
    private val motion = ArrayDeque<Sample>()
    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var micThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var manualAt = 0L
    @Volatile private var capture: MutableList<Frame>? = null
    @Volatile private var bandBin = store.calibration?.bin ?: -1
    private var gravity = SensorManager.GRAVITY_EARTH.toDouble()

    val micActive: Boolean get() = micThread?.isAlive == true
    val calibrated: Boolean get() = bandBin > 0 && store.calibration != null

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

    fun consumeManual(): Boolean {
        val had = manualAt != 0L
        manualAt = 0L
        return had
    }

    fun clearCalibration() {
        store.calibration = null
        bandBin = -1
    }

    // ---------------------------------------------------------------- audio

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
            val buf = ShortArray(N)
            val re = DoubleArray(N)
            val im = DoubleArray(N)
            try {
                rec.startRecording()
                while (running) {
                    var got = 0
                    while (got < N && running) {
                        val n = rec.read(buf, got, N - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got < N) continue
                    var sum = 0.0
                    for (i in 0 until N) {
                        val s = buf[i].toDouble()
                        sum += s * s
                        re[i] = s * WINDOW[i]
                        im[i] = 0.0
                    }
                    val rms = sqrt(sum / N)
                    fft(re, im)
                    val cap = capture
                    if (cap != null) {
                        val mag = DoubleArray(N / 2) { b -> sqrt(re[b] * re[b] + im[b] * im[b]) }
                        cap.add(Frame(rms, mag))
                    }
                    val bin = bandBin
                    var band = 0.0
                    if (bin in 1 until N / 2 - 1) {
                        for (b in bin - 1..bin + 1) band += sqrt(re[b] * re[b] + im[b] * im[b])
                    }
                    add(mic, rms, band)
                }
            } catch (e: Exception) {
            } finally {
                try { rec.stop() } catch (e: Exception) {}
                rec.release()
            }
        }, "mic").also { it.start() }
    }

    /**
     * Learns the device's beep: a few seconds of silence, then the user presses buttons
     * on the device itself. Blocks; call from a background thread.
     */
    fun calibrate(step: (String) -> Unit): String {
        if (!micActive) return "❌ המיקרופון לא פעיל — אשרו הרשאת מיקרופון"
        step("🤫 שקט בבקשה… מודד את רעש הרקע (4 שניות)")
        val quiet = captureFor(4000)
        step("👆 עכשיו: לחצו 3 פעמים על כפתור במכשיר עצמו (לא בטלפון), בהפרש של כשנייה")
        val loud = captureFor(7000)
        if (quiet.size < 30 || loud.size < 30) return "❌ לא נאסף מספיק שמע, נסו שוב"

        val half = N / 2
        val noise = DoubleArray(half) { b -> median(quiet.map { it.mag[b] }) }
        val top = loud.sortedByDescending { it.rms }.take(6)
        var best = -1
        var bestRatio = 0.0
        for (b in 10 until 230) {                  // ~310 Hz … 7.2 kHz
            val sig = median(top.map { it.mag[b - 1] + it.mag[b] + it.mag[b + 1] })
            val nz = noise[b - 1] + noise[b] + noise[b + 1] + 1e-6
            val r = sig / nz
            if (r > bestRatio) { bestRatio = r; best = b }
        }
        if (best < 0) return "❌ לא זוהה צפצוף"
        val qBand = quiet.map { band(it.mag, best) }.sorted()
        val noiseP97 = qBand[(qBand.size * 0.97).toInt().coerceAtMost(qBand.size - 1)]
        val beep = median(top.map { band(it.mag, best) })
        if (beep < noiseP97 * 3) {
            return "❌ הצפצוף לא בולט מספיק מעל הרעש (פי ${String.format("%.1f", beep / max(noiseP97, 1e-6))}). " +
                "קרבו את הטלפון למכשיר, הורידו רעשים ונסו שוב — או השתמשו בלי כיול."
        }
        val cal = Calibration(best, sqrt(noiseP97 * beep), noiseP97, beep)
        store.calibration = cal
        bandBin = best
        return "✅ כויל! תדר הצפצוף ~${cal.hz}Hz, חזק פי ${String.format("%.0f", beep / max(noiseP97, 1e-6))} מהרקע."
    }

    private fun captureFor(ms: Long): List<Frame> {
        val l = Collections.synchronizedList(ArrayList<Frame>())
        capture = l
        Thread.sleep(ms)
        capture = null
        return synchronized(l) { ArrayList(l) }
    }

    private fun band(mag: DoubleArray, bin: Int) = mag[bin - 1] + mag[bin] + mag[bin + 1]

    // ---------------------------------------------------------------- motion

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
        val m = sqrt(x * x + y * y + z * z)
        gravity = gravity * 0.95 + m * 0.05
        add(motion, abs(m - gravity), 0.0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- detection

    private fun add(q: ArrayDeque<Sample>, rms: Double, band: Double) {
        val now = System.currentTimeMillis()
        synchronized(q) {
            q.addLast(Sample(now, rms, band))
            while (q.isNotEmpty() && q.first().t < now - 8000) q.removeFirst()
        }
    }

    private fun window(q: ArrayDeque<Sample>, from: Long, to: Long): List<Sample> =
        synchronized(q) { q.filter { it.t in from..to } }

    /** Loudness factor for the uncalibrated mode: sensitivity 1 → ×10 … 9 → ×2. */
    private fun relFactor(): Double = (11 - store.sensitivity).toDouble().coerceIn(2.0, 10.0)

    /** Threshold scale for calibrated mode: sensitivity 1 → ×4 … 5 → ×1 … 9 → ×0.25. */
    private fun calScale(): Double = 2.0.pow((5 - store.sensitivity) / 2.0)

    /** Blocks until [sendAt] + [windowMs]; true if the device reacted in that window. */
    fun reacted(sendAt: Long, windowMs: Long, slow: Boolean): Boolean {
        val end = sendAt + windowMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            Thread.sleep(minOf(left, 25L))
        }
        if (manualAt != 0L && manualAt >= sendAt) return true
        if (useMic && checkMic(sendAt, end, slow)) return true
        if (useMotion && checkMotion(sendAt, end, slow)) return true
        return false
    }

    private fun checkMic(sendAt: Long, end: Long, slow: Boolean): Boolean {
        val before = window(mic, sendAt - 1500, sendAt - 1)
        val after = window(mic, sendAt + 40, end)
        if (before.size < 3 || after.size < 2) return false
        val cal = store.calibration
        if (cal != null && cal.bin == bandBin) {
            val peak = second(after.map { it.band })
            val base = median(before.map { it.band })
            if (peak >= cal.threshold * calScale() && peak >= base * 2.0) return true
            if (!slow) return false
            // motor start / stop while calibrated: require strong sustained change
            return sustained(before.map { it.rms }, after.map { it.rms }, MIN_MIC, 2.5)
        }
        val base = max(median(before.map { it.rms }), MIN_MIC)
        if (second(after.map { it.rms }) / base >= relFactor()) return true
        return slow && sustained(before.map { it.rms }, after.map { it.rms }, MIN_MIC, sqrt(relFactor()) * 1.2)
    }

    private fun checkMotion(sendAt: Long, end: Long, slow: Boolean): Boolean {
        val before = window(motion, sendAt - 1500, sendAt - 1).map { it.rms }
        val after = window(motion, sendAt + 40, end).map { it.rms }
        if (before.size < 3 || after.size < 2) return false
        val base = max(median(before), MIN_MOTION)
        if (second(after) / base >= relFactor()) return true
        return slow && sustained(before, after, MIN_MOTION, sqrt(relFactor()) * 1.2)
    }

    /** Level clearly rose (something started) or clearly fell (something stopped). */
    private fun sustained(before: List<Double>, after: List<Double>, floor: Double, f: Double): Boolean {
        if (after.size < 6) return false
        val base = max(median(before), floor)
        val late = median(after.subList(after.size / 2, after.size))
        if (late / base >= f) return true
        return base >= floor * 3 && late <= base / f
    }

    /** Text for the live meter. */
    fun meterText(): String {
        if (!micActive) return "🎤 המיקרופון לא פעיל — אשרו הרשאת מיקרופון בהגדרות"
        val now = System.currentTimeMillis()
        val before = window(mic, now - 3000, now - 500)
        val cur = window(mic, now - 300, now)
        if (cur.isEmpty()) return "🎤 …"
        val cal = store.calibration
        return if (cal != null && cal.bin == bandBin) {
            val pct = (cur.maxOf { it.band } / (cal.threshold * calScale()) * 100).toInt()
            "🎤 צפצוף (~${cal.hz}Hz): $pct% מהסף" + if (pct >= 100) "  🔔" else ""
        } else {
            val r = cur.maxOf { it.rms } / max(median(before.map { it.rms }), MIN_MIC)
            "🎤 עוצמה: פי ${String.format("%.1f", r)} מהרקע (סף: פי ${relFactor().toInt()})"
        }
    }

    companion object {
        private const val N = 512
        const val MIN_MIC = 40.0
        const val MIN_MOTION = 0.015
        private val WINDOW = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }

        fun median(l: List<Double>): Double {
            if (l.isEmpty()) return 0.0
            val s = l.sorted()
            return s[s.size / 2]
        }

        private fun second(l: List<Double>): Double {
            val s = l.sorted()
            return if (s.size >= 2) s[s.size - 2] else s.lastOrNull() ?: 0.0
        }

        /** In-place iterative radix-2 FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                val h = len / 2
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until h) {
                        val xr = re[i + k + h] * cr - im[i + k + h] * ci
                        val xi = re[i + k + h] * ci + im[i + k + h] * cr
                        re[i + k + h] = re[i + k] - xr; im[i + k + h] = im[i + k] - xi
                        re[i + k] += xr; im[i + k] += xi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}

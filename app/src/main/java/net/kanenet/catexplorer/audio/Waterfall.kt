package net.kanenet.catexplorer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the screen needs to draw a waterfall picture, whichever source made it. */
interface WaterfallPicture {
    val width: Int
    val height: Int
    /** ARGB rows, newest at the top. Read and write only under [lock]. */
    val pixels: IntArray
    /** Latest spectrum in dB above the noise floor, one value per pixel column. */
    val trace: FloatArray
    val lock: Any
    var rangeDb: Float
}

/** How the radio's audio maps to RF: upper sideband, lower sideband (audio runs backwards), or no simple mapping (AM, FM, RTTY). */
enum class Sense(val sign: Int) { UPPER(1), LOWER(-1), NONE(0) }

object TuneMath {
    fun sense(mode: String): Sense = when (mode) {
        "USB", "DATA-U", "CW-U" -> Sense.UPPER
        "LSB", "DATA-L", "CW-L" -> Sense.LOWER
        else -> Sense.NONE
    }

    /** The audio tone that sits exactly on the dial frequency: the CW pitch in CW, 0 Hz (the carrier) otherwise. */
    fun dialTone(mode: String, cwPitch: Int) = if (mode.startsWith("CW")) cwPitch else 0

    /** Where a tap moves a signal to: the CW pitch, 1500 Hz in DATA (the middle of WSJT-X/fldigi), the carrier in SSB. */
    fun target(mode: String, cwPitch: Int) = when {
        mode.startsWith("CW") -> cwPitch
        mode.startsWith("DATA") -> 1500
        else -> 0
    }

    /** RF frequency of an audio tone, or null in modes without a simple mapping. */
    fun rfOf(audioHz: Double, dialHz: Long, mode: String, cwPitch: Int): Long? {
        val s = sense(mode).sign
        if (s == 0) return null
        return dialHz + (s * (audioHz - dialTone(mode, cwPitch))).roundToInt()
    }

    /**
     * New dial frequency for a tap at RF frequency [rfHz] on the SDR view: in DATA the signal goes to 1500 Hz in the audio
     * (as in WSJT-X/fldigi), otherwise the tapped point becomes the dial (the carrier in SSB, the signal itself in CW,
     * because the FT-891 shows a CW signal's own frequency). Rounded to 10 Hz.
     */
    fun wideRetune(rfHz: Double, mode: String): Long {
        val hz = when (mode) {
            "DATA-U" -> rfHz - 1500
            "DATA-L" -> rfHz + 1500
            else -> rfHz
        }
        return (hz / 10.0).roundToInt() * 10L
    }

    /** New dial frequency that moves the tone at [audioHz] to [target], rounded to 10 Hz. */
    fun retune(audioHz: Double, dialHz: Long, mode: String, cwPitch: Int): Long? {
        val s = sense(mode).sign
        if (s == 0) return null
        val hz = dialHz + s * (audioHz - target(mode, cwPitch))
        return (hz / 10.0).roundToInt() * 10L
    }
}

/** Radix-2 FFT of a Hann-windowed block, returned as power in dB per bin (only the bins below [bins]). */
class Spectrum(val n: Int) {
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    /** [ring] holds the latest samples, oldest at [start] (wrapping). Writes dB values into [out]. */
    fun compute(ring: FloatArray, start: Int, out: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            re[j] = ring[(start + i) % n] * window[i]
            im[j] = 0f
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var k = 0
            while (k < n) {
                for (m in 0 until half) {
                    val c = cosT[m * step]; val s = -sinT[m * step]
                    val a = k + m; val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                k += size
            }
            size *= 2
        }
        for (i in out.indices) out[i] = 10f * log10(re[i] * re[i] + im[i] * im[i] + 1e-12f)
    }
}

/**
 * Turns the audio stream into waterfall rows. Samples go in with [add] (any thread); every [hop] samples a new row is made.
 * The pixels are only copied to the screen by the UI, under [lock].
 */
class WaterfallEngine(rate: Int, val maxHz: Int = 4000, override val height: Int = 220) : WaterfallPicture {
    val fftSize = 2048
    val binHz = rate.toDouble() / fftSize
    override val width = (maxHz / binHz).toInt()
    private val hop = rate / 20                      // 20 rows a second
    private val spectrum = Spectrum(fftSize)
    private val ring = FloatArray(fftSize)
    private var pos = 0
    private var sinceRow = 0
    private val db = FloatArray(width)
    private val sorted = FloatArray(width)

    override val lock = Any()
    override val pixels = IntArray(width * height) { BLACK }
    override val trace = FloatArray(width)
    @Volatile var floorDb = Float.NaN
        private set
    /** dB above the noise floor that reaches the top of the colour scale. */
    @Volatile override var rangeDb = 45f
    /** Fraction of recent samples that hit full scale. */
    @Volatile var clipFraction = 0f
        private set
    private var clipped = 0
    private var counted = 0

    /** Feeds samples; returns true when at least one new row was made. [frozen] (while transmitting) skips making rows. */
    fun add(s: ShortArray, n: Int, frozen: Boolean): Boolean {
        var made = false
        for (i in 0 until n) {
            val v = s[i].toInt()
            if (v >= 32700 || v <= -32700) clipped++
            ring[pos] = v / 32768f
            pos = (pos + 1) % fftSize
            if (++sinceRow >= hop) {
                sinceRow = 0
                if (!frozen) { row(); made = true }
            }
        }
        counted += n
        if (counted >= 12000) { clipFraction = clipped.toFloat() / counted; clipped = 0; counted = 0 }
        return made
    }

    private fun row() {
        spectrum.compute(ring, pos, db)
        db.copyInto(sorted); sorted.sort()
        val f = sorted[width / 5]                      // 20th percentile ≈ the noise floor
        floorDb = if (floorDb.isNaN()) f else floorDb * 0.95f + f * 0.05f
        val lo = floorDb - 3f
        val r = rangeDb
        synchronized(lock) {
            System.arraycopy(pixels, 0, pixels, width, width * (height - 1))
            for (x in 0 until width) {
                val level = ((db[x] - lo) / r).coerceIn(0f, 1f)
                pixels[x] = PALETTE[(level * 255).toInt()]
                trace[x] = trace[x] * 0.5f + (db[x] - floorDb) * 0.5f
            }
        }
    }

    /** Slides the whole picture sideways by [px] pixels (positive = right) so old traces stay under their signals after a retune. */
    fun shift(px: Int) {
        if (px == 0) return
        synchronized(lock) {
            if (kotlin.math.abs(px) >= width) { pixels.fill(BLACK); trace.fill(0f); return }
            for (y in 0 until height) {
                val o = y * width
                if (px > 0) {
                    System.arraycopy(pixels, o, pixels, o + px, width - px)
                    pixels.fill(BLACK, o, o + px)
                } else {
                    System.arraycopy(pixels, o - px, pixels, o, width + px)
                    pixels.fill(BLACK, o + width + px, o + width)
                }
            }
            trace.fill(0f)
        }
    }

    fun clear() = synchronized(lock) { pixels.fill(BLACK); trace.fill(0f) }

    /** Audio frequency of the strongest bin within ±[span] Hz of [hz]. */
    fun peakNear(hz: Double, span: Double): Double {
        val c = (hz / binHz).toInt()
        val w = (span / binHz).toInt()
        var best = c.coerceIn(0, width - 1)
        synchronized(lock) {
            for (x in (c - w).coerceAtLeast(0)..(c + w).coerceAtMost(width - 1)) if (trace[x] > trace[best]) best = x
        }
        return (best + 0.5) * binHz
    }

    companion object {
        const val BLACK = 0xFF000000.toInt()
        /** Black → blue → cyan → yellow → red → white. */
        val PALETTE = IntArray(256) { i ->
            val stops = arrayOf(
                0f to intArrayOf(0, 0, 0), 0.2f to intArrayOf(0, 0, 140), 0.4f to intArrayOf(0, 160, 220),
                0.6f to intArrayOf(240, 230, 0), 0.8f to intArrayOf(240, 40, 0), 1f to intArrayOf(255, 255, 255),
            )
            val t = i / 255f
            val k = stops.indexOfLast { it.first <= t }.coerceAtMost(stops.size - 2)
            val (t0, c0) = stops[k]; val (t1, c1) = stops[k + 1]
            val u = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
            val rgb = IntArray(3) { (c0[it] + (c1[it] - c0[it]) * u).toInt() }
            (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
    }
}

/** One spectrum line from the PC's RTL-SDR: [db] runs low to high frequency, bin i centred on centreHz + (i - size/2) * binHz. */
class SpecFrame(val centreHz: Long, val binHz: Float, val db: FloatArray, val overload: Boolean)

/**
 * The wide SDR view: rows of [width] pixels covering [spanHz] around the dial. A dial change slides the old rows sideways so
 * every signal stays in its column; a span change starts a fresh picture.
 */
class WideEngine(override val width: Int = 1000, override val height: Int = 220) : WaterfallPicture {
    override val lock = Any()
    override val pixels = IntArray(width * height) { WaterfallEngine.BLACK }
    override val trace = FloatArray(width)
    @Volatile override var rangeDb = 45f
    @Volatile var floorDb = Float.NaN
        private set
    /** RF frequency in the middle of the picture (the dial, as of the newest row). */
    @Volatile var centreHz = 0L
        private set
    @Volatile var spanHz = 200_000
        set(v) { if (v != field) { field = v; clear() } }
    @Volatile var overload = false
        private set
    val hzPerPx get() = spanHz.toDouble() / width
    private val row = FloatArray(width)
    private val sorted = FloatArray(width)

    @Volatile private var lastFrame: SpecFrame? = null
    /** ~0.5 s running average of each bin's power (linear), for finding a weak signal's exact frequency. */
    private var avgPower = FloatArray(0)
    private val avgLock = Any()

    /** Adds one spectrum line. Returns true when a row was drawn ([frozen], while transmitting, skips drawing). */
    fun add(f: SpecFrame, frozen: Boolean): Boolean {
        overload = f.overload
        synchronized(avgLock) {
            val same = lastFrame?.let { it.centreHz == f.centreHz && it.binHz == f.binHz && it.db.size == f.db.size } == true
            if (!same || avgPower.size != f.db.size) avgPower = FloatArray(f.db.size) { Math.pow(10.0, f.db[it] / 10.0).toFloat() }
            else for (i in avgPower.indices) avgPower[i] = avgPower[i] * 0.9f + Math.pow(10.0, f.db[i] / 10.0).toFloat() * 0.1f
            lastFrame = f
        }
        if (centreHz != 0L && f.centreHz != centreHz) shift(Math.round(-(f.centreHz - centreHz) / hzPerPx).toInt())
        centreHz = f.centreHz
        if (frozen) return false
        val n = f.db.size
        val half = n / 2.0
        var valid = 0
        for (x in 0 until width) {
            val lo = -spanHz / 2.0 + x * hzPerPx              // Hz from the dial, this pixel's left edge
            val hi = lo + hzPerPx
            val b0 = Math.floor(lo / f.binHz + half + 0.5).toInt()
            val b1 = maxOf(b0, Math.floor(hi / f.binHz + half + 0.5).toInt() - 1)
            var v = Float.NaN
            if (b1 >= 0 && b0 < n) for (b in b0.coerceAtLeast(0)..b1.coerceAtMost(n - 1)) if (v.isNaN() || f.db[b] > v) v = f.db[b]
            row[x] = v
            if (!v.isNaN()) sorted[valid++] = v
        }
        if (valid == 0) return false
        java.util.Arrays.sort(sorted, 0, valid)
        val fl = sorted[valid / 5]
        floorDb = if (floorDb.isNaN()) fl else floorDb * 0.9f + fl * 0.1f
        val lo = floorDb - 3f
        val r = rangeDb
        synchronized(lock) {
            System.arraycopy(pixels, 0, pixels, width, width * (height - 1))
            for (x in 0 until width) {
                val v = row[x]
                if (v.isNaN()) { pixels[x] = WaterfallEngine.BLACK; trace[x] = 0f; continue }
                pixels[x] = WaterfallEngine.PALETTE[(((v - lo) / r).coerceIn(0f, 1f) * 255).toInt()]
                trace[x] = trace[x] * 0.5f + (v - floorDb) * 0.5f
            }
        }
        return true
    }

    fun shift(px: Int) {
        if (px == 0) return
        synchronized(lock) {
            if (kotlin.math.abs(px) >= width) { pixels.fill(WaterfallEngine.BLACK); trace.fill(0f); return }
            for (y in 0 until height) {
                val o = y * width
                if (px > 0) {
                    System.arraycopy(pixels, o, pixels, o + px, width - px)
                    pixels.fill(WaterfallEngine.BLACK, o, o + px)
                } else {
                    System.arraycopy(pixels, o - px, pixels, o, width + px)
                    pixels.fill(WaterfallEngine.BLACK, o + width + px, o + width)
                }
            }
            trace.fill(0f)
        }
    }

    fun clear() = synchronized(lock) { pixels.fill(WaterfallEngine.BLACK); trace.fill(0f) }

    /** RF frequency under pixel column [x] (may be fractional). */
    fun rfAt(x: Double): Double = centreHz - spanHz / 2.0 + (x + 0.5) * hzPerPx

    /**
     * The exact frequency of the strongest signal within ±[windowHz] of [rfHz], from the latest spectrum line's own bins
     * (finer than the screen's pixels), interpolated between neighbouring bins. Returns [rfHz] if there's no data.
     */
    fun refinePeak(rfHz: Double, windowHz: Double): Double {
        val f = lastFrame ?: return rfHz
        val n = f.db.size
        val db = synchronized(avgLock) { if (avgPower.size == n) FloatArray(n) { 10f * kotlin.math.log10(avgPower[it] + 1e-12f) } else f.db }
        fun binOf(hz: Double) = Math.round((hz - f.centreHz) / f.binHz + n / 2.0).toInt()
        val lo = binOf(rfHz - windowHz).coerceIn(1, n - 2)
        val hi = binOf(rfHz + windowHz).coerceIn(1, n - 2)
        if (hi < lo) return rfHz
        var best = lo
        for (b in lo..hi) if (db[b] > db[best]) best = b
        val (y0, y1, y2) = Triple(db[best - 1], db[best], db[best + 1])
        val d = y0 - 2 * y1 + y2
        val off = if (d != 0f) (0.5f * (y0 - y2) / d).coerceIn(-0.5f, 0.5f) else 0f
        return f.centreHz + (best + off - n / 2.0) * f.binHz
    }

    /** Pixel column of the strongest signal within ±[spanPx] of [x]. */
    fun peakNear(x: Int, spanPx: Int): Int {
        var best = x.coerceIn(0, width - 1)
        synchronized(lock) {
            for (i in (x - spanPx).coerceAtLeast(0)..(x + spanPx).coerceAtMost(width - 1)) if (trace[i] > trace[best]) best = i
        }
        return best
    }
}

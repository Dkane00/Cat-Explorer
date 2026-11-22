package net.kanenet.catexplorer.audio

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import net.kanenet.catexplorer.rig.UsbLink
import java.io.IOException

/**
 * The kinds of SDR the tablet can run itself (drivers built into the app, src/main/cpp). [code] matches catsdr.c.
 * [coverage] is what the hardware tunes without an upconverter. Only the RTL-SDR has been tried on real hardware.
 */
enum class SdrKind(val code: Int, val label: String, val coverage: List<LongRange>, val gainInLevels: Boolean, val ids: Set<Pair<Int, Int>>) {
    RTL(
        0, "RTL-SDR", listOf(500_000L..1_766_000_000L), false,
        setOf(
            0x0bda to 0x2832, 0x0bda to 0x2838, 0x0413 to 0x6680, 0x0413 to 0x6f0f, 0x0458 to 0x707f, 0x0ccd to 0x00a9,
            0x0ccd to 0x00b3, 0x0ccd to 0x00b4, 0x0ccd to 0x00b5, 0x0ccd to 0x00b7, 0x0ccd to 0x00b8, 0x0ccd to 0x00b9,
            0x0ccd to 0x00c0, 0x0ccd to 0x00c6, 0x0ccd to 0x00d3, 0x0ccd to 0x00d7, 0x0ccd to 0x00e0, 0x1554 to 0x5020,
            0x15f4 to 0x0131, 0x15f4 to 0x0133, 0x185b to 0x0620, 0x185b to 0x0650, 0x185b to 0x0680, 0x1b80 to 0xd393,
            0x1b80 to 0xd394, 0x1b80 to 0xd395, 0x1b80 to 0xd397, 0x1b80 to 0xd398, 0x1b80 to 0xd39d, 0x1b80 to 0xd3a4,
            0x1b80 to 0xd3a8, 0x1b80 to 0xd3af, 0x1b80 to 0xd3b0, 0x1d19 to 0x1101, 0x1d19 to 0x1102, 0x1d19 to 0x1103,
            0x1d19 to 0x1104, 0x1f4d to 0xa803, 0x1f4d to 0xb803, 0x1f4d to 0xc803, 0x1f4d to 0xd286, 0x1f4d to 0xd803,
        ),
    ),
    AIRSPY(1, "Airspy", listOf(24_000_000L..1_800_000_000L), true, setOf(0x1d50 to 0x60a1)),
    AIRSPY_HF(2, "Airspy HF+", listOf(0L..31_000_000L, 60_000_000L..260_000_000L), false, setOf(0x03eb to 0x800c)),
    HACKRF(3, "HackRF", listOf(1_000_000L..6_000_000_000L), false, setOf(0x1d50 to 0x6089, 0x1d50 to 0x604b, 0x1d50 to 0xcc15));

    fun covers(hz: Long) = coverage.any { hz in it }

    /** A gain setting (tenths) as shown on screen: dB, or the Airspy's 0–21 "linearity" levels. */
    fun formatGain(tenths: Int) = if (tenths < 0) "Auto" else if (gainInLevels) "Level ${tenths / 10}" else "%.1f dB".format(tenths / 10.0)

    /** What it covers, for a message when the dial is outside it. */
    fun coverageText() = coverage.joinToString(" and ") { "${mhz(it.first)}–${mhz(it.last)}" }

    private fun mhz(hz: Long) = if (hz >= 1_000_000_000L) "%.1f GHz".format(hz / 1e9) else if (hz < 1_000_000) "%.1f MHz".format(hz / 1e6) else "${hz / 1_000_000} MHz"

    companion object {
        /** SDRplay (RSP1A, RSPdx, RSPduo…): its driver is closed and has no Android version, so the tablet can't run it. */
        const val SDRPLAY_VENDOR = 0x1df7
    }
}

/**
 * An SDR plugged into the tablet (through a USB hub, beside the radio). Android opens the USB device and the matching
 * driver built into the app runs it from that file descriptor.
 *
 * Not thread-safe: use one instance from one thread.
 */
class UsbSdr private constructor(private val conn: UsbDeviceConnection, private var handle: Long, val kind: SdrKind) {
    /** Samples a second (the RTL-SDR uses what it was asked for; the others pick a rate of their own). */
    val rate: Int = nativeRate(handle)
    val fftSize: Int = nativeFftSize(handle)
    val binHz: Float = rate.toFloat() / fftSize
    /** FFTs averaged into each spectrum line: about 0.1 s of samples, ~10 lines a second. */
    val fftsPerFrame: Int = maxOf(1, Math.round(rate * 0.096 / fftSize).toInt())

    /** Retunes; samples from the next ~0.1 s are best skipped. */
    fun tune(hz: Int) { check(nativeTune(handle, hz) >= 0) { "The ${kind.label} didn't accept $hz Hz" } }

    /** [tenths] < 0 is automatic gain (the HackRF has none and uses a middle setting). */
    fun setGain(tenths: Int) { nativeSetGain(handle, tenths) }

    /** The manual gain steps, in tenths of a dB (tenths of a level for the Airspy). */
    fun gains(): IntArray = nativeGains(handle)

    /** True while an RTL-SDR samples HF directly (a V3 below 24 MHz): the tuner, and its gain, aren't used. */
    fun directSampling(): Boolean = nativeDirectSampling(handle) != 0

    /**
     * Blocks until [fftsPerFrame] FFTs' worth of fresh samples have arrived and writes their average power into [out]
     * (size [fftSize], lowest frequency first, the tuned frequency in the middle). [stats] gets
     * {samples at full scale, samples, peak 0..128}.
     */
    fun readSpectrum(out: FloatArray, stats: IntArray) {
        val r = nativeReadSpectrum(handle, out, fftsPerFrame, stats)
        if (r < 0) throw IOException("The ${kind.label} stopped sending (error $r). Is it still plugged in?")
    }

    fun close() {
        if (handle != 0L) { nativeClose(handle); handle = 0 }
        conn.close()
    }

    companion object {
        init { System.loadLibrary("catsdr") }

        /** The RTL-SDR's rate: 1.024 MS/s gives 125 Hz bins from an 8192-point FFT. */
        const val RTL_RATE = 1_024_000

        /** The first SDR the app can run that is plugged into the tablet, and its kind. */
        fun find(ctx: Context): Pair<UsbDevice, SdrKind>? {
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            for (d in um.deviceList.values) {
                val kind = SdrKind.entries.firstOrNull { (d.vendorId to d.productId) in it.ids } ?: continue
                return d to kind
            }
            return null
        }

        /** True when an SDRplay is plugged in (which only the PC can run). */
        fun sdrplayPresent(ctx: Context): Boolean {
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            return um.deviceList.values.any { it.vendorId == SdrKind.SDRPLAY_VENDOR }
        }

        /** Asks for permission if needed (blocks on the dialog, so call off the main thread) and starts the SDR at [startHz]. */
        fun open(ctx: Context, startHz: Int, gainTenths: Int, ppm: Int): UsbSdr {
            val (device, kind) = find(ctx) ?: throw IOException("No supported SDR found on the tablet's USB port.")
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!um.hasPermission(device) && !UsbLink.askPermission(ctx, um, device)) throw IOException("USB permission for the ${kind.label} was refused.")
            val conn = um.openDevice(device) ?: throw IOException("Couldn't open the ${kind.label}.")
            val h = nativeOpen(kind.code, conn.fileDescriptor, RTL_RATE, startHz, gainTenths, ppm, if (kind == SdrKind.RTL) 8192 else 0)
            if (h == 0L) { conn.close(); throw IOException("The ${kind.label} driver couldn't start it (error ${nativeLastError()}). Unplug it and plug it back in.") }
            return UsbSdr(conn, h, kind)
        }

        @JvmStatic private external fun nativeOpen(kind: Int, fd: Int, rate: Int, hz: Int, gainTenths: Int, ppm: Int, fftSize: Int): Long
        @JvmStatic private external fun nativeRate(h: Long): Int
        @JvmStatic private external fun nativeFftSize(h: Long): Int
        @JvmStatic private external fun nativeTune(h: Long, hz: Int): Int
        @JvmStatic private external fun nativeSetGain(h: Long, gainTenths: Int): Int
        @JvmStatic private external fun nativeGains(h: Long): IntArray
        @JvmStatic private external fun nativeReadSpectrum(h: Long, out: FloatArray, ffts: Int, stats: IntArray): Int
        @JvmStatic private external fun nativeClose(h: Long)
        @JvmStatic private external fun nativeLastError(): Int
        @JvmStatic private external fun nativeDirectSampling(h: Long): Int
    }
}

/**
 * Turns the tablet's SDR into the same spectrum lines the PC Panadapter sends: the SDR sits [OFFSET] Hz below the dial
 * (keeping its centre spike away from the dial), retunes when the dial wanders out of [RETUNE], and each line covers
 * ±[HALF_SPAN] Hz around the dial in bins of about 125 Hz, about 10 a second.
 */
object TabletSdr {
    const val HALF_SPAN = 100_000
    const val OFFSET = 250_000
    val RETUNE = 150_000..350_000

    /** Where to tune the SDR for a radio dial of [dialHz] (never below [lowest]). */
    fun centreFor(dialHz: Long, lowest: Long = 500_000L): Int = maxOf(dialHz - OFFSET, lowest).toInt()

    /** Whether the SDR tuned to [sdrCentre] can stay put for [dialHz] (also when retuning wouldn't change anything). */
    fun inRange(dialHz: Long, sdrCentre: Int, lowest: Long = 500_000L) =
        (dialHz - sdrCentre) in RETUNE.first.toLong()..RETUNE.last.toLong() || centreFor(dialHz, lowest) == sdrCentre

    /**
     * The ±[HALF_SPAN] Hz around [dialHz] from a spectrum [power] (bins of [binHz], centred on [sdrCentre]), in dB; null if
     * it isn't all there.
     */
    fun frame(power: FloatArray, binHz: Float, sdrCentre: Int, dialHz: Long, overload: Boolean): SpecFrame? {
        val half = (HALF_SPAN / binHz).toInt()
        val centreBin = power.size / 2 + Math.round((dialHz - sdrCentre) / binHz)
        val lo = centreBin - half
        val hi = centreBin + half
        if (lo < 0 || hi > power.size) return null
        val db = FloatArray(hi - lo) { 10f * kotlin.math.log10(power[lo + it] + 1e-12f) }
        return SpecFrame(dialHz, binHz, db, overload)
    }
}

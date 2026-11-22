package net.kanenet.catexplorer.audio

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import net.kanenet.catexplorer.rig.UsbLink
import java.io.IOException

/**
 * An RTL-SDR plugged into the tablet (through a USB hub, beside the radio). Android opens the USB device and the
 * RTL-SDR Blog driver, built into the app (src/main/cpp), runs it from that file descriptor.
 *
 * Not thread-safe: use one instance from one thread.
 */
class RtlSdr private constructor(private val conn: UsbDeviceConnection, private var handle: Long, val fftSize: Int) {

    /** Retunes; samples from the next ~0.1 s are best skipped. */
    fun tune(hz: Int) { check(nativeTune(handle, hz) >= 0) { "The RTL-SDR didn't accept $hz Hz" } }

    /** [tenthsDb] < 0 is automatic gain. Only matters above 24 MHz: below that the dongle samples directly, with no amplifier. */
    fun setGain(tenthsDb: Int) { nativeSetGain(handle, tenthsDb) }

    fun gains(): IntArray = nativeGains(handle)

    /**
     * Blocks until [ffts] FFTs' worth of samples have arrived and writes their average power into [out] (size [fftSize],
     * lowest frequency first, the tuned frequency in the middle). [stats] gets {samples at full scale, samples, peak 0..128}.
     */
    fun readSpectrum(out: FloatArray, ffts: Int, stats: IntArray) {
        val r = nativeReadSpectrum(handle, out, ffts, stats)
        if (r < 0) throw IOException("The RTL-SDR stopped sending (error $r). Is it still plugged in?")
    }

    fun close() {
        if (handle != 0L) { nativeClose(handle); handle = 0 }
        conn.close()
    }

    companion object {
        init { System.loadLibrary("catsdr") }

        /** USB IDs of RTL2832U dongles (the same list the driver knows; the RTL-SDR Blog V3 and V4 are 0bda:2838). */
        private val KNOWN = setOf(
            0x0bda to 0x2832, 0x0bda to 0x2838, 0x0413 to 0x6680, 0x0413 to 0x6f0f, 0x0458 to 0x707f, 0x0ccd to 0x00a9,
            0x0ccd to 0x00b3, 0x0ccd to 0x00b4, 0x0ccd to 0x00b5, 0x0ccd to 0x00b7, 0x0ccd to 0x00b8, 0x0ccd to 0x00b9,
            0x0ccd to 0x00c0, 0x0ccd to 0x00c6, 0x0ccd to 0x00d3, 0x0ccd to 0x00d7, 0x0ccd to 0x00e0, 0x1554 to 0x5020,
            0x15f4 to 0x0131, 0x15f4 to 0x0133, 0x185b to 0x0620, 0x185b to 0x0650, 0x185b to 0x0680, 0x1b80 to 0xd393,
            0x1b80 to 0xd394, 0x1b80 to 0xd395, 0x1b80 to 0xd397, 0x1b80 to 0xd398, 0x1b80 to 0xd39d, 0x1b80 to 0xd3a4,
            0x1b80 to 0xd3a8, 0x1b80 to 0xd3af, 0x1b80 to 0xd3b0, 0x1d19 to 0x1101, 0x1d19 to 0x1102, 0x1d19 to 0x1103,
            0x1d19 to 0x1104, 0x1f4d to 0xa803, 0x1f4d to 0xb803, 0x1f4d to 0xc803, 0x1f4d to 0xd286, 0x1f4d to 0xd803,
        )

        fun find(ctx: Context): UsbDevice? {
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            return um.deviceList.values.firstOrNull { (it.vendorId to it.productId) in KNOWN }
        }

        /** Asks for permission if needed (blocks on the dialog, so call off the main thread) and opens the dongle. */
        fun open(ctx: Context, rate: Int, startHz: Int, gainTenths: Int, ppm: Int, fftSize: Int): RtlSdr {
            val device = find(ctx) ?: throw IOException("No RTL-SDR found on the tablet's USB port.")
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!um.hasPermission(device) && !UsbLink.askPermission(ctx, um, device)) throw IOException("USB permission for the RTL-SDR was refused.")
            val conn = um.openDevice(device) ?: throw IOException("Couldn't open the RTL-SDR.")
            val h = nativeOpen(conn.fileDescriptor, rate, startHz, gainTenths, ppm, fftSize)
            if (h == 0L) { conn.close(); throw IOException("The RTL-SDR driver couldn't start it (error ${nativeLastError()}). Unplug it and plug it back in.") }
            return RtlSdr(conn, h, fftSize)
        }

        @JvmStatic private external fun nativeOpen(fd: Int, rate: Int, hz: Int, gainTenths: Int, ppm: Int, fftSize: Int): Long
        @JvmStatic private external fun nativeTune(h: Long, hz: Int): Int
        @JvmStatic private external fun nativeSetGain(h: Long, gainTenths: Int): Int
        @JvmStatic private external fun nativeGains(h: Long): IntArray
        @JvmStatic private external fun nativeReadSpectrum(h: Long, out: FloatArray, ffts: Int, stats: IntArray): Int
        @JvmStatic private external fun nativeClose(h: Long)
        @JvmStatic private external fun nativeLastError(): Int
    }
}

/**
 * Turns the tablet's RTL-SDR into the same spectrum lines the PC Panadapter sends: the SDR sits [OFFSET] Hz below the dial
 * (keeping its centre spike away from the dial), retunes when the dial wanders out of [RETUNE], and each line covers
 * ±[HALF_SPAN] Hz around the dial in 125 Hz bins, about 10 a second.
 */
object TabletSdr {
    const val RATE = 1_024_000
    const val FFT = 8192
    const val FFTS_PER_FRAME = 12
    const val HALF_SPAN = 100_000
    const val OFFSET = 250_000
    val RETUNE = 150_000..350_000
    const val BIN_HZ = RATE.toFloat() / FFT

    /** Where to tune the SDR for a radio dial of [dialHz]. */
    fun centreFor(dialHz: Long): Int = maxOf(dialHz - OFFSET, 500_000L).toInt()

    /** Whether the SDR tuned to [sdrCentre] can stay put for [dialHz]. */
    fun inRange(dialHz: Long, sdrCentre: Int) = (dialHz - sdrCentre) in RETUNE.first.toLong()..RETUNE.last.toLong()

    /** The ±[HALF_SPAN] Hz around [dialHz] from a spectrum [power] centred on [sdrCentre], in dB; null if it isn't all there. */
    fun frame(power: FloatArray, sdrCentre: Int, dialHz: Long, overload: Boolean): SpecFrame? {
        val half = (HALF_SPAN / BIN_HZ).toInt()
        val centreBin = power.size / 2 + Math.round((dialHz - sdrCentre) / BIN_HZ)
        val lo = centreBin - half
        val hi = centreBin + half
        if (lo < 0 || hi > power.size) return null
        val db = FloatArray(hi - lo) { 10f * kotlin.math.log10(power[lo + it] + 1e-6f) }
        return SpecFrame(dialHz, BIN_HZ, db, overload)
    }
}

// Copyright (C) 2026 David Kane (W6MZT)
// SPDX-License-Identifier: GPL-3.0-or-later
package net.kanenet.catexplorer.rig

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the radio on the tablet's USB port, gets permission to use it, works out which radio it is, and returns a working
 * client: [UsbCatClient] for an FT-891 (text CAT, "ID;" answers "ID0650;"), [Ft857Client] for an FT-857D (five-byte binary
 * CAT, which has no ID command, so it's recognised by a sensible answer to "read frequency and mode").
 */
object UsbLink {
    private const val ACTION = "net.kanenet.catexplorer.USB_PERMISSION"

    /** CAT rates the two radios offer: the FT-891 4800/9600/19200/38400, the FT-857D 4800/9600/38400. */
    private val RATES = listOf(4800, 9600, 38400, 19200)

    /**
     * Blocks (permission dialog, probing) — call off the main thread. Tries [baud] first, then the other CAT rates, on every
     * serial port the cable shows (the FT-891's CP2105 bridge has two). At each rate it asks the FT-891 way, then the FT-857D
     * way. Handshake lines are varied too, since some cables need them high.
     */
    fun open(ctx: Context, baud: Int): RigClient {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull()
            ?: throw IOException("No USB serial device found. Check the OTG cable and that the radio is switched on.")
        val device = driver.device
        if (!um.hasPermission(device) && !askPermission(ctx, um, device)) throw IOException("USB permission was refused.")

        fun openPort(portIndex: Int, rate: Int, stopBits: Int): UsbSerialPort {
            val conn = um.openDevice(device) ?: throw IOException("Couldn't open the USB device.")
            val port = driver.ports[portIndex]
            try {
                port.open(conn)
                port.setParameters(rate, 8, stopBits, UsbSerialPort.PARITY_NONE)
            } catch (e: Exception) {
                runCatching { port.close() }
                throw IOException("Couldn't open serial port ${portIndex + 1}: ${e.message}")
            }
            return port
        }

        /** FT-891: text CAT, 8N1. A full check (three tries, three line states) at the chosen rate, a quick one elsewhere. */
        fun tryText(portIndex: Int, rate: Int, full: Boolean): RigClient? {
            val lines = if (full) listOf(true to false, false to false, true to true) else listOf(true to false)
            for ((rts, dtr) in lines) {
                val client = UsbCatClient(openPort(portIndex, rate, UsbSerialPort.STOPBITS_1)).also { it.setLines(rts, dtr) }
                if (runCatching { client.probe(if (full) 3 else 1) }.getOrNull() != null) return client
                client.abandon()
            }
            return null
        }

        /** FT-857D: binary CAT, 8N2 (the radio wants two stop bits). Some CAT cables are powered from the handshake lines. */
        fun tryBinary(portIndex: Int, rate: Int): RigClient? {
            for ((rts, dtr) in listOf(true to true, false to false)) {
                val port = openPort(portIndex, rate, UsbSerialPort.STOPBITS_2)
                runCatching { port.setRTS(rts) }; runCatching { port.setDTR(dtr) }
                val link = UsbByteLink(port)
                val client = Ft857Client(link)
                if (runCatching { client.probe() }.getOrNull() != null) return client
                link.close()   // not client.close(): that would send PTT-off bytes to whatever is (not) listening
            }
            return null
        }

        for (rate in listOf(baud) + RATES.filter { it != baud }) {
            for (portIndex in driver.ports.indices) {
                tryText(portIndex, rate, full = rate == baud)?.let { return it }
                tryBinary(portIndex, rate)?.let { return it }
            }
        }
        throw IOException(
            "No radio answered at any CAT rate (tried 4800, 9600, 19200 and 38400 baud). Check the radio is on, the cable carries " +
                "data, and the radio's CAT RATE menu setting.",
        )
    }

    /** Shows Android's USB permission dialog for [device] and waits for the answer (up to a minute). */
    fun askPermission(ctx: Context, um: UsbManager, device: android.hardware.usb.UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        val granted = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == ACTION) {
                    granted.set(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    latch.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            um.requestPermission(device, pi)
            latch.await(60, TimeUnit.SECONDS)
        } finally {
            ctx.unregisterReceiver(receiver)
        }
        return granted.get()
    }
}

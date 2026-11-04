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

/** Finds the radio on the tablet's USB port, gets permission to use it, and returns a working [UsbCatClient]. */
object UsbLink {
    private const val ACTION = "net.kanenet.catexplorer.USB_PERMISSION"

    /**
     * Blocks (permission dialog, port probing) — call off the main thread. The FT-891's CP2105 bridge shows two serial ports;
     * the first one that answers "ID;" at [baud] is used. Handshake lines stay low unless the radio is silent without them.
     */
    fun open(ctx: Context, baud: Int): UsbCatClient {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull()
            ?: throw IOException("No USB serial device found. Check the OTG cable and that the radio is switched on.")
        val device = driver.device
        if (!um.hasPermission(device) && !askPermission(ctx, um, device)) throw IOException("USB permission was refused.")

        val tried = StringBuilder()
        for (portIndex in driver.ports.indices) {
            for ((rts, dtr) in listOf(true to false, false to false, true to true)) {
                val conn = um.openDevice(device) ?: throw IOException("Couldn't open the USB device.")
                val port = driver.ports[portIndex]
                val client = try {
                    port.open(conn)
                    port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    UsbCatClient(port).also { it.setLines(rts, dtr) }
                } catch (e: Exception) {
                    runCatching { port.close() }
                    throw IOException("Couldn't open serial port ${portIndex + 1}: ${e.message}")
                }
                if (runCatching { client.probe() }.getOrNull() != null) return client
                client.close()
                tried.append(" port ${portIndex + 1}")
            }
        }
        throw IOException("The radio didn't answer at $baud baud (tried$tried). Check CAT RATE (menu 05-06) on the radio.")
    }

    private fun askPermission(ctx: Context, um: UsbManager, device: android.hardware.usb.UsbDevice): Boolean {
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

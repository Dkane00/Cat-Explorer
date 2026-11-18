package net.kanenet.catexplorer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

const val AUDIO_DEFAULT_PORT = 7366

/**
 * The radio's receive audio, streamed from `pc-panadapter/cat-panadapter-server.py` on the FLRig PC.
 * The stream starts with one text line ("CATAUDIO 1 rate=12000 ch=1 fmt=s16le") and is then raw 16-bit mono samples.
 */
class AudioLink(private val host: String, private val port: Int) : Closeable {
    private val socket = Socket()
    var rate = 12000
        private set
    private var track: AudioTrack? = null

    /** Connects and reads the header. Throws with a message fit for the screen if there is no panadapter there. */
    fun open() {
        try {
            socket.connect(InetSocketAddress(host, port), 3000)
        } catch (e: IOException) {
            throw IOException("No Cat Explorer Panadapter at $host:$port — is it running on the FLRig PC?")
        }
        socket.soTimeout = 5000
        socket.tcpNoDelay = true
        val header = readLine(socket.getInputStream())
        if (!header.startsWith("CATAUDIO 1")) throw IOException("$host:$port isn't the Cat Explorer Panadapter")
        Regex("rate=(\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull()?.let { rate = it }
    }

    private fun readLine(input: java.io.InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 200) {
            val b = input.read()
            if (b < 0) throw IOException("The Panadapter closed the connection")
            if (b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    /**
     * Reads audio until the connection drops or [keepGoing] turns false, handing each block to [onSamples].
     * While [listen] is true the audio is also played on the tablet.
     */
    fun pump(keepGoing: () -> Boolean, listen: () -> Boolean, onSamples: (ShortArray, Int) -> Unit) {
        val input = BufferedInputStream(socket.getInputStream(), 8192)
        val bytes = ByteArray(960)
        val shorts = ShortArray(bytes.size / 2 + 1)
        var odd = -1   // a leftover low byte when a read ends half-way through a sample
        while (keepGoing()) {
            val n = input.read(bytes)
            if (n < 0) throw IOException("The Panadapter stopped sending")
            var count = 0
            var i = 0
            if (odd >= 0 && n > 0) { shorts[count++] = ((bytes[0].toInt() shl 8) or odd).toShort(); i = 1; odd = -1 }
            while (i + 1 < n) { shorts[count++] = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort(); i += 2 }
            if (i < n) odd = bytes[i].toInt() and 0xFF
            if (count == 0) continue
            onSamples(shorts, count)
            play(listen(), shorts, count)
        }
    }

    private fun play(on: Boolean, s: ShortArray, n: Int) {
        if (!on) { track?.let { it.pause(); it.flush() }; return }
        val t = track ?: run {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, rate / 2))   // ~250 ms: enough to ride out Wi-Fi hiccups
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { track = it }
        }
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        // never wait on the speaker: if its buffer is full the block is dropped, so the waterfall keeps up
        t.write(s, 0, n, AudioTrack.WRITE_NON_BLOCKING)
    }

    override fun close() {
        runCatching { socket.close() }
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}

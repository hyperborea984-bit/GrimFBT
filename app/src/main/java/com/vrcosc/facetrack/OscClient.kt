package com.vrcosc.facetrack

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Minimal OSC 1.0 client over UDP, built from scratch so we don't need an
 * external OSC dependency (keeps the Gradle dependency graph small).
 *
 * VRChat listens for avatar parameter OSC messages on 127.0.0.1:9000 by
 * default, at addresses of the form:
 *   /avatar/parameters/<ParameterName>
 * with a single Float32, Int32, or Bool argument.
 *
 * Spec reference: OSC 1.0 (http://opensoundcontrol.org/spec-1_0)
 */
class OscClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 9000
) {
    private val socket = DatagramSocket()
    private val address = InetAddress.getByName(host)

    // Single background thread + queue so UI/analysis threads never block on I/O.
    private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true

    init {
        thread(isDaemon = true, name = "osc-sender") {
            while (running) {
                val packetBytes = queue.take()
                try {
                    val packet = DatagramPacket(packetBytes, packetBytes.size, address, port)
                    socket.send(packet)
                } catch (_: Exception) {
                    // Drop silently; next frame will resend fresher data anyway.
                }
            }
        }
    }

    fun sendFloat(oscAddress: String, value: Float) {
        queue.offer(buildMessage(oscAddress, floatArg = value))
    }

    fun sendInt(oscAddress: String, value: Int) {
        queue.offer(buildMessage(oscAddress, intArg = value))
    }

    fun sendBool(oscAddress: String, value: Boolean) {
        queue.offer(buildMessage(oscAddress, boolArg = value))
    }

    fun close() {
        running = false
        socket.close()
    }

    // --- OSC wire format ---------------------------------------------------

    private fun buildMessage(
        oscAddress: String,
        floatArg: Float? = null,
        intArg: Int? = null,
        boolArg: Boolean? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeOscString(out, oscAddress)

        val typeTag = StringBuilder(",")
        when {
            floatArg != null -> typeTag.append('f')
            intArg != null -> typeTag.append('i')
            boolArg != null -> typeTag.append(if (boolArg) 'T' else 'F')
        }
        writeOscString(out, typeTag.toString())

        // T/F type tags carry no argument bytes per the OSC 1.0 spec.
        if (floatArg != null) writeFloat32(out, floatArg)
        if (intArg != null) writeInt32(out, intArg)

        return out.toByteArray()
    }

    /** OSC strings are null-terminated and padded to a multiple of 4 bytes. */
    private fun writeOscString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        out.write(bytes)
        val padding = 4 - (bytes.size % 4).let { if (it == 0) 4 else it }
        repeat(padding) { out.write(0) }
    }

    private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeFloat32(out: ByteArrayOutputStream, value: Float) {
        writeInt32(out, java.lang.Float.floatToIntBits(value))
    }
}

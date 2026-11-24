package com.yegorkapomidorka.headswap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

object ObdReader {
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile var socPercent: Int = -1
    @Volatile var rangeKm: Int = -1
    @Volatile var connected: Boolean = false
    @Volatile private var running = false

    fun start(onUpdate: (soc: Int, range: Int) -> Unit) {
        if (running) return
        running = true
        Thread({
            while (running) {
                try {
                    connectAndPoll(onUpdate)
                } catch (_: Throwable) {
                    connected = false
                }
                Thread.sleep(5000)
            }
        }, "id4-obd").start()
    }

    @SuppressLint("MissingPermission")
    private fun connectAndPoll(onUpdate: (Int, Int) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return
        val dev = adapter.bondedDevices.firstOrNull { d ->
            val n = (d.name ?: "").uppercase()
            n.contains("OBD") || n.contains("ELM") || n.contains("VGATE") || n.contains("V-LINK") || n.contains("KONNWEI")
        } ?: return

        val sock: BluetoothSocket = dev.createRfcommSocketToServiceRecord(SPP_UUID)
        sock.connect()
        connected = true
        val inp = sock.inputStream
        val out = sock.outputStream

        // تهيئة ELM327 لمنصة VW ID.4 (MEB)
        sendCmd(out, inp, "ATZ")
        sendCmd(out, inp, "ATE0")
        sendCmd(out, inp, "ATL0")
        sendCmd(out, inp, "ATSP6")
        sendCmd(out, inp, "ATSH 7E5")

        while (running && sock.isConnected) {
            // قراءة نسبة البطارية المعروضة (Display SoC) لسيارات VW ID.4
            val resp = sendCmd(out, inp, "22 02 8C").replace(" ", "").uppercase()
            val idx = resp.indexOf("62028C")
            if (idx >= 0 && resp.length >= idx + 8) {
                val raw = resp.substring(idx + 6, idx + 8).toIntOrNull(16)
                if (raw != null) {
                    val soc = (raw * 100 / 250).coerceIn(0, 100)
                    val range = (soc * 4.3).toInt() // مدى ID.4 Crozz Pro التقريبي
                    socPercent = soc
                    rangeKm = range
                    onUpdate(soc, range)
                }
            }
            Thread.sleep(4000)
        }
        sock.close()
    }

    private fun sendCmd(out: OutputStream, inp: InputStream, cmd: String): String {
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) {
                val c = inp.read().toChar()
                if (c == '>') break
                sb.append(c)
            } else {
                Thread.sleep(20)
            }
        }
        return sb.toString()
    }
}

package com.trackpad.remote

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

class TrackpadClient(
    private val onStatusChange: (Boolean, String) -> Unit
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private var isConnected = false
    private var workerJob: Job? = null

    fun connect(host: String, port: Int) {
        scope.launch {
            try {
                disconnectInternal()
                withContext(Dispatchers.Main) {
                    onStatusChange(false, "Connecting to $host:$port...")
                }

                val newSocket = Socket()
                // Connect with 3-second timeout
                newSocket.connect(InetSocketAddress(host, port), 3000)
                // Crucial for zero-latency: disable Nagle's algorithm
                newSocket.tcpNoDelay = true
                newSocket.keepAlive = true

                socket = newSocket
                writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream()))
                isConnected = true

                withContext(Dispatchers.Main) {
                    onStatusChange(true, "🟢 Connected to $host")
                }

                startWorker()
            } catch (e: Exception) {
                Log.e("TrackpadClient", "Connection error", e)
                withContext(Dispatchers.Main) {
                    onStatusChange(false, "🔴 Failed: ${e.localizedMessage ?: "Unknown error"}")
                }
                disconnectInternal()
            }
        }
    }

    private fun startWorker() {
        workerJob?.cancel()
        workerJob = scope.launch {
            try {
                for (cmd in sendChannel) {
                    writer?.write("$cmd\n")
                    writer?.flush()
                }
            } catch (e: Exception) {
                Log.e("TrackpadClient", "Worker send error", e)
                withContext(Dispatchers.Main) {
                    onStatusChange(false, "🔴 Disconnected")
                }
                disconnectInternal()
            }
        }
    }

    fun send(command: String) {
        if (!isConnected) return
        sendChannel.trySend(command)
    }

    fun sendMove(dx: Float, dy: Float) {
        send("MOVE $dx $dy")
    }

    fun sendClick(button: String) {
        if (button.equals("right", ignoreCase = true)) {
            send("R_TAP")
        } else {
            send("TAP")
        }
    }

    fun sendMouseDown(button: String) {
        send("DOWN $button")
    }

    fun sendMouseUp(button: String) {
        send("UP $button")
    }

    fun sendScroll(dx: Float, dy: Float) {
        send("SCROLL $dx $dy")
    }

    fun sendText(text: String) {
        send("TEXT $text")
    }

    fun sendKey(key: String) {
        send("KEY $key")
    }

    fun sendNextSlide() {
        send("NEXT_SLIDE")
    }

    fun sendPrevSlide() {
        send("PREV_SLIDE")
    }

    fun sendVolumeUp() {
        send("VOL_UP")
    }

    fun sendVolumeDown() {
        send("VOL_DOWN")
    }

    fun disconnect() {
        scope.launch {
            disconnectInternal()
            withContext(Dispatchers.Main) {
                onStatusChange(false, "⚪ Disconnected")
            }
        }
    }

    private fun disconnectInternal() {
        isConnected = false
        try {
            writer?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        writer = null
    }

    fun isConnected(): Boolean = isConnected
}

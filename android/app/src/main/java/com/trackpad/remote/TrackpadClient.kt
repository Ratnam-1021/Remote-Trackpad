package com.trackpad.remote

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
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
    private val sendChannel = Channel<String>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var isConnected = false
    private var workerJob: Job? = null

    // Ultra-low latency Immediate-Dispatch Engine with sub-pixel accumulator matching Bluetooth HID
    private var pendingMoveX = 0f
    private var pendingMoveY = 0f
    private val moveLock = Any()
    private var lastSendTimeMs = 0L

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
                // Crucial for zero-latency: disable Nagle's algorithm & enable low delay
                newSocket.tcpNoDelay = true
                newSocket.keepAlive = true
                try {
                    newSocket.trafficClass = 0x10 // IPTOS_LOWDELAY
                    newSocket.setPerformancePreferences(0, 1, 0)
                } catch (_: Exception) {}

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
                    val w = writer ?: break
                    w.write("$cmd\n")
                    w.flush()
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
        if (!isConnected) return
        var toSendX = 0f
        var toSendY = 0f
        synchronized(moveLock) {
            pendingMoveX += dx
            pendingMoveY += dy
            val now = SystemClock.uptimeMillis()
            // Throttle to 4ms (~250Hz max) to prevent network congestion while matching high refresh screens
            if (now - lastSendTimeMs < 4L) {
                return
            }
            if (pendingMoveX != 0f || pendingMoveY != 0f) {
                toSendX = pendingMoveX
                toSendY = pendingMoveY
                pendingMoveX = 0f
                pendingMoveY = 0f
                lastSendTimeMs = now
            }
        }
        if (toSendX != 0f || toSendY != 0f) {
            send("MOVE $toSendX $toSendY")
        }
    }

    fun flushRemainder() {
        if (!isConnected) return
        var toSendX = 0f
        var toSendY = 0f
        synchronized(moveLock) {
            if (pendingMoveX != 0f || pendingMoveY != 0f) {
                toSendX = pendingMoveX
                toSendY = pendingMoveY
                pendingMoveX = 0f
                pendingMoveY = 0f
                lastSendTimeMs = SystemClock.uptimeMillis()
            }
        }
        if (toSendX != 0f || toSendY != 0f) {
            send("MOVE $toSendX $toSendY")
        }
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

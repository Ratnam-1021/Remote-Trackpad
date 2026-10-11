package com.trackpad.remote

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

class BluetoothHidManager(
    private val context: Context,
    private val onConnectionStateChanged: (connected: Boolean, deviceName: String?) -> Unit,
    private val onSupportedState: (supported: Boolean, message: String) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothHidManager"
        private const val REPORT_ID_MOUSE = 1
        private const val REPORT_ID_KEYBOARD = 2
        private const val REPORT_ID_CONSUMER = 3

        // Standard USB HID Report Descriptor (Mouse + Keyboard + Consumer)
        val HID_REPORT_DESCRIPTOR = byteArrayOf(
            // Mouse (Report ID 1)
            0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
            0xa1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x85.toByte(), 0x01.toByte(), //   REPORT_ID (1)
            0x09.toByte(), 0x01.toByte(), //   USAGE (Pointer)
            0xa1.toByte(), 0x00.toByte(), //   COLLECTION (Physical)
            0x05.toByte(), 0x09.toByte(), //     USAGE_PAGE (Button)
            0x19.toByte(), 0x01.toByte(), //     USAGE_MINIMUM (Button 1)
            0x29.toByte(), 0x03.toByte(), //     USAGE_MAXIMUM (Button 3)
            0x15.toByte(), 0x00.toByte(), //     LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //     LOGICAL_MAXIMUM (1)
            0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
            0x75.toByte(), 0x01.toByte(), //     REPORT_SIZE (1)
            0x81.toByte(), 0x02.toByte(), //     INPUT (Data,Var,Abs)
            0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
            0x75.toByte(), 0x05.toByte(), //     REPORT_SIZE (5)
            0x81.toByte(), 0x03.toByte(), //     INPUT (Cnst,Var,Abs)
            0x05.toByte(), 0x01.toByte(), //     USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     USAGE (X)
            0x09.toByte(), 0x31.toByte(), //     USAGE (Y)
            0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
            0x25.toByte(), 0x7f.toByte(), //     LOGICAL_MAXIMUM (127)
            0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
            0x95.toByte(), 0x02.toByte(), //     REPORT_COUNT (2)
            0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
            0x09.toByte(), 0x38.toByte(), //     USAGE (Wheel)
            0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
            0x25.toByte(), 0x7f.toByte(), //     LOGICAL_MAXIMUM (127)
            0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
            0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
            0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel)
            0xc0.toByte(),               //   END_COLLECTION
            0xc0.toByte(),               // END_COLLECTION

            // Keyboard (Report ID 2)
            0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // USAGE (Keyboard)
            0xa1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x85.toByte(), 0x02.toByte(), //   REPORT_ID (2)
            0x05.toByte(), 0x07.toByte(), //   USAGE_PAGE (Keyboard)
            0x19.toByte(), 0xe0.toByte(), //   USAGE_MINIMUM (LeftControl)
            0x29.toByte(), 0xe7.toByte(), //   USAGE_MAXIMUM (Right GUI)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //   LOGICAL_MAXIMUM (1)
            0x75.toByte(), 0x01.toByte(), //   REPORT_SIZE (1)
            0x95.toByte(), 0x08.toByte(), //   REPORT_COUNT (8)
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data,Var,Abs)
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8)
            0x81.toByte(), 0x01.toByte(), //   INPUT (Cnst,Ary,Abs)
            0x95.toByte(), 0x06.toByte(), //   REPORT_COUNT (6)
            0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x65.toByte(), //   LOGICAL_MAXIMUM (101)
            0x05.toByte(), 0x07.toByte(), //   USAGE_PAGE (Keyboard)
            0x19.toByte(), 0x00.toByte(), //   USAGE_MINIMUM (Reserved)
            0x29.toByte(), 0x65.toByte(), //   USAGE_MAXIMUM (Keyboard Application)
            0x81.toByte(), 0x00.toByte(), //   INPUT (Data,Ary,Abs)
            0xc0.toByte(),               // END_COLLECTION

            // Consumer / Media Controls (Report ID 3)
            0x05.toByte(), 0x0c.toByte(), // USAGE_PAGE (Consumer Devices)
            0x09.toByte(), 0x01.toByte(), // USAGE (Consumer Control)
            0xa1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x85.toByte(), 0x03.toByte(), //   REPORT_ID (3)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //   LOGICAL_MAXIMUM (1)
            0x75.toByte(), 0x01.toByte(), //   REPORT_SIZE (1)
            0x95.toByte(), 0x08.toByte(), //   REPORT_COUNT (8)
            0x09.toByte(), 0xe9.toByte(), //   Volume Up
            0x09.toByte(), 0xea.toByte(), //   Volume Down
            0x09.toByte(), 0xe2.toByte(), //   Mute
            0x09.toByte(), 0xcd.toByte(), //   Play/Pause
            0x09.toByte(), 0xb5.toByte(), //   Next Track
            0x09.toByte(), 0xb6.toByte(), //   Prev Track
            0x09.toByte(), 0xb7.toByte(), //   Stop
            0x09.toByte(), 0x00.toByte(), //   Padding
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data,Var,Abs)
            0xc0.toByte()                // END_COLLECTION
        )
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var isRegistered = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State of mouse buttons (bit 0 = left, bit 1 = right, bit 2 = middle)
    private var currentButtons: Byte = 0

    // High-performance 125Hz HID report accumulator (eliminates Binder IPC bottleneck)
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingScroll = 0f
    private val movementLock = Any()
    private var dispatcherJob: Job? = null

    private fun startDispatcherLoop() {
        dispatcherJob?.cancel()
        dispatcherJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var sendX = 0
                var sendY = 0
                var sendWheel = 0
                val target = connectedDevice
                val dev = hidDevice

                if (target != null && dev != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    synchronized(movementLock) {
                        if (pendingDx != 0f || pendingDy != 0f || pendingScroll != 0f) {
                            sendX = pendingDx.toInt().coerceIn(-127, 127)
                            sendY = pendingDy.toInt().coerceIn(-127, 127)
                            sendWheel = pendingScroll.toInt().coerceIn(-127, 127)

                            pendingDx -= sendX
                            pendingDy -= sendY
                            pendingScroll -= sendWheel
                        }
                    }

                    if (sendX != 0 || sendY != 0 || sendWheel != 0) {
                        try {
                            val report = byteArrayOf(
                                currentButtons,
                                sendX.toByte(),
                                sendY.toByte(),
                                sendWheel.toByte()
                            )
                            dev.sendReport(target, REPORT_ID_MOUSE, report)
                        } catch (_: Exception) {}
                    }
                }
                delay(8) // ~125Hz polling rate matches Apple Magic Mouse hardware rate
            }
        }
    }

    fun init() {
        startDispatcherLoop()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onSupportedState(false, "Bluetooth HID requires Android 9.0 or newer.")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            onSupportedState(false, "This device does not support Bluetooth.")
            return
        }

        if (!adapter.isEnabled) {
            onSupportedState(false, "Please turn on Bluetooth on your phone.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission not granted yet")
                return
            }
        }

        try {
            val res = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            Log.d(TAG, "getProfileProxy returned $res")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting HID_DEVICE profile proxy", e)
            onSupportedState(false, "Bluetooth HID is unavailable on this device firmware.")
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                hidDevice = proxy as? BluetoothHidDevice
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                isRegistered = false
                connectedDevice = null
                onConnectionStateChanged(false, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val device = hidDevice ?: return

        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Remote Trackpad",
            "Wireless Bluetooth Trackpad & Keyboard",
            "RemoteTrackpad",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HID_REPORT_DESCRIPTOR
        )

        val qosSettings = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        try {
            val registered = device.registerApp(sdpSettings, qosSettings, null, context.mainExecutor, hidCallback)
            if (registered) {
                isRegistered = true
                onSupportedState(true, "Bluetooth HID ready. Pair your computer via Bluetooth.")
            } else {
                onSupportedState(false, "Failed to register Bluetooth HID profile.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerApp exception", e)
            onSupportedState(false, "Bluetooth registration error: ${e.message}")
        }
    }

    private val hidCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        object : BluetoothHidDevice.Callback() {
            @SuppressLint("MissingPermission")
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                isRegistered = registered
                if (registered && pluggedDevice != null) {
                    connectedDevice = pluggedDevice
                    onConnectionStateChanged(true, pluggedDevice.name ?: pluggedDevice.address)
                }
            }

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        onConnectionStateChanged(true, device?.name ?: device?.address)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedDevice == device) {
                            connectedDevice = null
                            onConnectionStateChanged(false, null)
                        }
                    }
                }
            }

            override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                // Reply with zero report
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    hidDevice?.replyReport(device, type, id, ByteArray(bufferSize))
                }
            }

            override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
                }
            }
        }
    } else null

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hidDevice?.connect(device)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            connectedDevice?.let { hidDevice?.disconnect(it) }
        }
        connectedDevice = null
        onConnectionStateChanged(false, null)
    }

    fun isConnected(): Boolean = connectedDevice != null

    @SuppressLint("MissingPermission")
    fun getConnectedDeviceName(): String? {
        return connectedDevice?.name ?: connectedDevice?.address
    }

    // =========================================================================
    // MOUSE HID REPORTS
    // =========================================================================
    fun sendMouseMove(dx: Float, dy: Float) {
        synchronized(movementLock) {
            pendingDx += dx
            pendingDy += dy
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseClick(button: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val target = connectedDevice ?: return

        val mask: Byte = if (button.equals("right", ignoreCase = true)) 0x02 else 0x01

        scope.launch {
            // Button DOWN
            currentButtons = (currentButtons.toInt() or mask.toInt()).toByte()
            val downReport = byteArrayOf(currentButtons, 0, 0, 0)
            hidDevice?.sendReport(target, REPORT_ID_MOUSE, downReport)

            delay(35)

            // Button UP
            currentButtons = (currentButtons.toInt() and mask.toInt().inv()).toByte()
            val upReport = byteArrayOf(currentButtons, 0, 0, 0)
            hidDevice?.sendReport(target, REPORT_ID_MOUSE, upReport)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseDown(button: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val target = connectedDevice ?: return

        val mask: Byte = if (button.equals("right", ignoreCase = true)) 0x02 else 0x01
        currentButtons = (currentButtons.toInt() or mask.toInt()).toByte()
        val report = byteArrayOf(currentButtons, 0, 0, 0)
        hidDevice?.sendReport(target, REPORT_ID_MOUSE, report)
    }

    @SuppressLint("MissingPermission")
    fun sendMouseUp(button: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val target = connectedDevice ?: return

        val mask: Byte = if (button.equals("right", ignoreCase = true)) 0x02 else 0x01
        currentButtons = (currentButtons.toInt() and mask.toInt().inv()).toByte()
        val report = byteArrayOf(currentButtons, 0, 0, 0)
        hidDevice?.sendReport(target, REPORT_ID_MOUSE, report)
    }

    fun sendScroll(scrollDy: Float) {
        synchronized(movementLock) {
            // Smooth natural scrolling scale
            pendingScroll += (scrollDy / 2.2f)
        }
    }

    // =========================================================================
    // PRESENTATION & KEYBOARD HID REPORTS
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun sendSlideNavigation(next: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val target = connectedDevice ?: return

        // Right arrow = 0x4F, Left arrow = 0x50
        val keycode: Byte = if (next) 0x4F else 0x50

        scope.launch {
            // Key DOWN
            val downReport = byteArrayOf(0, 0, keycode, 0, 0, 0, 0, 0)
            hidDevice?.sendReport(target, REPORT_ID_KEYBOARD, downReport)
            delay(40)
            // Key UP
            val upReport = ByteArray(8)
            hidDevice?.sendReport(target, REPORT_ID_KEYBOARD, upReport)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendKey(keyName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val target = connectedDevice ?: return

        val keycode: Byte = when (keyName.lowercase()) {
            "enter", "return" -> 0x28
            "backspace" -> 0x2A
            "space" -> 0x2C
            "tab" -> 0x2B
            "esc", "escape" -> 0x29
            "up" -> 0x52
            "down" -> 0x51
            "left" -> 0x50
            "right" -> 0x4F
            else -> return
        }

        scope.launch {
            val downReport = byteArrayOf(0, 0, keycode, 0, 0, 0, 0, 0)
            hidDevice?.sendReport(target, REPORT_ID_KEYBOARD, downReport)
            delay(35)
            val upReport = ByteArray(8)
            hidDevice?.sendReport(target, REPORT_ID_KEYBOARD, upReport)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendVolume(up: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val target = connectedDevice ?: return

        // Consumer Report 3: Bit 0 = Vol Up (0x01), Bit 1 = Vol Down (0x02)
        val mask: Byte = if (up) 0x01 else 0x02

        scope.launch {
            val downReport = byteArrayOf(mask)
            hidDevice?.sendReport(target, REPORT_ID_CONSUMER, downReport)
            delay(40)
            val upReport = byteArrayOf(0)
            hidDevice?.sendReport(target, REPORT_ID_CONSUMER, upReport)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendText(text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val target = connectedDevice ?: return

        scope.launch {
            for (char in text) {
                val (modifier, keycode) = charToHidKey(char)
                if (keycode != 0.toByte()) {
                    val downReport = byteArrayOf(modifier, 0, keycode, 0, 0, 0, 0, 0)
                    hidDevice?.sendReport(target, REPORT_ID_KEYBOARD, downReport)
                    delay(25)
                    val upReport = ByteArray(8)
                    hidDevice?.sendReport(target, REPORT_ID_KEYBOARD, upReport)
                    delay(20)
                }
            }
        }
    }

    private fun charToHidKey(c: Char): Pair<Byte, Byte> {
        val shift: Byte = 0x02 // Left Shift modifier
        return when (c) {
            in 'a'..'z' -> Pair(0, (0x04 + (c - 'a')).toByte())
            in 'A'..'Z' -> Pair(shift, (0x04 + (c - 'A')).toByte())
            in '1'..'9' -> Pair(0, (0x1E + (c - '1')).toByte())
            '0' -> Pair(0, 0x27)
            ' ' -> Pair(0, 0x2C)
            '\n' -> Pair(0, 0x28)
            '!' -> Pair(shift, 0x1E)
            '@' -> Pair(shift, 0x1F)
            '#' -> Pair(shift, 0x20)
            '$' -> Pair(shift, 0x21)
            '%' -> Pair(shift, 0x22)
            '^' -> Pair(shift, 0x23)
            '&' -> Pair(shift, 0x24)
            '*' -> Pair(shift, 0x25)
            '(' -> Pair(shift, 0x26)
            ')' -> Pair(shift, 0x27)
            '-' -> Pair(0, 0x2D)
            '_' -> Pair(shift, 0x2D)
            '=' -> Pair(0, 0x2E)
            '+' -> Pair(shift, 0x2E)
            '[' -> Pair(0, 0x2F)
            '{' -> Pair(shift, 0x2F)
            ']' -> Pair(0, 0x30)
            '}' -> Pair(shift, 0x30)
            '\\' -> Pair(0, 0x31)
            '|' -> Pair(shift, 0x31)
            ';' -> Pair(0, 0x33)
            ':' -> Pair(shift, 0x33)
            '\'' -> Pair(0, 0x34)
            '"' -> Pair(shift, 0x34)
            ',' -> Pair(0, 0x36)
            '<' -> Pair(shift, 0x36)
            '.' -> Pair(0, 0x37)
            '>' -> Pair(shift, 0x37)
            '/' -> Pair(0, 0x38)
            '?' -> Pair(shift, 0x38)
            else -> Pair(0, 0)
        }
    }

    @SuppressLint("MissingPermission")
    fun destroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                hidDevice?.let { dev ->
                    connectedDevice?.let { dev.disconnect(it) }
                    if (isRegistered) {
                        dev.unregisterApp()
                    }
                }
            } catch (_: Exception) {}
        }
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null
        connectedDevice = null
        isRegistered = false
        dispatcherJob?.cancel()
        scope.cancel()
    }
}

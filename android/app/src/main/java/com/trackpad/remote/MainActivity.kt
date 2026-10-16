package com.trackpad.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trackpad.remote.databinding.ActivityMainBinding
import com.trackpad.remote.databinding.ItemDiscoveredSystemBinding
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    enum class ConnectionType {
        NONE,
        WIFI,
        BLUETOOTH
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: TrackpadClient
    private lateinit var btManager: BluetoothHidManager
    private lateinit var discoveryManager: DeviceDiscoveryManager
    private var vibrator: Vibrator? = null

    // Preferences & Settings State
    private var sensitivity = 1.2f
    private var hapticEnabled = true
    private var autoConnectEnabled = false
    private var lastSavedIp = "10.90.63.215"
    private var lastSavedPort = 8765
    private var activeSystemName = ""

    // Connection & Mode State
    private var currentConnectionType = ConnectionType.NONE
    private var activeBtDevice: BluetoothDevice? = null
    private var currentConnectTab = "wifi" // "wifi" or "bluetooth"
    private var isConnected = false
    private var isDrawerExpanded = false
    private var isPresentationMode = false
    private var isConnectionLost = false
    private var isTypingInternalUpdate = false

    // Bluetooth permission request launcher (API 31+)
    private val requestBluetoothPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val connectGranted = permissions[android.Manifest.permission.BLUETOOTH_CONNECT] ?: false
        if (connectGranted) {
            btManager.init()
            renderBluetoothDevices()
            Toast.makeText(this, "Bluetooth permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth permission required for Bluetooth HID", Toast.LENGTH_SHORT).show()
        }
    }

    // Touch gesture tracking variables
    private var touchSlop = 0
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastScrollY = 0f
    private var lastScrollX = 0f
    private var isDragging = false
    private var isTwoFingerGesture = false
    private var totalMovement = 0f

    // Presentation swipe gesture tracking
    private var presDownX = 0f
    private var presDownY = 0f
    private var presDownTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", "dark") ?: "dark"
        applyThemeMode(savedTheme)

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initVibrator()
        touchSlop = 4 // Minimal deadzone for instantaneous response

        loadPreferences()
        setupNetworkClient()
        setupBluetooth()
        setupDiscovery()
        setupHeaderInteractions()
        setupDockBar()
        setupConnectScreen()
        setupRemoteTyping()
        setupSettingsScreen()
        setupTouchpadGesture()
        setupPresentationGestures()
        setupBackNavigation()

        if (autoConnectEnabled && lastSavedIp.isNotEmpty()) {
            client.connect(lastSavedIp, lastSavedPort)
        }
    }

    private fun applyThemeMode(mode: String) {
        when (mode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    private fun updateAppearanceLabel(mode: String) {
        binding.tvSettingsAppearanceValue.text = when (mode) {
            "light" -> "Light"
            "system" -> "System"
            else -> "Dark"
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrate(durationMs: Long) {
        if (!hapticEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
        lastSavedIp = prefs.getString("last_ip", "10.90.63.215") ?: "10.90.63.215"
        lastSavedPort = prefs.getString("last_port", "8765")?.toIntOrNull() ?: 8765
        activeSystemName = prefs.getString("last_system_name", "") ?: ""
        val speedProgress = prefs.getInt("sensitivity_progress", 15)
        hapticEnabled = prefs.getBoolean("haptic_enabled", true)
        autoConnectEnabled = prefs.getBoolean("auto_connect", false)

        updateSensitivity(speedProgress)
        syncSpeedProgress(speedProgress)

        binding.etConnectIp.setText(lastSavedIp)
        binding.etConnectPort.setText(lastSavedPort.toString())
        binding.etDrawerIp.setText(lastSavedIp)
        binding.etDrawerPort.setText(lastSavedPort.toString())
        binding.tvSettingsIpValue.text = lastSavedIp
        binding.tvSettingsPortValue.text = lastSavedPort.toString()
        binding.switchHapticFeedback.isChecked = hapticEnabled
        binding.switchAutoConnect.isChecked = autoConnectEnabled

        val themeMode = prefs.getString("theme_mode", "dark") ?: "dark"
        updateAppearanceLabel(themeMode)
    }

    private fun updateSensitivity(progress: Int) {
        sensitivity = 0.4f + (progress / 30f) * 3.1f
    }

    private fun syncSpeedProgress(progress: Int) {
        val percent = (progress * 100 / 30).coerceIn(10, 100)
        binding.seekSpeedDrawer.progress = progress
        binding.seekSettingsSpeed.progress = progress
        binding.tvSpeedDrawerPercent.text = "$percent%"
        binding.tvSettingsSpeedPercent.text = "$percent%"
    }

    private fun setupNetworkClient() {
        client = TrackpadClient { connected, message ->
            runOnUiThread {
                isConnected = connected
                isConnectionLost = !connected && (message.contains("Failed", ignoreCase = true) || message.contains("lost", ignoreCase = true) || message.contains("Disconnected", ignoreCase = true))

                if (connected) {
                    currentConnectionType = ConnectionType.WIFI
                    if (btManager.isConnected()) {
                        btManager.disconnectDevice()
                    }
                    val displayName = if (activeSystemName.isNotEmpty()) activeSystemName else lastSavedIp
                    binding.tvHeaderTitle.text = displayName
                    binding.tvHeaderSubtitle.text = "$lastSavedIp:$lastSavedPort • Tap to switch"
                    binding.ivHeaderIcon.setImageResource(R.drawable.ic_laptop)
                    binding.layoutStatusBadge.visibility = View.VISIBLE
                    binding.tvStatusBadge.text = "Connected"
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.success_green)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    binding.layoutStatusBadge.setBackgroundResource(R.drawable.status_pill_bg)

                    binding.layoutDrawerWifiFields.visibility = View.VISIBLE

                    binding.layoutConnectionLostView.visibility = View.GONE
                    if (isPresentationMode) {
                        binding.layoutPresentationView.visibility = View.VISIBLE
                        binding.touchpadSurface.visibility = View.GONE
                    } else {
                        binding.touchpadSurface.visibility = View.VISIBLE
                        binding.layoutPresentationView.visibility = View.GONE
                    }
                    vibrate(40)
                    renderDiscoveredSystems(discoveryManager.getDiscoveredDevices())
                } else {
                    if (currentConnectionType == ConnectionType.WIFI) {
                        currentConnectionType = ConnectionType.NONE
                        binding.layoutStatusBadge.visibility = View.GONE
                        binding.tvHeaderTitle.text = "Not Connected"
                        binding.tvHeaderSubtitle.text = "Tap to connect"

                        if (isConnectionLost) {
                            binding.layoutConnectionLostView.visibility = View.VISIBLE
                            binding.touchpadSurface.visibility = View.GONE
                            binding.layoutPresentationView.visibility = View.GONE
                        } else {
                            binding.layoutConnectionLostView.visibility = View.GONE
                            if (isPresentationMode) {
                                binding.layoutPresentationView.visibility = View.VISIBLE
                                binding.touchpadSurface.visibility = View.GONE
                            } else {
                                binding.touchpadSurface.visibility = View.VISIBLE
                                binding.layoutPresentationView.visibility = View.GONE
                            }
                        }
                        renderDiscoveredSystems(discoveryManager.getDiscoveredDevices())
                    }
                }
            }
        }
    }

    private fun setupBluetooth() {
        btManager = BluetoothHidManager(
            context = this,
            onConnectionStateChanged = { connected, deviceName ->
                runOnUiThread {
                    onBluetoothConnectionChanged(connected, deviceName)
                }
            },
            onSupportedState = { supported, message ->
                runOnUiThread {
                    if (!supported) {
                        binding.tvBluetoothCount.text = "Notice"
                        binding.tvBluetoothStatusHeader.text = "⚠️ Bluetooth HID Notice"
                        binding.tvBluetoothStatusDesc.text = message
                    }
                }
            }
        )
        if (btManager.hasPermission()) {
            btManager.init()
        }
    }

    @SuppressLint("MissingPermission")
    private fun onBluetoothConnectionChanged(connected: Boolean, deviceName: String?) {
        isConnected = connected
        if (connected) {
            currentConnectionType = ConnectionType.BLUETOOTH
            if (client.isConnected()) {
                client.disconnect()
            }
            val name = deviceName ?: activeBtDevice?.name ?: "Bluetooth Device"
            activeSystemName = name
            binding.tvHeaderTitle.text = name
            binding.tvHeaderSubtitle.text = "Bluetooth HID • Tap to switch"
            binding.ivHeaderIcon.setImageResource(R.drawable.ic_laptop)
            binding.layoutStatusBadge.visibility = View.VISIBLE
            binding.tvStatusBadge.text = "Connected"
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.success_green)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            binding.layoutStatusBadge.setBackgroundResource(R.drawable.status_pill_bg)

            binding.layoutDrawerWifiFields.visibility = View.GONE
            binding.layoutConnectionLostView.visibility = View.GONE
            if (isPresentationMode) {
                binding.layoutPresentationView.visibility = View.VISIBLE
                binding.touchpadSurface.visibility = View.GONE
            } else {
                binding.touchpadSurface.visibility = View.VISIBLE
                binding.layoutPresentationView.visibility = View.GONE
            }
            vibrate(40)
            renderDiscoveredSystems(discoveryManager.getDiscoveredDevices())
            renderBluetoothDevices()
            saveToRecentDevices(name, "Bluetooth HID", 0)
        } else {
            if (currentConnectionType == ConnectionType.BLUETOOTH) {
                currentConnectionType = ConnectionType.NONE
                activeBtDevice = null
                binding.layoutStatusBadge.visibility = View.GONE
                binding.tvHeaderTitle.text = "Not Connected"
                binding.tvHeaderSubtitle.text = "Tap to connect"
                renderBluetoothDevices()
                renderDiscoveredSystems(discoveryManager.getDiscoveredDevices())
            }
        }
    }

    // =========================================================================
    // HEADER & EXPANDED DRAWER INTERACTIONS (Screens 1, 3, 6)
    // =========================================================================
    private fun openConnectScreen() {
        binding.layoutConnectScreen.visibility = View.VISIBLE
        if (currentConnectTab == "bluetooth") {
            switchToBluetoothTab()
        } else {
            switchToWifiTab()
        }
        renderDiscoveredSystems(discoveryManager.getDiscoveredDevices())
        renderBluetoothDevices()
        renderRecentDevices()
        val currentInput = binding.etConnectIp.text.toString().trim()
        val list = mutableListOf<String>()
        if (currentInput.isNotEmpty()) list.add(currentInput)
        if (lastSavedIp.isNotEmpty()) list.add(lastSavedIp)
        discoveryManager.triggerImmediateProbe(list)
    }

    private fun setupHeaderInteractions() {
        // Tapping the header row (laptop icon, titles, badge) always opens Connect to Mac (Bluetooth & Wi-Fi chooser)
        binding.headerSummaryRow.setOnClickListener {
            vibrate(25)
            openConnectScreen()
            val currentInput = binding.etConnectIp.text.toString().trim()
            val list = mutableListOf<String>()
            if (currentInput.isNotEmpty()) list.add(currentInput)
            if (lastSavedIp.isNotEmpty()) list.add(lastSavedIp)
            discoveryManager.triggerImmediateProbe(list)
        }

        binding.layoutStatusBadge.setOnClickListener {
            vibrate(25)
            openConnectScreen()
        }

        binding.btnDrawerChooseConnection.setOnClickListener {
            vibrate(25)
            isDrawerExpanded = false
            binding.layoutExpandedDrawer.visibility = View.GONE
            binding.btnHeaderChevron.setImageResource(R.drawable.ic_chevron_down)
            openConnectScreen()
        }

        // The chevron specifically toggles the expanded drawer for speed adjustments
        binding.btnHeaderChevron.setOnClickListener {
            vibrate(20)
            if (isConnected) {
                isDrawerExpanded = !isDrawerExpanded
                binding.layoutExpandedDrawer.visibility = if (isDrawerExpanded) View.VISIBLE else View.GONE
                binding.btnHeaderChevron.setImageResource(
                    if (isDrawerExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
                )
            } else {
                openConnectScreen()
            }
        }

        binding.btnDrawerDisconnect.setOnClickListener {
            vibrate(25)
            if (currentConnectionType == ConnectionType.BLUETOOTH) {
                btManager.disconnectDevice()
            } else {
                client.disconnect()
            }
            isDrawerExpanded = false
            binding.layoutExpandedDrawer.visibility = View.GONE
            binding.btnHeaderChevron.setImageResource(R.drawable.ic_chevron_down)
            openConnectScreen()
        }

        binding.seekSpeedDrawer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSensitivity(progress)
                syncSpeedProgress(progress)
                getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("sensitivity_progress", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnReconnect.setOnClickListener {
            binding.layoutConnectionLostView.visibility = View.GONE
            binding.touchpadSurface.visibility = View.VISIBLE
            if (currentConnectionType == ConnectionType.BLUETOOTH && activeBtDevice != null) {
                btManager.connectDevice(activeBtDevice!!)
            } else {
                client.connect(lastSavedIp, lastSavedPort)
            }
        }
    }

    // =========================================================================
    // DOCK BAR CONTROLS (Left Click, Present, Keyboard, Right Click)
    // =========================================================================
    private fun setupDockBar() {
        // Left Click
        binding.btnDockLeftClick.setOnClickListener {
            vibrate(25)
            dispatchMouseClick("left")
        }
        binding.btnDockLeftClick.setOnLongClickListener {
            vibrate(40)
            dispatchMouseDown("left")
            true
        }

        // Present Mode Toggle (Screen 4)
        binding.btnDockPresent.setOnClickListener {
            isPresentationMode = !isPresentationMode
            vibrate(30)
            updatePresentationState()
        }

        // Keyboard / Remote Typing Toggle (Screen 5)
        binding.btnDockKeyboard.setOnClickListener {
            vibrate(30)
            val isOpen = binding.layoutRemoteTyping.visibility == View.VISIBLE
            if (isOpen) {
                closeRemoteTyping()
            } else {
                openRemoteTyping()
            }
        }

        // Right Click
        binding.btnDockRightClick.setOnClickListener {
            vibrate(30)
            dispatchMouseClick("right")
        }
    }

    private fun updatePresentationState() {
        if (isPresentationMode) {
            binding.btnDockPresent.setBackgroundResource(R.drawable.dock_btn_active)
            binding.ivDockPresentIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_white))
            binding.tvDockPresentLabel.setTextColor(ContextCompat.getColor(this, R.color.text_white))

            binding.layoutPresentationView.visibility = View.VISIBLE
            binding.touchpadSurface.visibility = View.GONE
        } else {
            binding.btnDockPresent.setBackgroundResource(R.drawable.dock_btn_normal)
            binding.ivDockPresentIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvDockPresentLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            binding.layoutPresentationView.visibility = View.GONE
            if (!isConnectionLost) {
                binding.touchpadSurface.visibility = View.VISIBLE
            }
        }
    }

    // =========================================================================
    // CONNECT SCREEN (Screen 2: Connect to Mac & Recent Devices)
    // =========================================================================
    private fun setupConnectScreen() {
        binding.btnBackFromConnect.setOnClickListener {
            binding.layoutConnectScreen.visibility = View.GONE
        }

        binding.btnConnectToSettings.setOnClickListener {
            binding.layoutConnectScreen.visibility = View.GONE
            binding.layoutSettingsScreen.visibility = View.VISIBLE
        }

        binding.btnClearConnectIp.setOnClickListener {
            binding.etConnectIp.setText("")
        }

        binding.btnClearConnectPort.setOnClickListener {
            binding.etConnectPort.setText("")
        }

        // Auto-split if user pastes "IP:PORT" into IP field
        binding.etConnectIp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: return
                if (text.contains(":")) {
                    val parts = text.split(":")
                    binding.etConnectIp.removeTextChangedListener(this)
                    binding.etConnectIp.setText(parts[0].trim())
                    binding.etConnectIp.setSelection(parts[0].trim().length)
                    if (parts.size > 1 && parts[1].trim().isNotEmpty()) {
                        binding.etConnectPort.setText(parts[1].trim())
                    }
                    binding.etConnectIp.addTextChangedListener(this)
                }
            }
        })

        binding.btnConnectSubmit.setOnClickListener {
            val ip = binding.etConnectIp.text.toString().trim()
            val port = binding.etConnectPort.text.toString().trim().toIntOrNull() ?: 8765

            if (ip.isEmpty()) {
                binding.etConnectIp.error = "Enter IP address"
                return@setOnClickListener
            }

            val matching = discoveryManager.getDiscoveredDevices().find { it.ip == ip && it.port == port }
            activeSystemName = matching?.name ?: "Computer"

            lastSavedIp = ip
            lastSavedPort = port
            getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("last_ip", ip)
                .putString("last_port", port.toString())
                .putString("last_system_name", activeSystemName)
                .apply()

            binding.etDrawerIp.setText(ip)
            binding.etDrawerPort.setText(port.toString())
            binding.tvSettingsIpValue.text = ip
            binding.tvSettingsPortValue.text = port.toString()

            saveToRecentDevices(activeSystemName, ip, port)

            binding.layoutConnectScreen.visibility = View.GONE
            client.connect(ip, port)
        }

        binding.btnScanWifi.setOnClickListener {
            vibrate(25)
            val typedIp = binding.etConnectIp.text.toString().trim()
            discoveryManager.triggerImmediateProbe(if (typedIp.isNotEmpty()) listOf(typedIp) else emptyList())
            Toast.makeText(this, "Scanning Wi-Fi network...", Toast.LENGTH_SHORT).show()
        }

        binding.btnScanAgain.setOnClickListener {
            vibrate(25)
            val typedIp = binding.etConnectIp.text.toString().trim()
            discoveryManager.triggerImmediateProbe(if (typedIp.isNotEmpty()) listOf(typedIp) else emptyList())
            Toast.makeText(this, "Scanning for systems...", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearAllRecent.setOnClickListener {
            vibrate(30)
            getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
                .edit().putString("recent_devices_history", "[]").apply()
            renderRecentDevices()
            Toast.makeText(this, "Recent devices cleared", Toast.LENGTH_SHORT).show()
        }

        // Tab switching between Wi-Fi and Bluetooth
        binding.tabModeWifi.setOnClickListener {
            vibrate(20)
            switchToWifiTab()
        }

        binding.tabModeBluetooth.setOnClickListener {
            vibrate(20)
            switchToBluetoothTab()
        }

        // Bluetooth Controls
        binding.btnScanBluetooth.setOnClickListener {
            vibrate(25)
            checkAndRefreshBluetooth()
            Toast.makeText(this, "Refreshing paired Bluetooth computers...", Toast.LENGTH_SHORT).show()
        }

        binding.btnScanBluetoothAgain.setOnClickListener {
            vibrate(25)
            checkAndRefreshBluetooth()
        }

        binding.btnPairNewBluetooth.setOnClickListener {
            vibrate(25)
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                Toast.makeText(this, "Pair your computer in Bluetooth Settings", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Bluetooth settings", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnBluetoothActionHelper.setOnClickListener {
            vibrate(25)
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {}
        }

        renderRecentDevices()
    }

    private fun switchToWifiTab() {
        currentConnectTab = "wifi"
        binding.tabModeWifi.setBackgroundResource(R.drawable.dock_btn_active)
        binding.tvTabModeWifi.setTextColor(ContextCompat.getColor(this, R.color.text_white))
        binding.tvTabModeWifi.typeface = android.graphics.Typeface.DEFAULT_BOLD

        binding.tabModeBluetooth.setBackgroundResource(android.R.color.transparent)
        binding.tvTabModeBluetooth.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvTabModeBluetooth.typeface = android.graphics.Typeface.DEFAULT
        binding.ivTabBluetoothIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.text_secondary)

        binding.layoutWifiContent.visibility = View.VISIBLE
        binding.layoutBluetoothContent.visibility = View.GONE
    }

    private fun switchToBluetoothTab() {
        currentConnectTab = "bluetooth"
        binding.tabModeBluetooth.setBackgroundResource(R.drawable.dock_btn_active)
        binding.tvTabModeBluetooth.setTextColor(ContextCompat.getColor(this, R.color.text_white))
        binding.tvTabModeBluetooth.typeface = android.graphics.Typeface.DEFAULT_BOLD
        binding.ivTabBluetoothIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.text_white)

        binding.tabModeWifi.setBackgroundResource(android.R.color.transparent)
        binding.tvTabModeWifi.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvTabModeWifi.typeface = android.graphics.Typeface.DEFAULT

        binding.layoutWifiContent.visibility = View.GONE
        binding.layoutBluetoothContent.visibility = View.VISIBLE

        checkAndRefreshBluetooth()
    }

    private fun checkAndRefreshBluetooth() {
        if (!btManager.isBluetoothSupported()) {
            binding.tvBluetoothCount.text = "Not supported"
            binding.tvBluetoothStatusHeader.text = "⚠️ Bluetooth Not Available"
            binding.tvBluetoothStatusDesc.text = "This device does not have Bluetooth hardware."
            binding.cardBluetoothGroup.visibility = View.GONE
            binding.tvBluetoothEmptyHint.visibility = View.VISIBLE
            binding.tvBluetoothEmptyHint.text = "Bluetooth hardware not detected on this phone."
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !btManager.hasPermission()) {
            binding.tvBluetoothCount.text = "Permission needed"
            requestBluetoothPermissionsLauncher.launch(
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN
                )
            )
            return
        }

        if (!btManager.isBluetoothEnabled()) {
            binding.tvBluetoothCount.text = "Bluetooth Off"
            Toast.makeText(this, "Please turn on Bluetooth to connect", Toast.LENGTH_SHORT).show()
        }

        btManager.init()
        renderBluetoothDevices()
    }

    @SuppressLint("MissingPermission")
    private fun renderBluetoothDevices() {
        val pairedDevices = if (btManager.hasPermission()) {
            btManager.getPairedDevices()
        } else emptyList()

        val connectedDevice = btManager.getConnectedDevice()

        if (pairedDevices.isEmpty()) {
            binding.tvBluetoothCount.text = if (btManager.isBluetoothEnabled()) "0 paired" else "Bluetooth Off"
            binding.tvBluetoothEmptyHint.visibility = View.VISIBLE
            binding.cardBluetoothGroup.visibility = View.GONE
            binding.containerBluetoothList.removeAllViews()
        } else {
            binding.tvBluetoothCount.text = "${pairedDevices.size} paired"
            binding.tvBluetoothEmptyHint.visibility = View.GONE
            binding.cardBluetoothGroup.visibility = View.VISIBLE
            binding.containerBluetoothList.removeAllViews()

            for ((index, device) in pairedDevices.withIndex()) {
                val itemBinding = ItemDiscoveredSystemBinding.inflate(layoutInflater, binding.containerBluetoothList, false)
                val deviceName = try { device.name ?: device.address } catch (_: Exception) { device.address }
                val isCurrentTarget = isConnected && currentConnectionType == ConnectionType.BLUETOOTH &&
                        (connectedDevice?.address == device.address || activeBtDevice?.address == device.address)

                itemBinding.tvSystemName.text = deviceName
                itemBinding.tvSystemIp.text = "Bluetooth HID · ${device.address}"
                itemBinding.ivSystemIcon.setImageResource(R.drawable.ic_laptop)
                itemBinding.btnSystemDelete.visibility = View.GONE
                itemBinding.dividerBottom.visibility = if (index == pairedDevices.size - 1) View.GONE else View.VISIBLE

                if (isCurrentTarget) {
                    itemBinding.tvSystemActionBadge.text = "Connected"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connected_bg)
                } else {
                    itemBinding.tvSystemActionBadge.text = "Connect"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_white))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connect_bg)
                }

                val clickAction = View.OnClickListener {
                    connectBluetoothDevice(device)
                }
                itemBinding.cardSystemItem.setOnClickListener(clickAction)
                itemBinding.tvSystemActionBadge.setOnClickListener(clickAction)

                binding.containerBluetoothList.addView(itemBinding.root)
            }
        }
    }

    private fun connectBluetoothDevice(device: BluetoothDevice) {
        vibrate(35)
        val name = try { device.name ?: device.address } catch (_: Exception) { device.address }
        Toast.makeText(this, "Connecting to $name via Bluetooth...", Toast.LENGTH_SHORT).show()

        if (client.isConnected()) {
            client.disconnect()
        }

        activeBtDevice = device
        activeSystemName = name
        currentConnectionType = ConnectionType.BLUETOOTH

        getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_system_name", name)
            .putString("last_connection_type", "bluetooth")
            .putString("last_bt_address", device.address)
            .apply()

        binding.layoutConnectScreen.visibility = View.GONE
        isDrawerExpanded = false
        binding.layoutExpandedDrawer.visibility = View.GONE
        binding.btnHeaderChevron.setImageResource(R.drawable.ic_chevron_down)

        btManager.connectDevice(device)
        renderBluetoothDevices()
        renderDiscoveredSystems(discoveryManager.getDiscoveredDevices())
    }

    private fun connectToRecent(name: String, ip: String, port: Int) {
        if (ip == "Bluetooth HID") {
            switchToBluetoothTab()
            val match = btManager.getPairedDevices().find { (it.name ?: it.address) == name }
            if (match != null) {
                connectBluetoothDevice(match)
            } else {
                Toast.makeText(this, "Select $name from Paired Computers", Toast.LENGTH_SHORT).show()
            }
            return
        }
        switchToWifiTab()
        activeSystemName = name
        binding.etConnectIp.setText(ip)
        binding.etConnectPort.setText(port.toString())
        binding.btnConnectSubmit.performClick()
    }

    private fun deleteRecentDevice(ip: String, port: Int) {
        vibrate(25)
        val prefs = getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
        val historyStr = prefs.getString("recent_devices_history", "[]") ?: "[]"
        try {
            val arr = JSONArray(historyStr)
            val updated = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optString("ip") != ip || item.optInt("port", 8765) != port) {
                    updated.put(item)
                }
            }
            prefs.edit().putString("recent_devices_history", updated.toString()).apply()
            renderRecentDevices()
            Toast.makeText(this, "Removed from recent devices", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun saveToRecentDevices(name: String, ip: String, port: Int) {
        if (ip.isEmpty()) return
        val prefs = getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
        val historyStr = prefs.getString("recent_devices_history", "[]") ?: "[]"
        try {
            val arr = JSONArray(historyStr)
            val updated = JSONArray()
            val newObj = JSONObject().apply {
                put("name", name)
                put("ip", ip)
                put("port", port)
            }
            updated.put(newObj)

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val itemIp = item.optString("ip")
                val itemPort = item.optInt("port", 8765)
                // Avoid duplicates
                if (itemIp != ip || itemPort != port) {
                    updated.put(item)
                }
                if (updated.length() >= 5) break
            }

            prefs.edit().putString("recent_devices_history", updated.toString()).apply()
            renderRecentDevices()
        } catch (_: Exception) {}
    }

    private fun renderRecentDevices() {
        val prefs = getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
        val historyStr = prefs.getString("recent_devices_history", "[]") ?: "[]"
        try {
            val arr = JSONArray(historyStr)
            if (arr.length() == 0) {
                binding.layoutRecentSection.visibility = View.GONE
                binding.containerRecentList.removeAllViews()
                return
            }

            binding.layoutRecentSection.visibility = View.VISIBLE
            binding.containerRecentList.removeAllViews()

            val onlineDevices = discoveryManager.getDiscoveredDevices()

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val name = item.optString("name", "Computer")
                val ip = item.optString("ip")
                val port = item.optInt("port", 8765)

                val itemBinding = ItemDiscoveredSystemBinding.inflate(layoutInflater, binding.containerRecentList, false)
                itemBinding.tvSystemName.text = name
                val isOnline = onlineDevices.any { it.ip == ip && it.port == port }
                itemBinding.tvSystemIp.text = if (isOnline) "$ip · $port • Online" else "$ip · $port"
                itemBinding.ivSystemIcon.setImageResource(R.drawable.ic_laptop)
                itemBinding.dividerBottom.visibility = if (i == arr.length() - 1) View.GONE else View.VISIBLE
                val isCurrentlyActive = isConnected && lastSavedIp == ip && lastSavedPort == port

                if (isCurrentlyActive) {
                    itemBinding.tvSystemActionBadge.text = "Connected"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connected_bg)
                } else {
                    itemBinding.tvSystemActionBadge.text = "Connect"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_white))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connect_bg)
                }

                // Delete option for saved devices
                itemBinding.btnSystemDelete.visibility = View.VISIBLE
                itemBinding.btnSystemDelete.setOnClickListener {
                    deleteRecentDevice(ip, port)
                }

                val clickAction = View.OnClickListener {
                    connectToRecent(name, ip, port)
                }
                itemBinding.cardSystemItem.setOnClickListener(clickAction)
                itemBinding.tvSystemActionBadge.setOnClickListener(clickAction)

                binding.containerRecentList.addView(itemBinding.root)
            }
        } catch (_: Exception) {
            binding.layoutRecentSection.visibility = View.GONE
        }
    }

    // =========================================================================
    // REMOTE TYPING & SENT CONFIRMATION (Screens 5 & 7)
    // =========================================================================
    private fun setupRemoteTyping() {
        binding.btnCloseTyping.setOnClickListener {
            closeRemoteTyping()
        }

        // Realtime typing: dispatch each character/text addition as it is typed
        binding.etTypingInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isTypingInternalUpdate) return

                if (count > 0) {
                    val added = s?.subSequence(start, start + count)?.toString() ?: ""
                    if (added.isNotEmpty()) {
                        dispatchText(added)
                    }
                } else if (before > 0) {
                    for (i in 0 until before) {
                        dispatchKey("backspace")
                    }
                }
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Soft keyboard Enter key listener
        binding.etTypingInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)
            ) {
                dispatchKey("enter")
                true
            } else {
                false
            }
        }

        binding.btnTypingBackspace.setOnClickListener {
            vibrate(20)
            dispatchKey("backspace")
            val current = binding.etTypingInput.text.toString()
            if (current.isNotEmpty()) {
                isTypingInternalUpdate = true
                binding.etTypingInput.setText(current.substring(0, current.length - 1))
                binding.etTypingInput.setSelection(binding.etTypingInput.text.length)
                isTypingInternalUpdate = false
            }
        }

        binding.btnTypingEnter.setOnClickListener {
            vibrate(25)
            dispatchKey("enter")
            isTypingInternalUpdate = true
            binding.etTypingInput.setText("")
            isTypingInternalUpdate = false
        }

        binding.btnTypingSend.setOnClickListener {
            vibrate(35)
            isTypingInternalUpdate = true
            binding.etTypingInput.setText("")
            isTypingInternalUpdate = false
            closeRemoteTyping()
        }

        binding.btnConfirmationOk.setOnClickListener {
            binding.layoutConfirmationModal.visibility = View.GONE
        }
    }

    private fun openRemoteTyping() {
        binding.layoutRemoteTyping.visibility = View.VISIBLE
        binding.btnDockKeyboard.setBackgroundResource(R.drawable.dock_btn_active)
        binding.ivDockKeyboardIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_white))
        binding.tvDockKeyboardLabel.setTextColor(ContextCompat.getColor(this, R.color.text_white))
        isTypingInternalUpdate = true
        binding.etTypingInput.setText("")
        isTypingInternalUpdate = false
        binding.etTypingInput.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(binding.etTypingInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeRemoteTyping() {
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etTypingInput.windowToken, 0)
        binding.layoutRemoteTyping.visibility = View.GONE
        binding.btnDockKeyboard.setBackgroundResource(R.drawable.dock_btn_normal)
        binding.ivDockKeyboardIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvDockKeyboardLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    // =========================================================================
    // SETTINGS SCREEN (Screen 10)
    // =========================================================================
    private fun setupSettingsScreen() {
        binding.btnBackFromSettings.setOnClickListener {
            binding.layoutSettingsScreen.visibility = View.GONE
        }

        binding.rowSettingsIp.setOnClickListener {
            binding.layoutSettingsScreen.visibility = View.GONE
            binding.layoutConnectScreen.visibility = View.VISIBLE
        }

        binding.rowSettingsPort.setOnClickListener {
            binding.layoutSettingsScreen.visibility = View.GONE
            binding.layoutConnectScreen.visibility = View.VISIBLE
        }

        binding.seekSettingsSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSensitivity(progress)
                syncSpeedProgress(progress)
                getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("sensitivity_progress", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchHapticFeedback.setOnCheckedChangeListener { _, isChecked ->
            hapticEnabled = isChecked
            getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("haptic_enabled", isChecked).apply()
        }

        binding.switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            autoConnectEnabled = isChecked
            getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("auto_connect", isChecked).apply()
        }

        binding.rowSettingsAppearance.setOnClickListener {
            val prefs = getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
            val currentMode = prefs.getString("theme_mode", "dark") ?: "dark"
            val themeOptions = arrayOf("🌑 Dark Mode", "☀️ Light Mode", "⚙️ System Default")
            val currentChoice = when (currentMode) {
                "light" -> 1
                "system" -> 2
                else -> 0
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Appearance")
                .setSingleChoiceItems(themeOptions, currentChoice) { dialog, which ->
                    val selectedMode = when (which) {
                        1 -> "light"
                        2 -> "system"
                        else -> "dark"
                    }
                    prefs.edit().putString("theme_mode", selectedMode).apply()
                    updateAppearanceLabel(selectedMode)
                    applyThemeMode(selectedMode)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // =========================================================================
    // PRESENTATION MODE SWIPE GESTURES (Screen 4)
    // =========================================================================
    private fun setupPresentationGestures() {
        binding.layoutPresentationView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    presDownX = event.x
                    presDownY = event.y
                    presDownTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - presDownX
                    val dy = event.y - presDownY
                    val duration = System.currentTimeMillis() - presDownTime

                    if (duration < 600) {
                        val absDx = abs(dx)
                        val absDy = abs(dy)

                        if (absDx > 60 && absDx > absDy) {
                            if (dx > 0) {
                                // Swipe right -> Next Slide
                                vibrate(25)
                                dispatchSlide(true)
                            } else {
                                // Swipe left -> Previous Slide
                                vibrate(25)
                                dispatchSlide(false)
                            }
                        } else if (absDy > 60 && absDy > absDx) {
                            if (dy < 0) {
                                // Swipe up -> Volume Up
                                vibrate(25)
                                dispatchVolume(true)
                            } else {
                                // Swipe down -> Volume Down
                                vibrate(25)
                                dispatchVolume(false)
                            }
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    // =========================================================================
    // MAIN TOUCHPAD GESTURES
    // =========================================================================
    private fun setupTouchpadGesture() {
        binding.touchpadSurface.setOnTouchListener { _, event ->
            val pointerCount = event.pointerCount

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    lastX = event.x
                    lastY = event.y
                    isDragging = false
                    isTwoFingerGesture = false
                    totalMovement = 0f
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (pointerCount == 2) {
                        isTwoFingerGesture = true
                        lastScrollX = (event.getX(0) + event.getX(1)) / 2f
                        lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (pointerCount == 1 && !isTwoFingerGesture) {
                        val historySize = event.historySize
                        for (i in 0 until historySize) {
                            val hx = event.getHistoricalX(i)
                            val hy = event.getHistoricalY(i)
                            val hdx = hx - lastX
                            val hdy = hy - lastY
                            lastX = hx
                            lastY = hy
                            totalMovement += hypot(hdx, hdy)
                            if (hdx != 0f || hdy != 0f) {
                                dispatchMouseMove(hdx * sensitivity, hdy * sensitivity)
                            }
                        }
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        lastX = event.x
                        lastY = event.y
                        totalMovement += hypot(dx, dy)
                        if (dx != 0f || dy != 0f) {
                            dispatchMouseMove(dx * sensitivity, dy * sensitivity)
                        }

                    } else if (pointerCount >= 2) {
                        isTwoFingerGesture = true
                        val avgX = (event.getX(0) + event.getX(1)) / 2f
                        val avgY = (event.getY(0) + event.getY(1)) / 2f

                        val scrollDx = avgX - lastScrollX
                        val scrollDy = avgY - lastScrollY
                        lastScrollX = avgX
                        lastScrollY = avgY

                        if (scrollDx != 0f || scrollDy != 0f) {
                            dispatchScroll(scrollDx * 0.9f, scrollDy * 0.9f)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (pointerCount == 2 && isTwoFingerGesture) {
                        val duration = System.currentTimeMillis() - downTime
                        if (duration < 280 && totalMovement < 16f) {
                            vibrate(30)
                            dispatchMouseClick("right")
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - downTime
                    if (!isTwoFingerGesture && totalMovement < 10f && duration < 260) {
                        vibrate(20)
                        dispatchMouseClick("left")
                    } else {
                        if (isConnected) {
                            dispatchFlushRemainder()
                        }
                    }
                    totalMovement = 0f
                    isTwoFingerGesture = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dispatchMouseUp("left")
                    isDragging = false
                    isTwoFingerGesture = false
                    true
                }

                else -> false
            }
        }
    }

    // =========================================================================
    // BLANKET MODE: Volume Keys Handlers
    // =========================================================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                vibrate(20)
                dispatchVolume(true)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                vibrate(25)
                dispatchVolume(false)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // =========================================================================
    // MULTI-SYSTEM ZERO-CONFIG DISCOVERY & SWITCHING
    // =========================================================================
    private fun setupDiscovery() {
        discoveryManager = DeviceDiscoveryManager(this) { devices ->
            runOnUiThread {
                renderDiscoveredSystems(devices)
            }
        }
        if (lastSavedIp.isNotEmpty()) {
            discoveryManager.addCandidateIp(lastSavedIp)
        }
        val prefs = getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
        val historyStr = prefs.getString("recent_devices_history", "[]") ?: "[]"
        try {
            val arr = JSONArray(historyStr)
            for (i in 0 until arr.length()) {
                val ip = arr.getJSONObject(i).optString("ip")
                if (ip.isNotEmpty()) discoveryManager.addCandidateIp(ip)
            }
        } catch (_: Exception) {}

        discoveryManager.start()
    }

    private fun renderDiscoveredSystems(devices: List<DiscoveredDevice>) {
        // 1. Connect Screen List (Wi-Fi)
        if (devices.isEmpty()) {
            binding.tvDiscoveredCount.text = "Searching..."
            binding.tvDiscoveredEmptyHint.visibility = View.VISIBLE
            binding.cardDiscoveredGroup.visibility = View.GONE
            binding.containerDiscoveredList.removeAllViews()
        } else {
            binding.tvDiscoveredCount.text = "${devices.size} online"
            binding.tvDiscoveredEmptyHint.visibility = View.GONE
            binding.cardDiscoveredGroup.visibility = View.VISIBLE
            binding.containerDiscoveredList.removeAllViews()

            for ((index, device) in devices.withIndex()) {
                val itemBinding = ItemDiscoveredSystemBinding.inflate(layoutInflater, binding.containerDiscoveredList, false)
                val isCurrentTarget = isConnected && currentConnectionType == ConnectionType.WIFI &&
                        (device.ip == lastSavedIp && device.port == lastSavedPort)

                itemBinding.tvSystemName.text = device.name
                itemBinding.tvSystemIp.text = "${device.ip} · ${device.port} · ${device.os}"
                itemBinding.ivSystemIcon.setImageResource(R.drawable.ic_laptop)
                itemBinding.btnSystemDelete.visibility = View.GONE
                itemBinding.dividerBottom.visibility = if (index == devices.size - 1) View.GONE else View.VISIBLE

                if (isCurrentTarget) {
                    itemBinding.tvSystemActionBadge.text = "Connected"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connected_bg)
                } else {
                    itemBinding.tvSystemActionBadge.text = "Connect"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_white))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connect_bg)
                }

                val switchAction = View.OnClickListener {
                    switchToSystem(device)
                }
                itemBinding.cardSystemItem.setOnClickListener(switchAction)
                itemBinding.tvSystemActionBadge.setOnClickListener(switchAction)

                binding.containerDiscoveredList.addView(itemBinding.root)
            }
        }

        // 2. Drawer Quick Switch List (Both Wi-Fi & Bluetooth devices)
        val pairedBt = if (::btManager.isInitialized && btManager.hasPermission()) {
            try { btManager.getPairedDevices() } catch (_: Exception) { emptyList() }
        } else emptyList()

        if (devices.isEmpty() && pairedBt.isEmpty()) {
            binding.layoutDrawerSwitchSection.visibility = View.GONE
            binding.containerDrawerSystemsList.removeAllViews()
        } else {
            binding.layoutDrawerSwitchSection.visibility = View.VISIBLE
            binding.containerDrawerSystemsList.removeAllViews()

            // Discovered Wi-Fi Devices
            for (device in devices) {
                val itemBinding = ItemDiscoveredSystemBinding.inflate(layoutInflater, binding.containerDrawerSystemsList, false)
                val isCurrentTarget = isConnected && currentConnectionType == ConnectionType.WIFI &&
                        (device.ip == lastSavedIp && device.port == lastSavedPort)

                itemBinding.tvSystemName.text = device.name
                itemBinding.tvSystemIp.text = "Wi-Fi · ${device.ip}:${device.port}"
                itemBinding.ivSystemIcon.setImageResource(R.drawable.ic_laptop)
                itemBinding.btnSystemDelete.visibility = View.GONE
                itemBinding.dividerBottom.visibility = View.VISIBLE

                if (isCurrentTarget) {
                    itemBinding.tvSystemActionBadge.text = "Active"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connected_bg)
                } else {
                    itemBinding.tvSystemActionBadge.text = "Connect"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_white))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connect_bg)
                }

                val switchAction = View.OnClickListener {
                    switchToSystem(device)
                }
                itemBinding.cardSystemItem.setOnClickListener(switchAction)
                itemBinding.tvSystemActionBadge.setOnClickListener(switchAction)

                binding.containerDrawerSystemsList.addView(itemBinding.root)
            }

            // Paired Bluetooth Devices
            for ((index, device) in pairedBt.withIndex()) {
                val itemBinding = ItemDiscoveredSystemBinding.inflate(layoutInflater, binding.containerDrawerSystemsList, false)
                val deviceName = try { device.name ?: device.address } catch (_: Exception) { device.address }
                val isCurrentTarget = isConnected && currentConnectionType == ConnectionType.BLUETOOTH &&
                        (btManager.getConnectedDevice()?.address == device.address || activeBtDevice?.address == device.address)

                itemBinding.tvSystemName.text = deviceName
                itemBinding.tvSystemIp.text = "Bluetooth HID · ${device.address}"
                itemBinding.ivSystemIcon.setImageResource(R.drawable.ic_laptop)
                itemBinding.btnSystemDelete.visibility = View.GONE
                itemBinding.dividerBottom.visibility = if (index == pairedBt.size - 1) View.GONE else View.VISIBLE

                if (isCurrentTarget) {
                    itemBinding.tvSystemActionBadge.text = "Active"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connected_bg)
                } else {
                    itemBinding.tvSystemActionBadge.text = "Connect"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_white))
                    itemBinding.tvSystemActionBadge.setBackgroundResource(R.drawable.btn_device_connect_bg)
                }

                val switchAction = View.OnClickListener {
                    connectBluetoothDevice(device)
                }
                itemBinding.cardSystemItem.setOnClickListener(switchAction)
                itemBinding.tvSystemActionBadge.setOnClickListener(switchAction)

                binding.containerDrawerSystemsList.addView(itemBinding.root)
            }
        }

        renderRecentDevices()
    }

    private fun switchToSystem(device: DiscoveredDevice) {
        vibrate(35)
        Toast.makeText(this, "Controlling ${device.name}...", Toast.LENGTH_SHORT).show()

        if (::btManager.isInitialized && btManager.isConnected()) {
            btManager.disconnectDevice()
        }
        currentConnectionType = ConnectionType.WIFI

        activeSystemName = device.name
        lastSavedIp = device.ip
        lastSavedPort = device.port

        getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_ip", device.ip)
            .putString("last_port", device.port.toString())
            .putString("last_system_name", device.name)
            .putString("last_connection_type", "wifi")
            .apply()

        binding.etConnectIp.setText(device.ip)
        binding.etConnectPort.setText(device.port.toString())
        binding.etDrawerIp.setText(device.ip)
        binding.etDrawerPort.setText(device.port.toString())
        binding.tvSettingsIpValue.text = device.ip
        binding.tvSettingsPortValue.text = device.port.toString()

        saveToRecentDevices(device.name, device.ip, device.port)

        binding.layoutConnectScreen.visibility = View.GONE
        isDrawerExpanded = false
        binding.layoutExpandedDrawer.visibility = View.GONE
        binding.btnHeaderChevron.setImageResource(R.drawable.ic_chevron_down)

        client.connect(device.ip, device.port)
        renderDiscoveredSystems(discoveryManager.getDiscoveredDevices())
    }

    // =========================================================================
    // DISPATCH FUNCTIONS (Unified Wi-Fi LAN & Bluetooth HID Routing)
    // =========================================================================
    private fun dispatchMouseMove(dx: Float, dy: Float) {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.sendMouseMove(dx, dy)
        } else {
            client.sendMove(dx, dy)
        }
    }

    private fun dispatchFlushRemainder() {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.flushRemainder()
        } else {
            client.flushRemainder()
        }
    }

    private fun dispatchMouseClick(button: String) {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.sendMouseClick(button)
        } else {
            client.sendClick(button)
        }
    }

    private fun dispatchMouseDown(button: String) {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.sendMouseDown(button)
        } else {
            client.sendMouseDown(button)
        }
    }

    private fun dispatchMouseUp(button: String) {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.sendMouseUp(button)
        } else {
            client.sendMouseUp(button)
        }
    }

    private fun dispatchScroll(scrollDx: Float, scrollDy: Float) {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.sendScroll(scrollDy)
        } else {
            client.sendScroll(scrollDx, scrollDy)
        }
    }

    private fun dispatchSlide(next: Boolean) {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.sendSlideNavigation(next)
        } else {
            if (next) client.sendNextSlide() else client.sendPrevSlide()
        }
    }

    private fun dispatchVolume(up: Boolean) {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.sendVolume(up)
        } else {
            if (up) client.sendVolumeUp() else client.sendVolumeDown()
        }
    }

    private fun dispatchKey(keyName: String) {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.sendKey(keyName)
        } else {
            client.sendKey(keyName)
        }
    }

    private fun dispatchText(text: String) {
        if (currentConnectionType == ConnectionType.BLUETOOTH && ::btManager.isInitialized && btManager.isConnected()) {
            btManager.sendText(text)
        } else {
            if (text == " ") {
                dispatchKey("space")
            } else if (text.contains(" ")) {
                for (char in text) {
                    if (char == ' ') {
                        dispatchKey("space")
                    } else {
                        client.sendText(char.toString())
                    }
                }
            } else {
                client.sendText(text)
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.layoutRemoteTyping.visibility == View.VISIBLE -> {
                        closeRemoteTyping()
                    }
                    binding.layoutConfirmationModal.visibility == View.VISIBLE -> {
                        binding.layoutConfirmationModal.visibility = View.GONE
                    }
                    binding.layoutSettingsScreen.visibility == View.VISIBLE -> {
                        binding.layoutSettingsScreen.visibility = View.GONE
                    }
                    binding.layoutConnectScreen.visibility == View.VISIBLE -> {
                        binding.layoutConnectScreen.visibility = View.GONE
                    }
                    isPresentationMode -> {
                        isPresentationMode = false
                        updatePresentationState()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stop()
        client.disconnect()
        if (::btManager.isInitialized) {
            btManager.destroy()
        }
    }
}

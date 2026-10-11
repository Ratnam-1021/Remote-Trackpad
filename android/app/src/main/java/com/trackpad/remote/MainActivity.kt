package com.trackpad.remote

import android.Manifest
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
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trackpad.remote.databinding.ActivityMainBinding
import com.trackpad.remote.databinding.ItemDiscoveredSystemBinding
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BT_PERMS = 101
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: TrackpadClient
    private lateinit var discoveryManager: DeviceDiscoveryManager
    private lateinit var bluetoothHidManager: BluetoothHidManager
    private var vibrator: Vibrator? = null

    // Preferences & Settings State
    private var sensitivity = 1.2f
    private var hapticEnabled = true
    private var autoConnectEnabled = false
    private var lastSavedIp = "10.90.63.215"
    private var lastSavedPort = 8765
    private var activeSystemName = ""

    // UI View State
    private var isConnected = false
    private var isBluetoothConnected = false
    private var activeConnectionMode = "bluetooth" // "bluetooth" or "wifi"
    private var isDrawerExpanded = false
    private var isPresentationMode = false
    private var isConnectionLost = false

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initVibrator()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        loadPreferences()
        setupNetworkClient()
        setupBluetoothHid()
        setupDiscovery()
        setupHeaderInteractions()
        setupDockBar()
        setupConnectScreen()
        setupRemoteTyping()
        setupSettingsScreen()
        setupTouchpadGesture()
        setupPresentationGestures()

        checkAndRequestBluetoothPermissions()

        if (autoConnectEnabled && lastSavedIp.isNotEmpty()) {
            client.connect(lastSavedIp, lastSavedPort)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::bluetoothHidManager.isInitialized) {
            renderBluetoothDevices()
        }
    }

    private fun checkAndRequestBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_BT_PERMS)
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_PERMS) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                bluetoothHidManager.init()
                renderBluetoothDevices()
                Toast.makeText(this, "Bluetooth permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Nearby device permission is needed for Bluetooth mode", Toast.LENGTH_LONG).show()
            }
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
                    val displayName = if (activeSystemName.isNotEmpty()) activeSystemName else lastSavedIp
                    binding.tvHeaderTitle.text = displayName
                    binding.tvHeaderSubtitle.text = "$lastSavedIp:$lastSavedPort"
                    binding.layoutStatusBadge.visibility = View.VISIBLE
                    binding.tvStatusBadge.text = "Connected"
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.success_green)
                    binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    binding.layoutStatusBadge.setBackgroundResource(R.drawable.status_pill_bg)

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

    // =========================================================================
    // HEADER & EXPANDED DRAWER INTERACTIONS (Screens 1, 3, 6)
    // =========================================================================
    private fun setupHeaderInteractions() {
        binding.headerSummaryRow.setOnClickListener {
            if (isConnected) {
                // Toggle expanded drawer
                isDrawerExpanded = !isDrawerExpanded
                binding.layoutExpandedDrawer.visibility = if (isDrawerExpanded) View.VISIBLE else View.GONE
                binding.btnHeaderChevron.setImageResource(
                    if (isDrawerExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
                )
            } else {
                // Open Connect Screen (Screen 2)
                binding.layoutConnectScreen.visibility = View.VISIBLE
                val currentInput = binding.etConnectIp.text.toString().trim()
                val list = mutableListOf<String>()
                if (currentInput.isNotEmpty()) list.add(currentInput)
                if (lastSavedIp.isNotEmpty()) list.add(lastSavedIp)
                discoveryManager.triggerImmediateProbe(list)
            }
        }

        binding.btnHeaderChevron.setOnClickListener {
            binding.headerSummaryRow.performClick()
        }

        binding.btnDrawerDisconnect.setOnClickListener {
            if (isBluetoothConnected) {
                bluetoothHidManager.disconnectDevice()
            }
            client.disconnect()
            isDrawerExpanded = false
            binding.layoutExpandedDrawer.visibility = View.GONE
            binding.btnHeaderChevron.setImageResource(R.drawable.ic_chevron_down)
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
            if (activeConnectionMode == "bluetooth") {
                val paired = bluetoothHidManager.getPairedDevices()
                if (paired.isNotEmpty()) {
                    bluetoothHidManager.connectDevice(paired.first())
                }
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
            activeConnectionMode = "wifi"
            client.connect(ip, port)
        }

        binding.btnTabBluetooth.setOnClickListener {
            vibrate(20)
            binding.btnTabBluetooth.setBackgroundResource(R.drawable.dock_btn_active)
            binding.btnTabBluetooth.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            binding.btnTabWifi.setBackgroundResource(android.R.color.transparent)
            binding.btnTabWifi.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            binding.layoutBluetoothContent.visibility = View.VISIBLE
            binding.layoutWifiContent.visibility = View.GONE

            if (checkAndRequestBluetoothPermissions()) {
                bluetoothHidManager.init()
                renderBluetoothDevices()
            }
        }

        binding.btnTabWifi.setOnClickListener {
            vibrate(20)
            binding.btnTabWifi.setBackgroundResource(R.drawable.dock_btn_active)
            binding.btnTabWifi.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            binding.btnTabBluetooth.setBackgroundResource(android.R.color.transparent)
            binding.btnTabBluetooth.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            binding.layoutWifiContent.visibility = View.VISIBLE
            binding.layoutBluetoothContent.visibility = View.GONE
        }

        binding.btnOpenBluetoothSettings.setOnClickListener {
            vibrate(25)
            if (!checkAndRequestBluetoothPermissions()) {
                return@setOnClickListener
            }
            try {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Bluetooth settings", Toast.LENGTH_SHORT).show()
            }
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

        renderRecentDevices()
        renderBluetoothDevices()
    }

    private fun connectToRecent(name: String, ip: String, port: Int) {
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
                itemBinding.tvSystemIp.text = "$ip · $port"

                val isOnline = onlineDevices.any { it.ip == ip && it.port == port }
                val isCurrentlyActive = isConnected && lastSavedIp == ip && lastSavedPort == port

                if (isCurrentlyActive) {
                    itemBinding.tvSystemActionBadge.text = "ACTIVE"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_accent))
                    itemBinding.cardSystemItem.setBackgroundResource(R.drawable.dock_btn_active)
                } else if (isOnline) {
                    itemBinding.tvSystemActionBadge.text = "ONLINE"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    itemBinding.cardSystemItem.setBackgroundResource(R.drawable.dock_btn_normal)
                } else {
                    itemBinding.tvSystemActionBadge.text = "SAVED"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    itemBinding.cardSystemItem.setBackgroundResource(R.drawable.dock_btn_normal)
                }

                // Delete option for saved devices
                itemBinding.btnSystemDelete.visibility = View.VISIBLE
                itemBinding.btnSystemDelete.setOnClickListener {
                    deleteRecentDevice(ip, port)
                }

                itemBinding.cardSystemItem.setOnClickListener {
                    connectToRecent(name, ip, port)
                }

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

        binding.btnTypingBackspace.setOnClickListener {
            vibrate(20)
            dispatchKey("backspace")
            val current = binding.etTypingInput.text.toString()
            if (current.isNotEmpty()) {
                binding.etTypingInput.setText(current.substring(0, current.length - 1))
                binding.etTypingInput.setSelection(binding.etTypingInput.text.length)
            }
        }

        binding.btnTypingEnter.setOnClickListener {
            vibrate(25)
            dispatchKey("enter")
            binding.etTypingInput.setText("")
        }

        binding.btnTypingSend.setOnClickListener {
            val text = binding.etTypingInput.text.toString()
            if (text.isNotEmpty()) {
                vibrate(35)
                dispatchText(text)
                binding.etTypingInput.setText("")
                closeRemoteTyping()
                // Show confirmation modal (Screen 7)
                binding.layoutConfirmationModal.visibility = View.VISIBLE
            }
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
        binding.etTypingInput.requestFocus()
    }

    private fun closeRemoteTyping() {
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
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        val dist = hypot(dx, dy)
                        totalMovement += dist

                        if (totalMovement > touchSlop) {
                            isDragging = true
                            val distance = hypot(dx, dy)
                            val factor = if (distance > 25f) 1.55f else 1.0f
                            val sendDx = dx * sensitivity * factor
                            val sendDy = dy * sensitivity * factor
                            dispatchMouseMove(sendDx, sendDy)
                        }

                        lastX = event.x
                        lastY = event.y

                    } else if (pointerCount >= 2) {
                        isTwoFingerGesture = true
                        val avgX = (event.getX(0) + event.getX(1)) / 2f
                        val avgY = (event.getY(0) + event.getY(1)) / 2f

                        val scrollDx = avgX - lastScrollX
                        val scrollDy = avgY - lastScrollY

                        if (hypot(scrollDx, scrollDy) > 2f) {
                            dispatchScroll(scrollDx * 0.8f, scrollDy * 0.8f)
                        }

                        lastScrollX = avgX
                        lastScrollY = avgY
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (pointerCount == 2 && isTwoFingerGesture) {
                        val duration = System.currentTimeMillis() - downTime
                        if (duration < 280 && totalMovement < touchSlop * 2) {
                            vibrate(30)
                            dispatchMouseClick("right")
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - downTime
                    if (!isTwoFingerGesture && !isDragging && duration < 240) {
                        vibrate(20)
                        dispatchMouseClick("left")
                    } else if (isDragging) {
                        dispatchMouseUp("left")
                    }
                    isDragging = false
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
        // 1. Connect Screen List
        if (devices.isEmpty()) {
            binding.tvDiscoveredCount.text = "Searching..."
            binding.tvDiscoveredEmptyHint.visibility = View.VISIBLE
            binding.containerDiscoveredList.removeAllViews()
        } else {
            binding.tvDiscoveredCount.text = "${devices.size} online"
            binding.tvDiscoveredEmptyHint.visibility = View.GONE
            binding.containerDiscoveredList.removeAllViews()

            for (device in devices) {
                val itemBinding = ItemDiscoveredSystemBinding.inflate(layoutInflater, binding.containerDiscoveredList, false)
                val isCurrentTarget = isConnected && (device.ip == lastSavedIp && device.port == lastSavedPort)

                itemBinding.tvSystemName.text = device.name
                itemBinding.tvSystemIp.text = "${device.ip} · ${device.port} · ${device.os}"

                if (isCurrentTarget) {
                    itemBinding.tvSystemActionBadge.text = "CONNECTED"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_accent))
                    itemBinding.cardSystemItem.setBackgroundResource(R.drawable.dock_btn_active)
                } else {
                    itemBinding.tvSystemActionBadge.text = "CONTROL"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    itemBinding.cardSystemItem.setBackgroundResource(R.drawable.dock_btn_normal)
                }

                itemBinding.cardSystemItem.setOnClickListener {
                    switchToSystem(device)
                }

                binding.containerDiscoveredList.addView(itemBinding.root)
            }
        }

        // 2. Drawer Quick Switch List
        if (devices.isEmpty()) {
            binding.layoutDrawerSwitchSection.visibility = View.GONE
            binding.containerDrawerSystemsList.removeAllViews()
        } else {
            binding.layoutDrawerSwitchSection.visibility = View.VISIBLE
            binding.containerDrawerSystemsList.removeAllViews()

            for (device in devices) {
                val itemBinding = ItemDiscoveredSystemBinding.inflate(layoutInflater, binding.containerDrawerSystemsList, false)
                val isCurrentTarget = isConnected && (device.ip == lastSavedIp && device.port == lastSavedPort)

                itemBinding.tvSystemName.text = device.name
                itemBinding.tvSystemIp.text = "${device.ip} · ${device.port}"

                if (isCurrentTarget) {
                    itemBinding.tvSystemActionBadge.text = "ACTIVE"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_accent))
                    itemBinding.cardSystemItem.setBackgroundResource(R.drawable.dock_btn_active)
                } else {
                    itemBinding.tvSystemActionBadge.text = "SWITCH"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_accent))
                    itemBinding.cardSystemItem.setBackgroundResource(R.drawable.dock_btn_normal)
                }

                itemBinding.cardSystemItem.setOnClickListener {
                    switchToSystem(device)
                }

                binding.containerDrawerSystemsList.addView(itemBinding.root)
            }
        }

        renderRecentDevices()
    }

    private fun switchToSystem(device: DiscoveredDevice) {
        vibrate(35)
        Toast.makeText(this, "Controlling ${device.name}...", Toast.LENGTH_SHORT).show()

        activeSystemName = device.name
        lastSavedIp = device.ip
        lastSavedPort = device.port

        getSharedPreferences("remote_trackpad_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("last_ip", device.ip)
            .putString("last_port", device.port.toString())
            .putString("last_system_name", device.name)
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
    // BLUETOOTH HID & UNIFIED DUAL-MODE DISPATCH
    // =========================================================================
    private fun setupBluetoothHid() {
        bluetoothHidManager = BluetoothHidManager(
            context = this,
            onConnectionStateChanged = { connected, deviceName ->
                runOnUiThread {
                    isBluetoothConnected = connected
                    if (connected) {
                        activeConnectionMode = "bluetooth"
                        val name = deviceName ?: "Bluetooth Device"
                        binding.tvHeaderTitle.text = name
                        binding.tvHeaderSubtitle.text = "Bluetooth HID (Zero-Install)"
                        binding.layoutStatusBadge.visibility = View.VISIBLE
                        binding.tvStatusBadge.text = "Connected"
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_accent)
                        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_accent))

                        binding.layoutConnectionLostView.visibility = View.GONE
                        if (isPresentationMode) {
                            binding.layoutPresentationView.visibility = View.VISIBLE
                            binding.touchpadSurface.visibility = View.GONE
                        } else {
                            binding.touchpadSurface.visibility = View.VISIBLE
                            binding.layoutPresentationView.visibility = View.GONE
                        }
                        vibrate(40)
                        Toast.makeText(this, "Connected via Bluetooth to $name", Toast.LENGTH_SHORT).show()
                    } else {
                        if (activeConnectionMode == "bluetooth") {
                            if (!isConnected) {
                                binding.layoutStatusBadge.visibility = View.GONE
                                binding.tvHeaderTitle.text = "Not Connected"
                                binding.tvHeaderSubtitle.text = "Tap to connect"
                            }
                        }
                    }
                    renderBluetoothDevices()
                }
            },
            onSupportedState = { supported, message ->
                runOnUiThread {
                    if (!supported && message.isNotEmpty()) {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        bluetoothHidManager.init()
    }

    @SuppressLint("MissingPermission")
    private fun renderBluetoothDevices() {
        if (!::bluetoothHidManager.isInitialized) return
        val paired = bluetoothHidManager.getPairedDevices()
        if (paired.isEmpty()) {
            binding.tvBluetoothEmptyHint.visibility = View.VISIBLE
            binding.containerBluetoothDevicesList.removeAllViews()
        } else {
            binding.tvBluetoothEmptyHint.visibility = View.GONE
            binding.containerBluetoothDevicesList.removeAllViews()

            for (device in paired) {
                val itemBinding = ItemDiscoveredSystemBinding.inflate(layoutInflater, binding.containerBluetoothDevicesList, false)
                val deviceName = try { device.name ?: device.address ?: "Computer" } catch (_: Exception) { device.address ?: "Computer" }
                itemBinding.tvSystemName.text = deviceName
                itemBinding.tvSystemIp.text = "Bluetooth · ${device.address}"
                itemBinding.btnSystemDelete.visibility = View.GONE

                val isCurrent = isBluetoothConnected && bluetoothHidManager.getConnectedDeviceName() == deviceName

                if (isCurrent) {
                    itemBinding.tvSystemActionBadge.text = "ACTIVE"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.primary_accent))
                    itemBinding.cardSystemItem.setBackgroundResource(R.drawable.dock_btn_active)
                } else {
                    itemBinding.tvSystemActionBadge.text = "CONNECT"
                    itemBinding.tvSystemActionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    itemBinding.cardSystemItem.setBackgroundResource(R.drawable.dock_btn_normal)
                }

                itemBinding.cardSystemItem.setOnClickListener {
                    vibrate(30)
                    activeConnectionMode = "bluetooth"
                    bluetoothHidManager.connectDevice(device)
                    Toast.makeText(this, "Connecting to $deviceName...", Toast.LENGTH_SHORT).show()
                    binding.layoutConnectScreen.visibility = View.GONE
                }

                binding.containerBluetoothDevicesList.addView(itemBinding.root)
            }
        }
    }

    // =========================================================================
    // UNIFIED DISPATCH FUNCTIONS (Bluetooth HID or Wi-Fi LAN)
    // =========================================================================
    private fun dispatchMouseMove(dx: Float, dy: Float) {
        if (isBluetoothConnected && activeConnectionMode == "bluetooth") {
            bluetoothHidManager.sendMouseMove(dx, dy)
        } else {
            client.sendMove(dx, dy)
        }
    }

    private fun dispatchMouseClick(button: String) {
        if (isBluetoothConnected && activeConnectionMode == "bluetooth") {
            bluetoothHidManager.sendMouseClick(button)
        } else {
            client.sendClick(button)
        }
    }

    private fun dispatchMouseDown(button: String) {
        if (isBluetoothConnected && activeConnectionMode == "bluetooth") {
            bluetoothHidManager.sendMouseDown(button)
        } else {
            client.sendMouseDown(button)
        }
    }

    private fun dispatchMouseUp(button: String) {
        if (isBluetoothConnected && activeConnectionMode == "bluetooth") {
            bluetoothHidManager.sendMouseUp(button)
        } else {
            client.sendMouseUp(button)
        }
    }

    private fun dispatchScroll(scrollDx: Float, scrollDy: Float) {
        if (isBluetoothConnected && activeConnectionMode == "bluetooth") {
            bluetoothHidManager.sendScroll(scrollDy)
        } else {
            client.sendScroll(scrollDx, scrollDy)
        }
    }

    private fun dispatchSlide(next: Boolean) {
        if (isBluetoothConnected && activeConnectionMode == "bluetooth") {
            bluetoothHidManager.sendSlideNavigation(next)
        } else {
            if (next) client.sendNextSlide() else client.sendPrevSlide()
        }
    }

    private fun dispatchVolume(up: Boolean) {
        if (isBluetoothConnected && activeConnectionMode == "bluetooth") {
            bluetoothHidManager.sendVolume(up)
        } else {
            if (up) client.sendVolumeUp() else client.sendVolumeDown()
        }
    }

    private fun dispatchKey(keyName: String) {
        if (isBluetoothConnected && activeConnectionMode == "bluetooth") {
            bluetoothHidManager.sendKey(keyName)
        } else {
            client.sendKey(keyName)
        }
    }

    private fun dispatchText(text: String) {
        if (isBluetoothConnected && activeConnectionMode == "bluetooth") {
            bluetoothHidManager.sendText(text)
        } else {
            client.sendText(text)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stop()
        bluetoothHidManager.destroy()
        client.disconnect()
    }
}

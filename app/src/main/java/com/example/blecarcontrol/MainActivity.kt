package com.example.blecarcontrol

import android.app.AlertDialog
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private var bleService: BleForegroundService? = null
    private var bound = false

    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvLockStatus: TextView
    private lateinit var tvDoorStatus: TextView

    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressMs = 600L

    // ================= Servis bilan bog'lanish =================
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleForegroundService.LocalBinder
            bleService = binder.getService()
            bound = true
            bleService?.connectToDevice()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            bleService = null
        }
    }

    // ================= BLE holat yangilanishlarini qabul qilish =================
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                BleForegroundService.ACTION_STATUS_UPDATE -> {
                    val connected = intent.getBooleanExtra(BleForegroundService.EXTRA_CONNECTED, false)
                    val locked = intent.getBooleanExtra(BleForegroundService.EXTRA_LOCKED, true)
                    val doorOpen = intent.getBooleanExtra(BleForegroundService.EXTRA_DOOR_OPEN, false)
                    updateStatusUi(connected, locked, doorOpen)
                }
                BleForegroundService.ACTION_ALARM_TRIGGERED -> {
                    // Sirena BleForegroundService ichida ishga tushadi;
                    // bu yerda xohlasangiz UI'da qo'shimcha ogohlantirish ko'rsatishingiz mumkin.
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvLockStatus = findViewById(R.id.tvLockStatus)
        tvDoorStatus = findViewById(R.id.tvDoorStatus)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        // 5 ta tugma - har biriga qisqa/uzoq bosish kodlari biriktirildi
        setupButton(findViewById(R.id.btnLock), "LCK1", "LCK2")
        setupButton(findViewById(R.id.btnUnlock), "ULK1", "ULK2")
        setupButton(findViewById(R.id.btnEngine), "ENG1", "ENG2")
        setupButton(findViewById(R.id.btnTrunk), "TRK1", "TRK2")
        setupButton(findViewById(R.id.btnFind), "FND1", "SOS1")

        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver,
            IntentFilter().apply {
                addAction(BleForegroundService.ACTION_STATUS_UPDATE)
                addAction(BleForegroundService.ACTION_ALARM_TRIGGERED)
            }
        )
        val intent = Intent(this, BleForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    // ============================================================
    //         QISQA / UZOQ BOSISHNI ANIQLASH (bitta tugma uchun)
    // ============================================================
    private fun setupButton(button: ImageButton, shortCode: String, longCode: String) {
        var longTriggered = false
        val longPressRunnable = Runnable {
            longTriggered = true
            bleService?.sendCommand(longCode)
            button.alpha = 0.5f
        }

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longTriggered = false
                    button.alpha = 0.7f
                    longPressHandler.postDelayed(longPressRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    button.alpha = 1.0f
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!longTriggered) {
                        bleService?.sendCommand(shortCode)
                    }
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    button.alpha = 1.0f
                    longPressHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    // ============================================================
    //                     HOLATNI UI'DA KO'RSATISH
    // ============================================================
    private fun updateStatusUi(connected: Boolean, locked: Boolean, doorOpen: Boolean) {
        if (connected) {
            tvConnectionStatus.text = getString(R.string.status_connected)
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            tvConnectionStatus.text = getString(R.string.status_disconnected)
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        }

        tvLockStatus.text = if (locked) getString(R.string.lock_locked) else getString(R.string.lock_unlocked)

        tvDoorStatus.text = if (doorOpen) getString(R.string.door_open) else getString(R.string.door_closed)
        tvDoorStatus.setTextColor(
            ContextCompat.getColor(this, if (doorOpen) R.color.accent_orange else R.color.text_gray)
        )
    }

    // ============================================================
    //                  SOZLAMALAR (BLE KODI) DIALOGI
    // ============================================================
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val editText = view.findViewById<EditText>(R.id.etBleCode)
        editText.setText(Prefs.getBleCode(this))

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val code = editText.text.toString().trim()
                Prefs.setBleCode(this, code)
                bleService?.disconnect()
                longPressHandler.postDelayed({ bleService?.connectToDevice() }, 500)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ============================================================
    //                      RUXSATLARNI SO'RASH
    // ============================================================
    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needed.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            needed.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            bleService?.connectToDevice()
        }
    }
}

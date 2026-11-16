package com.example.blecarcontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.UUID

/**
 * ESP32 bilan BLE orqali ulanishni boshqaradigan fon xizmati (Foreground Service).
 * Bu xizmat ilova "orqa fonda" yoki ekran o'chiq bo'lsa ham ishlashda davom
 * etadi - shu sababli "mashinaga tegilsa" degan signal istalgan vaqtda
 * qabul qilinib, telefonda sirena chalinishi mumkin.
 *
 * ESLATMA: SERVICE_UUID / CHAR_UUID ESP32 kodidagi bilan ANIQ bir xil
 * bo'lishi shart (ikkalasida ham FFE0 / FFE1 ishlatilgan).
 */
class BleForegroundService : Service() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val ACTION_STATUS_UPDATE = "com.example.blecarcontrol.STATUS_UPDATE"
        const val ACTION_ALARM_TRIGGERED = "com.example.blecarcontrol.ALARM_TRIGGERED"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_LOCKED = "locked"
        const val EXTRA_DOOR_OPEN = "door_open"

        private const val CHANNEL_ID_SERVICE = "ble_service_channel"
        private const val CHANNEL_ID_ALARM = "ble_alarm_channel"
        private const val NOTIF_ID_SERVICE = 1
        private const val NOTIF_ID_ALARM = 2
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleForegroundService = this@BleForegroundService
    }

    private val binder = LocalBinder()
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var isLocked = true
    private var isDoorOpen = false

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIF_ID_SERVICE, buildServiceNotification(getString(R.string.status_disconnected)))
    }

    override fun onDestroy() {
        stopScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: SecurityException) {
        }
        super.onDestroy()
    }

    // ============================================================
    //                    ULANISHNI BOSHLASH
    // ============================================================
    fun connectToDevice() {
        if (isConnected) return
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) return

        val deviceName = Prefs.getBleCode(this)
        scanner = adapter.bluetoothLeScanner

        val filters = if (deviceName.isNotBlank()) {
            listOf(ScanFilter.Builder().setDeviceName(deviceName).build())
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanning = true
            scanner?.startScan(filters, settings, scanCallback)
            // 15 soniyadan keyin avtomatik skanerlashni to'xtatish
            handler.postDelayed({ stopScan() }, 15000)
        } catch (_: SecurityException) {
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            try {
                bluetoothGatt = result.device.connectGatt(this@BleForegroundService, false, gattCallback)
            } catch (_: SecurityException) {
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
        }
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
        } catch (_: SecurityException) {
        }
    }

    // ============================================================
    //                    GATT HODISALARI
    // ============================================================
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    gatt.discoverServices()
                } catch (_: SecurityException) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                writeCharacteristic = null
                broadcastStatus()
                updateServiceNotification(getString(R.string.status_disconnected))
                try {
                    gatt.close()
                } catch (_: SecurityException) {
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID) ?: return
            val char = service.getCharacteristic(CHAR_UUID) ?: return
            writeCharacteristic = char

            try {
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(CCCD_UUID)
                cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                cccd?.let { gatt.writeDescriptor(it) }
            } catch (_: SecurityException) {
            }

            isConnected = true
            broadcastStatus()
            updateServiceNotification(getString(R.string.status_connected))

            // Ulangandan keyin "BLE kodi"ni autentifikatsiya sifatida yuboramiz.
            // ESP32 tomonida shu kodni tekshirib, mos kelmasa buyruqlarni rad etishi mumkin.
            val code = Prefs.getBleCode(this@BleForegroundService)
            if (code.isNotBlank()) {
                handler.postDelayed({ sendCommand("AUTH:$code") }, 400)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val text = String(characteristic.value)
            handleIncoming(text)
        }
    }

    // ============================================================
    //              QABUL QILINGAN MA'LUMOTNI QAYTA ISHLASH
    // ============================================================
    // Kutilayotgan formatlar (ESP32 kodi bilan mos):
    //   "STATUS,LOCK=1,DOOR=0,ENG=0"
    //   "ALARM,TAMPER=1"
    private fun handleIncoming(text: String) {
        when {
            text.startsWith("STATUS") -> {
                val parts = text.split(",")
                for (p in parts) {
                    when {
                        p.startsWith("LOCK=") -> isLocked = p.substringAfter("=") == "1"
                        p.startsWith("DOOR=") -> isDoorOpen = p.substringAfter("=") == "1"
                    }
                }
                broadcastStatus()
            }
            text.startsWith("ALARM") -> {
                triggerSiren()
                val intent = Intent(ACTION_ALARM_TRIGGERED)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
        }
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_UPDATE)
        intent.putExtra(EXTRA_CONNECTED, isConnected)
        intent.putExtra(EXTRA_LOCKED, isLocked)
        intent.putExtra(EXTRA_DOOR_OPEN, isDoorOpen)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // ============================================================
    //                    BUYRUQ YUBORISH
    // ============================================================
    fun sendCommand(code: String) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return
        try {
            char.value = code.toByteArray()
            gatt.writeCharacteristic(char)
        } catch (_: SecurityException) {
        }
    }

    // ============================================================
    //          SIRENA (TELEFONDA BALAND SIGNAL CHALISH)
    // ============================================================
    private fun triggerSiren() {
        // Baland ovozli naycha-signal (ToneGenerator) - qo'shimcha audio fayl shart emas
        Thread {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            repeat(10) {
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                Thread.sleep(500)
            }
            tone.release()
        }.start()

        // Tebranish
        vibrate()

        // Yuqori muhimlikdagi bildirishnoma (ekran qulflangan bo'lsa ham ko'rinadi)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALARM)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(getString(R.string.alarm_notification_title))
            .setContentText(getString(R.string.alarm_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_ALARM, notification)
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 400, 200, 400, 200, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        }
    }

    // ============================================================
    //                 BILDIRISHNOMA KANALLARI
    // ============================================================
    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel = NotificationChannel(
            CHANNEL_ID_SERVICE, "BLE xizmati", NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(serviceChannel)

        val alarmChannel = NotificationChannel(
            CHANNEL_ID_ALARM, "Signalizatsiya ogohlantirishi", NotificationManager.IMPORTANCE_HIGH
        )
        alarmChannel.setSound(
            android.media.RingtoneManager.getActualDefaultRingtoneUri(this, android.media.RingtoneManager.TYPE_ALARM),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
        alarmChannel.enableVibration(true)
        nm.createNotificationChannel(alarmChannel)
    }

    private fun buildServiceNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateServiceNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_SERVICE, buildServiceNotification(statusText))
    }
}

package com.rohit.supertripperpod

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.UUID

@SuppressLint("MissingPermission")
object TripperBleEngine {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val handler = Handler(Looper.getMainLooper())
    private var lastPacket: ByteArray? = null

    var onStatus: ((String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onNav: ((String, String, String, String) -> Unit)? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val pkt = lastPacket
            if (gatt != null && writeChar != null && pkt != null) writeRaw(pkt)
            handler.postDelayed(this, 1500)
        }
    }

    fun connect(context: Context) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        onStatus?.invoke("Scanning RE_DISP...")
        val paired = adapter.bondedDevices.firstOrNull {
            it.name?.contains("RE_") == true || it.name?.contains("Tripper") == true
        }
        if (paired != null) {
            onStatus?.invoke("Connecting ${paired.name}...")
            gatt = paired.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                val name = dev.name ?: ""
                if (name.contains("RE_") || name.contains("Tripper")) {
                    scanner.stopScan(this)
                    onStatus?.invoke("Bonding $name...")
                    if (dev.bondState != BluetoothDevice.BOND_BONDED) dev.createBond()
                    handler.postDelayed({
                        gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    }, 2000)
                }
            }
        }
        scanner.startScan(callback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus?.invoke("Connected! Discovering...")
                handler.postDelayed({ g.discoverServices() }, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onStatus?.invoke("RE_DISP Disconnected")
                handler.removeCallbacks(heartbeatRunnable)
                writeChar = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var notifyCharToEnable: BluetoothGattCharacteristic? = null
            for (service in g.services) {
                val suuid = service.uuid.toString().lowercase()
                if (suuid.startsWith("00001800") || suuid.startsWith("00001801")) continue
                for (c in service.characteristics) {
                    val p = c.properties
                    if ((p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && notifyCharToEnable == null) {
                        notifyCharToEnable = c
                    }
                    if (((p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                        (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) && writeChar == null
                    ) {
                        writeChar = c
                    }
                }
            }
            if (notifyCharToEnable != null) {
                g.setCharacteristicNotification(notifyCharToEnable, true)
                val desc = notifyCharToEnable.getDescriptor(CCCD_UUID)
                if (desc != null) {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                }
            }
            if (writeChar != null) {
                onStatus?.invoke("POD ACTIVE ✓")
                handler.postDelayed({
                    val initFrame = byteArrayOf(0x01, 0x01, 0x00, 0x64, 0x01, 0x00, 0x00, 0x00)
                    lastPacket = initFrame
                    writeRaw(initFrame)
                    handler.post(heartbeatRunnable)
                }, 800)
            }
        }
    }

    private fun writeRaw(payload: ByteArray) {
        val c = writeChar ?: return
        val g = gatt ?: return
        c.value = payload
        c.writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        g.writeCharacteristic(c)
    }

    fun sendTbt(turnByte: Byte, arrowChar: String, distMeters: Int, distStr: String, road: String, eta: String) {
        onNav?.invoke(arrowChar, distStr, road, eta)
        val d = distMeters.coerceIn(0, 65535)
        val packet = byteArrayOf(0x01, turnByte, ((d shr 8) and 0xFF).toByte(), (d and 0xFF).toByte(), 0x01, 0x00, 0x00, 0x00)
        lastPacket = packet
        writeRaw(packet)
    }
}

class MapsNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.google.android.apps.maps") return
        val ex = sbn.notification.extras
        val distStr = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val roadStr = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val etaStr = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        if (distStr.isEmpty() && roadStr.isEmpty()) return

        var (code, symbol) = detectFromText(roadStr)
        if (code == 0x01.toByte()) {
            val bmp = extractBitmap(sbn.notification)
            if (bmp != null) {
                val bmpTurn = detectFromBitmap(bmp)
                if (bmpTurn != null) { code = bmpTurn.first; symbol = bmpTurn.second }
            }
        }
        val clean = distStr.lowercase().trim()
        val meters = try {
            when {
                clean.endsWith("km") -> (clean.replace("km", "").trim().toFloat() * 1000).toInt()
                clean.endsWith("m") -> clean.replace("m", "").trim().toInt()
                else -> 100
            }
        } catch (e: Exception) { 100 }
        TripperBleEngine.sendTbt(code, symbol, meters, distStr, roadStr, etaStr)
    }

    private fun detectFromText(text: String): Pair<Byte, String> {
        val l = text.lowercase()
        return when {
            l.contains("u-turn") -> Pair(0x08.toByte(), "↩")
            l.contains("sharp left") -> Pair(0x04.toByte(), "↙")
            l.contains("slight left") -> Pair(0x02.toByte(), "↖")
            l.contains("left") -> Pair(0x03.toByte(), "⬅")
            l.contains("sharp right") -> Pair(0x07.toByte(), "↘")
            l.contains("slight right") -> Pair(0x05.toByte(), "↗")
            l.contains("right") -> Pair(0x06.toByte(), "➡")
            l.contains("roundabout") -> Pair(0x09.toByte(), "🔄")
            l.contains("arrive") || l.contains("destination") -> Pair(0x0D.toByte(), "🏁")
            else -> Pair(0x01.toByte(), "⬆")
        }
    }

    private fun extractBitmap(notif: Notification): Bitmap? {
        val icon: Icon? = notif.getLargeIcon()
        if (icon != null) {
            val d = icon.loadDrawable(this)
            if (d is BitmapDrawable) return d.bitmap
        }
        @Suppress("DEPRECATION")
        return notif.largeIcon
    }

    private fun detectFromBitmap(bmp: Bitmap): Pair<Byte, String>? {
        val scaled = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        var leftWeight = 0
        var rightWeight = 0
        for (y in 4..20) {
            for (x in 2..13) if (Color.alpha(scaled.getPixel(x, y)) > 128) leftWeight++
            for (x in 18..29) if (Color.alpha(scaled.getPixel(x, y)) > 128) rightWeight++
        }
        return when {
            leftWeight > rightWeight + 12 -> Pair(0x03.toByte(), "⬅")
            rightWeight > leftWeight + 12 -> Pair(0x06.toByte(), "➡")
            else -> null
        }
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#080808"))
            setPadding(36, 48, 36, 36)
        }
        val headerTxt = TextView(this).apply {
            text = "ROHIT SINGH • METEOR 350"
            setTextColor(Color.parseColor("#FF6D00"))
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val statusTxt = TextView(this).apply {
            text = "DISCONNECTED"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        val podDial = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val size = (resources.displayMetrics.widthPixels * 0.68).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#121212"))
                setStroke(12, Color.parseColor("#2C2C2C"))
            }
            setPadding(24, 24, 24, 24)
        }
        val arrowTxt = TextView(this).apply { text = "⬆"; textSize = 60f; setTextColor(Color.parseColor("#00E676")); gravity = Gravity.CENTER }
        val distTxt = TextView(this).apply { text = "-- m"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val roadTxt = TextView(this).apply { text = "Start Google Maps Navigation"; textSize = 12f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
        val etaTxt = TextView(this).apply { text = "ETA --:--"; textSize = 11f; setTextColor(Color.parseColor("#FF9100")); gravity = Gravity.CENTER }

        podDial.addView(arrowTxt); podDial.addView(distTxt); podDial.addView(roadTxt); podDial.addView(etaTxt)

        val btnConnect = Button(this).apply { text = "🔗 Connect RE_DISP Pod"; setOnClickListener { TripperBleEngine.connect(this@MainActivity) } }
        val btnNotif = Button(this).apply { text = "🔔 Enable Google Maps Bridge"; setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        val btnTest = Button(this).apply { text = "🧪 Send Test Turn to Pod"; setOnClickListener { TripperBleEngine.sendTbt(0x03, "⬅", 250, "250 m", "Turn Left • Test", "ETA 5 min") } }

        root.addView(headerTxt); root.addView(statusTxt); root.addView(podDial); root.addView(btnConnect); root.addView(btnNotif); root.addView(btnTest)
        setContentView(root)

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)

        TripperBleEngine.onStatus = { msg -> runOnUiThread { statusTxt.text = msg } }
        TripperBleEngine.onNav = { arr, dist, road, eta ->
            runOnUiThread { arrowTxt.text = arr; distTxt.text = dist; roadTxt.text = road; etaTxt.text = eta }
        }
    }
}

package com.example.picoterminal

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

data class TerminalLog(
    val prefix: String, // "TX", "RX", "SYS"
    val message: String
)

data class ScannedBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        // UUID standard Nordic UART Service (NUS) identici a esp32_bridge.ino
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val CHUNK_SIZE = 120
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Stati reattivi osservati dalla UI Jetpack Compose
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _discoveredDevices = MutableStateFlow<List<ScannedBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ScannedBleDevice>> = _discoveredDevices

    private val _terminalLogs = MutableStateFlow<List<TerminalLog>>(
        listOf(TerminalLog("SYS", "Terminale pronto. Premi [CONN] per scansionare i dispositivi BLE."))
    )
    val terminalLogs: StateFlow<List<TerminalLog>> = _terminalLogs

    private val sendQueue = ConcurrentLinkedQueue<String>()
    private var isWriting = false

    private fun appendLog(prefix: String, msg: String) {
        val updated = _terminalLogs.value + TerminalLog(prefix, msg)
        _terminalLogs.value = if (updated.size > 200) updated.takeLast(200) else updated
    }

    fun clearLogs() {
        _terminalLogs.value = listOf(TerminalLog("SYS", "Console pulita."))
    }

    // --- 1. SCANSIONE BLE PER LISTA SELEZIONABILE ---

    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            appendLog("SYS", "ERR: Bluetooth disattivato sullo smartphone.")
            return
        }
        if (_connectionState.value == ConnectionState.SCANNING) return

        _discoveredDevices.value = emptyList()
        _connectionState.value = ConnectionState.SCANNING
        appendLog("SYS", "Scansione dispositivi BLE avviata...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, settings, scanCallback)

        // Ferma automaticamente la scansione dopo 12 secondi per risparmiare batteria
        mainHandler.postDelayed({
            if (_connectionState.value == ConnectionState.SCANNING) {
                stopScan()
                _connectionState.value = ConnectionState.DISCONNECTED
                appendLog("SYS", "Scansione completata (${_discoveredDevices.value.size} dispositivi trovati).")
            }
        }, 12_000)
    }

    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rawName = result.device.name ?: result.scanRecord?.deviceName
            val displayName = if (!rawName.isNullOrBlank()) rawName else "Dispositivo Sconosciuto"
            val address = result.device.address

            val currentList = _discoveredDevices.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.address == address }

            val item = ScannedBleDevice(
                name = displayName,
                address = address,
                rssi = result.rssi,
                device = result.device
            )

            if (existingIndex >= 0) {
                // Aggiorna nome (se arrivato nello scan response) e RSSI
                val old = currentList[existingIndex]
                val bestName = if (displayName != "Dispositivo Sconosciuto") displayName else old.name
                currentList[existingIndex] = item.copy(name = bestName)
            } else {
                currentList.add(item)
            }

            // Ordina mettendo prima i dispositivi con nome noto e segnale più forte
            _discoveredDevices.value = currentList.sortedWith(
                compareByDescending<ScannedBleDevice> { it.name != "Dispositivo Sconosciuto" }
                    .thenByDescending { it.rssi }
            )
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = ConnectionState.DISCONNECTED
            appendLog("SYS", "Errore scansione BLE (codice $errorCode).")
        }
    }

    // --- 2. CONNESSIONE AL DISPOSITIVO SCELTO ---

    fun connectToDevice(scanned: ScannedBleDevice) {
        stopScan()
        _connectionState.value = ConnectionState.CONNECTING
        appendLog("SYS", "Connessione a ${scanned.name} (${scanned.address})...")
        bluetoothGatt = scanned.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        sendQueue.clear()
        isWriting = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        rxCharacteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
        appendLog("SYS", "Disconnesso dal bridge.")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog("SYS", "Connesso al server GATT. Richiesta MTU 512...")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
                rxCharacteristic = null
                sendQueue.clear()
                isWriting = false
                appendLog("SYS", "Connessione interrotta.")
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            appendLog("SYS", "MTU negoziato: $mtu byte. Ricerca servizi...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("SYS", "Errore discovery servizi GATT.")
                return
            }

            val service = gatt.getService(NUS_SERVICE_UUID)
            if (service == null) {
                appendLog("SYS", "ERR: Questo dispositivo non espone il servizio Nordic UART (NUS).")
                disconnect()
                return
            }

            rxCharacteristic = service.getCharacteristic(NUS_RX_CHAR_UUID)
            val txCharacteristic = service.getCharacteristic(NUS_TX_CHAR_UUID)

            if (rxCharacteristic != null && txCharacteristic != null) {
                gatt.setCharacteristicNotification(txCharacteristic, true)
                val descriptor = txCharacteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
                _connectionState.value = ConnectionState.CONNECTED
                appendLog("SYS", "Bridge pronto! In attesa di comandi.")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NUS_TX_CHAR_UUID) {
                val reply = value.toString(Charsets.UTF_8).trim()
                if (reply.isNotEmpty()) {
                    appendLog("RX", reply)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == NUS_TX_CHAR_UUID) {
                @Suppress("DEPRECATION")
                val reply = characteristic.value?.toString(Charsets.UTF_8)?.trim() ?: ""
                if (reply.isNotEmpty()) {
                    appendLog("RX", reply)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            isWriting = false
            processNextInQueue()
        }
    }

    // --- 3. INVIO COMANDI E CHUNKING ---

    fun sendCommand(rawCommand: String, logToConsole: Boolean = true) {
        val cleanCmd = rawCommand.trim()
        if (cleanCmd.isEmpty()) return

        if (_connectionState.value != ConnectionState.CONNECTED || rxCharacteristic == null) {
            appendLog("SYS", "Non connesso: impossibile inviare '$cleanCmd'")
            return
        }

        if (cleanCmd.equals("//STOP", ignoreCase = true)) {
            sendQueue.clear()
        }

        if (logToConsole) {
            appendLog("TX", cleanCmd)
        }

        sendQueue.offer(cleanCmd + "\n")
        processNextInQueue()
    }

    fun sendWriteBlock(text: String, outputFlag: String = "none") {
        if (text.isEmpty()) return

        if (text.length <= CHUNK_SIZE) {
            val flagSuffix = if (outputFlag != "none") " -output $outputFlag" else ""
            sendCommand("//WRITE $text$flagSuffix")
            return
        }

        appendLog("TX", "//WRITE [Blocco di ${text.length} caratteri]")
        val chunks = text.chunked(CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            val isLast = index == chunks.lastIndex
            val flagSuffix = if (isLast && outputFlag != "none") " -output $outputFlag" else ""
            sendQueue.offer("//WRITE $chunk$flagSuffix\n")
        }
        processNextInQueue()
    }

    private fun processNextInQueue() {
        if (isWriting) return
        val payload = sendQueue.poll() ?: return
        val gatt = bluetoothGatt ?: return
        val rxChar = rxCharacteristic ?: return

        isWriting = true
        val bytes = payload.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                rxChar,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            rxChar.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }
    }
}
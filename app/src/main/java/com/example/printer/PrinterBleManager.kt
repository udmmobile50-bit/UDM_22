package com.example.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.data.entity.AppSettingsEntity
import com.example.data.entity.PaymentEntity
import com.example.data.entity.RepairEntity
import com.example.data.entity.RepairItemEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

sealed class PrinterConnectionState {
    object Disconnected : PrinterConnectionState()
    object Scanning : PrinterConnectionState()
    data class Connecting(val deviceName: String, val address: String, val statusMessage: String = "") : PrinterConnectionState()
    data class Reconnecting(val deviceName: String, val address: String, val statusMessage: String = "Reconnecting...") : PrinterConnectionState()
    data class Connected(val deviceName: String, val address: String) : PrinterConnectionState()
    data class Printing(val progressPercent: Int, val statusMessage: String = "Printing...") : PrinterConnectionState()
    data class Error(val message: String) : PrinterConnectionState()
}

enum class PrintJobStatus {
    IDLE,
    CONNECTING,
    PREPARING,
    PRINTING,
    SUCCESS,
    FAILED;

    companion object {
        val TRANSMITTING get() = PRINTING
        val RETRYING get() = CONNECTING
        val COMPLETED get() = SUCCESS
    }
}

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isP50S: Boolean,
    val deviceType: String = "BLE",
    val serviceUuids: List<String> = emptyList(),
    val manufacturerDataHex: String = "",
    val rawBytesHex: String = "",
    val packetCount: Int = 1,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

sealed class PrintResult {
    object Success : PrintResult()
    data class Error(val message: String) : PrintResult()
}

@SuppressLint("MissingPermission")
class PrinterBleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PrinterBleManager"

        @Volatile
        private var instance: PrinterBleManager? = null

        fun getInstance(context: Context): PrinterBleManager {
            return instance ?: synchronized(this) {
                instance ?: PrinterBleManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow<PrinterConnectionState>(PrinterConnectionState.Disconnected)
    val connectionState: StateFlow<PrinterConnectionState> = _connectionState.asStateFlow()

    private val _printJobStatus = MutableStateFlow(PrintJobStatus.IDLE)
    val printJobStatus: StateFlow<PrintJobStatus> = _printJobStatus.asStateFlow()

    private val _printStatusMessage = MutableStateFlow("Ready")
    val printStatusMessage: StateFlow<String> = _printStatusMessage.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val scannedDevices: StateFlow<List<DiscoveredDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanningFlow: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanCountdown = MutableStateFlow(0)
    val scanCountdown: StateFlow<Int> = _scanCountdown.asStateFlow()

    private val _lastScanError = MutableStateFlow<String?>(null)
    val lastScanError: StateFlow<String?> = _lastScanError.asStateFlow()

    private val _totalPacketsReceived = MutableStateFlow(0)
    val totalPacketsReceived: StateFlow<Int> = _totalPacketsReceived.asStateFlow()

    private val _manufacturerPacketsCount = MutableStateFlow(0)
    val manufacturerPacketsCount: StateFlow<Int> = _manufacturerPacketsCount.asStateFlow()

    @Volatile private var negotiatedMtu = 23
    @Volatile private var pendingWriteCompleter: CompletableDeferred<Int>? = null
    @Volatile private var gattReadyCompleter: CompletableDeferred<Boolean>? = null
    @Volatile private var isPrintInProgress = false
    @Volatile private var isGattConnected = false
    @Volatile private var flowControlObserved = false
    private val flowCredits = java.util.concurrent.atomic.AtomicInteger(4)
    private val flowCreditSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private var scanJob: Job? = null
    private var activeGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var flowControlCharacteristic: BluetoothGattCharacteristic? = null
    private val printMutex = Mutex()
    private var isScanning = false

    private var lastConnectedMac: String? = PrinterPreferences.getSavedPrinterMac(context)
    private var lastConnectedName: String? = PrinterPreferences.getSavedPrinterName(context)

    private fun updatePrintStatus(status: PrintJobStatus, message: String) {
        _printJobStatus.value = status
        _printStatusMessage.value = message
        Log.d(TAG, "PrintJob: $status - $message")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started (Code 1)"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed (Code 2). Please toggle Bluetooth OFF and ON."
                SCAN_FAILED_INTERNAL_ERROR -> "Internal Bluetooth error (Code 3). Please restart phone Bluetooth."
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning feature unsupported on this device (Code 4)"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Out of hardware resources (Code 5). Other apps may be using the scanner."
                else -> "BLE Scan failed with error code $errorCode"
            }
            Log.e(TAG, "BLE Scan failed: $errorMsg")
            _lastScanError.value = errorMsg
            isScanning = false
            _isScanning.value = false
            _scanCountdown.value = 0
            if (_connectionState.value is PrinterConnectionState.Scanning) {
                _connectionState.value = PrinterConnectionState.Disconnected
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return

        _totalPacketsReceived.value = _totalPacketsReceived.value + 1

        // Extract device name:
        // 1. ScanRecord device name
        // 2. device.name (if connect permission or cached)
        // 3. Raw TLV parse from bytes for 0x08 / 0x09
        var detectedName = result.scanRecord?.deviceName
        if (detectedName.isNullOrBlank()) {
            try {
                detectedName = device.name
            } catch (_: SecurityException) {}
        }
        if (detectedName.isNullOrBlank()) {
            detectedName = extractNameFromScanRecord(result.scanRecord?.bytes)
        }

        val displayName = if (detectedName.isNullOrBlank()) "Unknown BLE Device" else detectedName.trim()

        val deviceType = when (device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
            else -> "UNKNOWN"
        }

        val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()

        val manufData = result.scanRecord?.manufacturerSpecificData
        val manufacturerDataHex = if (manufData != null && manufData.size() > 0) {
            _manufacturerPacketsCount.value = _manufacturerPacketsCount.value + 1
            (0 until manufData.size()).joinToString("; ") { i ->
                val id = manufData.keyAt(i)
                val bytes = manufData.valueAt(i)
                val hex = bytes?.joinToString("") { "%02X".format(it) } ?: ""
                "0x%04X: %s".format(id, hex)
            }
        } else ""

        val rawBytesHex = result.scanRecord?.bytes?.joinToString("") { "%02X".format(it) } ?: ""

        val isTarget = P50SProtocol.isPotentialP50SPrinter(displayName) ||
                displayName.contains("P50S", ignoreCase = true) ||
                displayName.contains("496A", ignoreCase = true) ||
                serviceUuids.any { it.contains("ff00", ignoreCase = true) }

        val currentList = _scannedDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.address == address }

        val updatedItem = if (existingIndex >= 0) {
            val existing = currentList[existingIndex]
            val bestName = if (existing.name != "Unknown BLE Device" && displayName == "Unknown BLE Device") {
                existing.name
            } else {
                displayName
            }
            val bestIsTarget = existing.isP50S || isTarget
            val bestManuf = if (manufacturerDataHex.isNotEmpty()) manufacturerDataHex else existing.manufacturerDataHex
            val bestUuids = if (serviceUuids.isNotEmpty()) serviceUuids else existing.serviceUuids
            val bestRaw = if (rawBytesHex.isNotEmpty()) rawBytesHex else existing.rawBytesHex

            existing.copy(
                name = bestName,
                rssi = result.rssi,
                isP50S = bestIsTarget,
                deviceType = deviceType,
                serviceUuids = bestUuids,
                manufacturerDataHex = bestManuf,
                rawBytesHex = bestRaw,
                packetCount = existing.packetCount + 1,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        } else {
            DiscoveredDevice(
                name = displayName,
                address = address,
                rssi = result.rssi,
                isP50S = isTarget,
                deviceType = deviceType,
                serviceUuids = serviceUuids,
                manufacturerDataHex = manufacturerDataHex,
                rawBytesHex = rawBytesHex,
                packetCount = 1,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        }

        if (existingIndex >= 0) {
            currentList[existingIndex] = updatedItem
        } else {
            if (isTarget) {
                currentList.add(0, updatedItem)
            } else {
                currentList.add(updatedItem)
            }
        }

        // Sort: target P50S at top, then by signal strength (RSSI)
        currentList.sortByDescending { if (it.isP50S) 1000 + it.rssi else it.rssi }
        _scannedDevices.value = currentList
    }

    private fun extractNameFromScanRecord(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        var index = 0
        while (index < bytes.size) {
            val length = bytes[index].toInt() and 0xFF
            if (length == 0 || index + length >= bytes.size) break
            val type = bytes[index + 1].toInt() and 0xFF
            // 0x08 = Shortened Local Name, 0x09 = Complete Local Name
            if (type == 0x08 || type == 0x09) {
                val nameLength = length - 1
                if (nameLength > 0 && index + 2 + nameLength <= bytes.size) {
                    val nameBytes = bytes.copyOfRange(index + 2, index + 2 + nameLength)
                    val name = String(nameBytes, Charsets.UTF_8).trim()
                    if (name.isNotEmpty()) return name
                }
            }
            index += length + 1
        }
        return null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "GATT STATE: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isGattConnected = true
                val name = gatt.device.name ?: PrinterPreferences.getSavedPrinterName(context) ?: lastConnectedName ?: "P50S-496A-BLE"
                Log.d(TAG, "GATT CONNECTED: device=$name (${gatt.device.address})")
                activeGatt = gatt
                negotiatedMtu = 23 // Reset until onMtuChanged
                lastConnectedMac = gatt.device.address
                lastConnectedName = name
                _connectionState.value = PrinterConnectionState.Connecting(name, gatt.device.address, "Connected. Discovering services...")
                updatePrintStatus(PrintJobStatus.CONNECTING, "Connected. Discovering services...")

                // Request MTU 240 for faster throughput
                val mtuRequested = try {
                    gatt.requestMtu(240)
                } catch (e: Exception) {
                    false
                }

                // Request HIGH connection priority for maximum throughput and low latency
                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not request high connection priority", e)
                }

                if (!mtuRequested) {
                    Log.d(TAG, "MTU request failed or not supported; discovering services directly")
                    try {
                        gatt.discoverServices()
                    } catch (e: Exception) {
                        Log.e(TAG, "discoverServices failed", e)
                    }
                } else {
                    // Fallback timer: if onMtuChanged doesn't fire within 1200ms, discoverServices anyway
                    scope.launch {
                        delay(1200)
                        if (activeGatt == gatt && writeCharacteristic == null && _connectionState.value is PrinterConnectionState.Connecting) {
                            Log.d(TAG, "MTU callback timeout: triggering discoverServices directly")
                            try {
                                gatt.discoverServices()
                            } catch (_: Exception) {}
                        }
                    }
                }
            } else {
                Log.w(TAG, "GATT DISCONNECTED: status=$status, newState=$newState")
                Log.i(TAG, "[POWER_DIAG] PRINTER PHYSICAL DISCONNECT DETECTED: status=$status, newState=$newState, timestamp=${System.currentTimeMillis()}")
                isGattConnected = false
                val pending = pendingWriteCompleter
                pendingWriteCompleter = null
                pending?.complete(BluetoothGatt.GATT_FAILURE)

                val ready = gattReadyCompleter
                gattReadyCompleter = null
                ready?.complete(false)

                cleanupGatt()
                _connectionState.value = PrinterConnectionState.Disconnected
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            } else {
                Log.w(TAG, "MTU request returned status $status; using default MTU $negotiatedMtu")
            }
            val name = gatt.device.name ?: PrinterPreferences.getSavedPrinterName(context) ?: lastConnectedName ?: "P50S-496A-BLE"
            _connectionState.value = PrinterConnectionState.Connecting(name, gatt.device.address, "Discovering printer services...")
            updatePrintStatus(PrintJobStatus.CONNECTING, "Discovering printer services...")
            try {
                gatt.discoverServices()
            } catch (e: Exception) {
                Log.e(TAG, "discoverServices failed", e)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite status=$status, uuid=${characteristic.uuid}")
            val completer = pendingWriteCompleter
            pendingWriteCompleter = null
            completer?.complete(status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "SERVICE DISCOVERY COMPLETE: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status $status")
                val ready = gattReadyCompleter
                gattReadyCompleter = null
                ready?.complete(false)
                cleanupGatt()
                _connectionState.value = PrinterConnectionState.Disconnected
                return
            }

            var writeChar: BluetoothGattCharacteristic? = null
            var flowChar: BluetoothGattCharacteristic? = null

            for (service in gatt.services) {
                val serviceUuidStr = service.uuid.toString().lowercase()
                if (serviceUuidStr.contains("ff00") || service.uuid == P50SProtocol.SERVICE_UUID) {
                    for (char in service.characteristics) {
                        val charUuidStr = char.uuid.toString().lowercase()
                        if (charUuidStr.contains("ff02") || char.uuid == P50SProtocol.WRITE_CHAR_UUID) {
                            writeChar = char
                        } else if (charUuidStr.contains("ff03") || char.uuid == P50SProtocol.FLOW_CONTROL_CHAR_UUID) {
                            flowChar = char
                        }
                    }
                }
            }

            // Fallback: check across all services if ff00 was customized
            if (writeChar == null) {
                for (service in gatt.services) {
                    for (char in service.characteristics) {
                        val charUuidStr = char.uuid.toString().lowercase()
                        if (charUuidStr.contains("ff02")) writeChar = char
                        if (charUuidStr.contains("ff03")) flowChar = char
                    }
                }
            }

            if (writeChar != null) {
                writeCharacteristic = writeChar
                flowControlCharacteristic = flowChar
                Log.d(TAG, "WRITABLE CHARACTERISTIC FOUND: uuid=${writeChar.uuid}")

                // Enable flow control notification on 0xFF03 if present
                if (flowChar != null) {
                    try {
                        gatt.setCharacteristicNotification(flowChar, true)
                        val descriptor = flowChar.getDescriptor(P50SProtocol.CCCD_DESCRIPTOR_UUID)
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
                    } catch (e: Exception) {
                        Log.w(TAG, "Error enabling flow control notification", e)
                    }
                }

                val devName = gatt.device.name ?: PrinterPreferences.getSavedPrinterName(context) ?: lastConnectedName ?: "P50S-496A-BLE"
                val devAddress = gatt.device.address
                lastConnectedMac = devAddress
                lastConnectedName = devName
                PrinterPreferences.savePrinter(context, devName, devAddress)
                _connectionState.value = PrinterConnectionState.Connected(devName, devAddress)
                updatePrintStatus(PrintJobStatus.IDLE, "Printer ready")
                Log.i(TAG, "Printer connected and ready: $devName ($devAddress)")

                val ready = gattReadyCompleter
                gattReadyCompleter = null
                ready?.complete(true)
            } else {
                Log.e(TAG, "Write characteristic ff02 not found on printer")
                val ready = gattReadyCompleter
                gattReadyCompleter = null
                ready?.complete(false)
                cleanupGatt()
                _connectionState.value = PrinterConnectionState.Disconnected
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(characteristic.uuid, value)
        }

        private fun handleNotification(uuid: UUID, value: ByteArray?) {
            if (value != null && (uuid.toString().lowercase().contains("ff03") || uuid == P50SProtocol.FLOW_CONTROL_CHAR_UUID)) {
                val credit = P50SProtocol.parseFlowControlNotification(value)
                if (credit != null) {
                    flowControlObserved = true
                    if (credit == 4) {
                        flowCredits.set(4)
                    } else {
                        flowCredits.addAndGet(credit)
                    }
                    Log.d(TAG, "FLOW_CONTROL: Received 0xFF03 credit=$credit, totalCredits=${flowCredits.get()}")
                    flowCreditSignal.trySend(Unit)
                } else {
                    Log.d(TAG, "Received 0xFF03 flow notification: ${value.joinToString(" ") { "%02X".format(it) }}")
                }
            }
        }
    }

    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return missing
    }

    fun hasBluetoothPermissions(): Boolean {
        return getMissingPermissions().isEmpty()
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationServiceEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    fun isBleSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    fun isBluetoothAdapterAvailable(): Boolean {
        return bluetoothAdapter != null
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun clearScannedDevices() {
        _scannedDevices.value = emptyList()
        _totalPacketsReceived.value = 0
        _manufacturerPacketsCount.value = 0
    }

    fun startScan(durationSeconds: Int = 15) {
        _lastScanError.value = null

        if (!isBleSupported()) {
            val msg = "Bluetooth LE hardware is not supported on this device."
            Log.e(TAG, msg)
            _lastScanError.value = msg
            return
        }

        val missing = getMissingPermissions()
        if (missing.isNotEmpty()) {
            val friendlyMissing = missing.map { it.substringAfterLast('.') }.joinToString(", ")
            val msg = "Missing required permissions: $friendlyMissing. Please grant all permissions."
            Log.e(TAG, msg)
            _lastScanError.value = msg
            return
        }

        if (!isBluetoothEnabled()) {
            val msg = "Bluetooth is currently turned OFF. Please turn on phone Bluetooth."
            Log.e(TAG, msg)
            _lastScanError.value = msg
            return
        }

        // On Android 6-11 (and some Android 12+), Location Services must be turned ON for BLE scanning
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationServiceEnabled()) {
            val msg = "Phone Location Services (GPS) is turned OFF. BLE scanning requires Location Services to be enabled."
            Log.w(TAG, msg)
            _lastScanError.value = msg
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            val msg = "BluetoothLeScanner is unavailable. Bluetooth may still be turning on."
            Log.e(TAG, msg)
            _lastScanError.value = msg
            return
        }

        // Stop any currently running scan
        scanJob?.cancel()
        if (isScanning) {
            try {
                scanner.stopScan(scanCallback)
            } catch (_: Exception) {}
        }

        isScanning = true
        _isScanning.value = true
        _connectionState.value = PrinterConnectionState.Scanning
        _scanCountdown.value = durationSeconds
        _totalPacketsReceived.value = 0
        _manufacturerPacketsCount.value = 0

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            // Scan for ALL nearby BLE advertisements without name or UUID filter
            scanner.startScan(null, scanSettings, scanCallback)
            Log.i(TAG, "Unfiltered BLE scan started (SCAN_MODE_LOW_LATENCY) for ${durationSeconds}s")

            scanJob = scope.launch {
                for (remaining in durationSeconds downTo 1) {
                    _scanCountdown.value = remaining
                    delay(1000)
                }
                _scanCountdown.value = 0
                stopScan()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan", e)
            _lastScanError.value = "Failed to start BLE scan: ${e.localizedMessage}"
            isScanning = false
            _isScanning.value = false
            _scanCountdown.value = 0
            _connectionState.value = PrinterConnectionState.Disconnected
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (isScanning) {
            isScanning = false
            _isScanning.value = false
            _scanCountdown.value = 0
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                Log.i(TAG, "BLE scan stopped successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping BLE scan", e)
            }
        }
        if (_connectionState.value is PrinterConnectionState.Scanning) {
            _connectionState.value = PrinterConnectionState.Disconnected
        }
    }

    fun connect(deviceAddress: String, deviceName: String? = null) {
        stopScan()
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        try {
            val device = adapter.getRemoteDevice(deviceAddress) ?: return
            val label = deviceName ?: device.name ?: lastConnectedName ?: "P50S-496A-BLE"
            lastConnectedMac = deviceAddress
            lastConnectedName = label
            PrinterPreferences.savePrinter(context, label, deviceAddress)
            _connectionState.value = PrinterConnectionState.Connecting(label, deviceAddress, "Connecting to $label...")
            updatePrintStatus(PrintJobStatus.CONNECTING, "Connecting to $label...")

            cleanupGatt()
            activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device $deviceAddress", e)
            cleanupGatt()
            _connectionState.value = PrinterConnectionState.Disconnected
        }
    }

    /**
     * Synchronizes UI state with the real hardware GATT connection state.
     * Prevents false "Disconnected" or "Offline" states when navigating between screens.
     */
    fun syncConnectionState() {
        if (isPrinterConnected()) {
            val dev = activeGatt?.device
            val name = dev?.name ?: PrinterPreferences.getSavedPrinterName(context) ?: lastConnectedName ?: P50SProtocol.TARGET_DEVICE_NAME_EXACT
            val address = dev?.address ?: PrinterPreferences.getSavedPrinterMac(context) ?: lastConnectedMac ?: ""
            if (_connectionState.value !is PrinterConnectionState.Printing && _connectionState.value !is PrinterConnectionState.Scanning) {
                _connectionState.value = PrinterConnectionState.Connected(name, address)
            }
        } else if (!isGattConnected && _connectionState.value is PrinterConnectionState.Connected) {
            _connectionState.value = PrinterConnectionState.Disconnected
        }
    }

    fun disconnect() {
        stopScan()
        cleanupGatt()
        _connectionState.value = PrinterConnectionState.Disconnected
        updatePrintStatus(PrintJobStatus.IDLE, "Disconnected")
    }

    private fun cleanupGatt() {
        isGattConnected = false
        val gatt = activeGatt
        activeGatt = null
        writeCharacteristic = null
        flowControlCharacteristic = null
        negotiatedMtu = 23

        val pending = pendingWriteCompleter
        pendingWriteCompleter = null
        pending?.complete(BluetoothGatt.GATT_FAILURE)

        val ready = gattReadyCompleter
        gattReadyCompleter = null
        ready?.complete(false)

        if (gatt != null) {
            try {
                gatt.disconnect()
            } catch (_: Exception) {}
            try {
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing GATT", e)
            }
        }
    }

    /**
     * Checks the REAL GATT state of the printer, verifying:
     * 1. Active GATT reference is non-null
     * 2. Required write characteristic 0xFF02 is discovered and ready
     * 3. Bluetooth adapter is enabled
     * 4. GATT connection is actively established and live
     */
    fun isPrinterConnected(): Boolean {
        val gatt = activeGatt ?: return false
        if (writeCharacteristic == null) return false
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false
        return isGattConnected
    }

    /**
     * Verifies that the P50S GATT connection is live and ready.
     * If the current GATT is stale or disconnected, automatically attempts reconnection
     * and service rediscovery before reporting failure.
     */
    suspend fun ensureConnectedAndReady(): Boolean {
        if (!isBluetoothEnabled()) {
            Log.w(TAG, "ensureConnectedAndReady: Bluetooth adapter is OFF")
            return false
        }
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "ensureConnectedAndReady: Missing Bluetooth permissions")
            return false
        }

        // 1. If currently connected and verified ready
        if (isPrinterConnected()) {
            Log.d(TAG, "ensureConnectedAndReady: Existing GATT connection is live and verified ready")
            return true
        }

        // Stop any active BLE scan so connection and GATT communication get full radio priority
        stopScan()

        // 2. Identify target MAC address to reconnect to
        var targetMac = PrinterPreferences.getSavedPrinterMac(context) ?: lastConnectedMac
        var targetName = PrinterPreferences.getSavedPrinterName(context) ?: lastConnectedName ?: P50SProtocol.TARGET_DEVICE_NAME_EXACT

        if (targetMac.isNullOrBlank()) {
            // Check bonded devices
            try {
                val bonded = bluetoothAdapter?.bondedDevices?.firstOrNull {
                    P50SProtocol.isPotentialP50SPrinter(it.name) || it.name?.contains("P50S", ignoreCase = true) == true
                }
                if (bonded != null) {
                    targetMac = bonded.address
                    targetName = bonded.name ?: targetName
                    PrinterPreferences.savePrinter(context, targetName, targetMac)
                }
            } catch (_: SecurityException) {}
        }

        if (targetMac.isNullOrBlank()) {
            // Check memory scanned devices
            val discovered = _scannedDevices.value.firstOrNull { it.isP50S }
            if (discovered != null) {
                targetMac = discovered.address
                targetName = discovered.name
                PrinterPreferences.savePrinter(context, targetName, targetMac)
            }
        }

        if (targetMac.isNullOrBlank()) {
            Log.e(TAG, "ensureConnectedAndReady: No target printer MAC known or saved")
            return false
        }

        // Try reconnecting (up to 2 attempts for service discovery / ready state)
        for (attempt in 1..2) {
            Log.d(TAG, "RECONNECT START: target=$targetName ($targetMac), attempt=$attempt")
            updatePrintStatus(PrintJobStatus.CONNECTING, "Reconnecting to P50S...")
            _connectionState.value = PrinterConnectionState.Reconnecting(targetName, targetMac, "Reconnecting to P50S...")

            cleanupGatt()
            delay(250) // Let BLE radio stack settle

            val completer = CompletableDeferred<Boolean>()
            gattReadyCompleter = completer

            try {
                val device = bluetoothAdapter?.getRemoteDevice(targetMac)
                if (device == null) {
                    Log.e(TAG, "getRemoteDevice returned null for $targetMac")
                    cleanupGatt()
                    continue
                }

                activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }

                // Wait up to 10 seconds for connection + service discovery + characteristic discovery
                val ready = withTimeoutOrNull(10000) {
                    completer.await()
                } ?: false

                if (ready && isPrinterConnected()) {
                    Log.i(TAG, "ensureConnectedAndReady: Printer successfully reconnected and ready on attempt $attempt")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during reconnect attempt $attempt", e)
            } finally {
                gattReadyCompleter = null
            }

            cleanupGatt()
            if (attempt < 2) {
                delay(500)
            }
        }

        _connectionState.value = PrinterConnectionState.Disconnected
        return false
    }

    fun getSafeChunkSize(): Int {
        // BLE ATT header is 3 bytes (Opcode 1 byte + Attribute Handle 2 bytes)
        val maxAttPayload = negotiatedMtu - 3
        // Safe chunk size for P50S microcontroller: minimum 20 bytes, maximum standard P50S BLE chunk size (90 bytes)
        return maxAttPayload.coerceIn(20, P50SProtocol.BLE_CHUNK_SIZE)
    }

    suspend fun printTestReceipt(): PrintResult = withContext(Dispatchers.IO) {
        if (!isPrinterConnected()) {
            Log.w(TAG, "printTestReceipt: Printer is not connected")
            return@withContext PrintResult.Error("Printer Not Connected (Disconnected)\nPlease connect P50S-496A-BLE and try again.")
        }
        val savedName = PrinterPreferences.getSavedPrinterName(context) ?: P50SProtocol.TARGET_DEVICE_NAME_EXACT
        val bitmap = ReceiptBitmapGenerator.generateTestReceipt(savedName)
        val monoBytes = ReceiptBitmapGenerator.convertTo1BitMonochrome(bitmap)
        val structuredPayload = P50SProtocol.buildStructuredPrintPayload(monoBytes, bitmap.height)
        printStructuredData(bitmap, structuredPayload, isCustomerReceipt = false)
    }

    private fun splitIntoChunks(data: ByteArray, chunkSize: Int): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        val list = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            list.add(data.copyOfRange(offset, end))
            offset = end
        }
        return list
    }

    suspend fun printCustomerReceipt(
        repair: RepairEntity,
        items: List<RepairItemEntity>,
        payments: List<PaymentEntity>,
        settings: AppSettingsEntity,
        config: CustomerReceiptConfig = ReceiptPreferences.getCustomerReceiptConfig(context),
        forceNormalReceipt: Boolean = false
    ): PrintResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "CUSTOMER_RECEIPT_START: Job #${repair.jobNumber}, Customer=${repair.customerName}, Phone=${repair.customerPhone}, Items=${items.size}")

        if (!isPrinterConnected()) {
            Log.w(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Printer is not connected")
            return@withContext PrintResult.Error("Printer Not Connected (Disconnected)\nPlease connect P50S-496A-BLE and try again.")
        }

        val bitmap = ReceiptBitmapGenerator.generateCustomerReceipt(repair, items, payments, settings, config)
        executeCustomerReceiptPipeline(bitmap = bitmap, isDiagnostic = false)
    }

    /**
     * Executes the diagnostic test that treats each 200px block as a completely separate PRINT JOB.
     * Sequences:
     * - JOB 1: START_PRINT_JOB -> raster (0-199) -> 500ms delay -> STOP_PRINT_JOB
     * - JOB 2: START_PRINT_JOB -> raster (200-399) -> 500ms delay -> STOP_PRINT_JOB
     * - JOB 3: START_PRINT_JOB -> raster (400-599) -> 500ms delay -> STOP_PRINT_JOB
     * - ONLY AFTER JOB 3: FEED_PAPER_160PX -> ALIGN_END
     */
    suspend fun printCustomerReceiptDiagnostic(): PrintResult = withContext(Dispatchers.IO) {
        printSeparateJobsDiagnostic()
    }

    suspend fun printSeparateJobsDiagnostic(): PrintResult = withContext(Dispatchers.IO) {
        val bitmap = ReceiptBitmapGenerator.generateCustomerReceiptDiagnostic()
        printSeparateJobsBitmap(bitmap, isCustomerReceipt = false, isDiagnostic = true)
    }

    /**
     * Reusable sequential separate-job print engine for Marklife P50S.
     * Divides a bitmap vertically into blocks of max 200px and executes each block
     * as a completely separate P50S print job with:
     * 1. START_PRINT_JOB
     * 2. Exactly ONE 0x1F 0x10 raster command for that block
     * 3. 90-byte BLE chunks with 28ms packet pacing
     * 4. 500ms post-raster settling delay
     * 5. STOP_PRINT_JOB
     * Between jobs: 300ms idle delay without paper feed or alignment.
     * After final job only: CMD_FEED_PAPER_160PX then CMD_ALIGN_END.
     */
    private suspend fun printSeparateJobsBitmap(
        bitmap: android.graphics.Bitmap,
        isCustomerReceipt: Boolean = false,
        isDiagnostic: Boolean = false,
        receiptType: String = if (isCustomerReceipt) "Customer Receipt" else "3-job diagnostic"
    ): PrintResult = withContext(Dispatchers.IO) {
        if (!isPrinterConnected()) {
            val label = receiptType.uppercase(Locale.US).replace(" ", "_")
            Log.w(TAG, "$label: Printer is not connected")
            return@withContext PrintResult.Error("Printer Not Connected (Disconnected)\nPlease connect P50S-496A-BLE and try again.")
        }

        if (isPrintInProgress || _printJobStatus.value == PrintJobStatus.PRINTING || _printJobStatus.value == PrintJobStatus.CONNECTING || _printJobStatus.value == PrintJobStatus.PREPARING) {
            return@withContext PrintResult.Error("A print job is already in progress. Please wait.")
        }
        if (!printMutex.tryLock()) {
            return@withContext PrintResult.Error("A print job is already in progress. Please wait.")
        }
        isPrintInProgress = true

        val label = receiptType
        try {
            updatePrintStatus(PrintJobStatus.PREPARING, "Preparing $label...")
            flowCredits.set(4)

            val gatt = activeGatt
            val writeChar = writeCharacteristic

            if (gatt == null || writeChar == null || !isPrinterConnected()) {
                cleanupGatt()
                updatePrintStatus(PrintJobStatus.FAILED, "Printer Not Connected")
                return@withContext PrintResult.Error("Printer Not Connected\nPlease connect P50S-496A-BLE and try again.")
            }

            val chunkSize = getSafeChunkSize()
            updatePrintStatus(PrintJobStatus.PRINTING, "Printing $label...")
            _connectionState.value = PrinterConnectionState.Printing(0, "Printing $label...")

            val props = writeChar.properties
            val writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            writeChar.writeType = writeType

            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (_: Exception) {}

            suspend fun sendChunksDirect(chunks: List<ByteArray>, blockName: String, pacingDelayMs: Long = 28): Boolean {
                for ((chunkIdx, chunk) in chunks.withIndex()) {
                    val currentGatt = activeGatt
                    val currentWriteChar = writeCharacteristic
                    if (currentGatt == null || currentWriteChar == null || !isGattConnected) {
                        Log.e(TAG, "Print aborted in $blockName: activeGatt/writeCharacteristic is null or disconnected")
                        return false
                    }

                    if (flowControlObserved) {
                        if (flowCredits.get() <= 0) {
                            withTimeoutOrNull(400) {
                                while (flowCredits.get() <= 0 && isGattConnected) {
                                    flowCreditSignal.receive()
                                }
                            }
                        }
                        if (flowCredits.get() > 0) {
                            flowCredits.decrementAndGet()
                        }
                    }

                    var packetSent = false
                    for (attempt in 1..4) {
                        if (!isGattConnected) return false
                        val completer = CompletableDeferred<Int>()
                        pendingWriteCompleter = completer

                        val writeInitiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val res = currentGatt.writeCharacteristic(currentWriteChar, chunk, writeType)
                            res == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            currentWriteChar.value = chunk
                            @Suppress("DEPRECATION")
                            currentGatt.writeCharacteristic(currentWriteChar)
                        }

                        if (!writeInitiated) {
                            delay(40L * attempt)
                            continue
                        }

                        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                            val callbackStatus = withTimeoutOrNull(2000) { completer.await() }
                            if (callbackStatus == BluetoothGatt.GATT_SUCCESS) {
                                packetSent = true
                                break
                            } else {
                                delay(40L * attempt)
                            }
                        } else {
                            packetSent = true
                            break
                        }
                    }

                    if (!packetSent) {
                        Log.e(TAG, "Chunk ${chunkIdx + 1}/${chunks.size} failed in $blockName")
                        return false
                    }
                    delay(pacingDelayMs)
                }
                return true
            }

            // 1. Convert to monochrome raster data
            val monoBytes = ReceiptBitmapGenerator.convertTo1BitMonochrome(bitmap)

            // 2. Hardware initialization commands (protocol 0x1F continuous mode)
            val initHardware = byteArrayOf(
                0x1F.toByte(), 0xB2.toByte(), 0x00.toByte(), // CMD_SET_BT_TYPE
                0x1F.toByte(), 0x80.toByte(), 0x01.toByte(), 0x10.toByte(), // CMD_PAPER_TYPE_CONTINUOUS
                0x1F.toByte(), 0x70.toByte(), 0x01.toByte(), 0x04.toByte()  // CMD_SET_DENSITY_4
            )
            val initHardwareChunks = splitIntoChunks(initHardware, chunkSize)
            if (!sendChunksDirect(initHardwareChunks, "HardwareInit", 20)) {
                return@withContext PrintResult.Error("Failed to initialize printer hardware")
            }
            delay(40)

            // Divide vertically into blocks of max 200px
            val blocks = P50SProtocol.buildSeparateJobBlocks(monoBytes, bitmap.height, maxBlockHeight = 200)
            if (blocks.isEmpty()) {
                Log.e(TAG, "Separate jobs print: bitmap height ${bitmap.height} resulted in 0 blocks")
                return@withContext PrintResult.Error("Validation failed: Empty receipt content")
            }

            val startJobChunks = splitIntoChunks(P50SProtocol.CMD_START_PRINT_JOB, chunkSize)
            val stopJobChunks = splitIntoChunks(P50SProtocol.CMD_STOP_PRINT_JOB, chunkSize)

            for ((index, block) in blocks.withIndex()) {
                val jobNum = index + 1
                Log.i(TAG, "[JOB $jobNum] START")
                if (!sendChunksDirect(startJobChunks, "JOB $jobNum START", 20)) {
                    return@withContext PrintResult.Error("Failed to send JOB $jobNum START")
                }
                delay(30)

                val jobChunks = splitIntoChunks(block.rasterCommand, chunkSize)
                if (!sendChunksDirect(jobChunks, "JOB $jobNum Raster", 28)) {
                    return@withContext PrintResult.Error("Failed transmitting JOB $jobNum raster")
                }
                Log.i(TAG, "[JOB $jobNum] raster complete")
                val progressPercent = ((jobNum.toFloat() / blocks.size) * 90).toInt()
                _connectionState.value = PrinterConnectionState.Printing(progressPercent, "Job $jobNum complete...")
                delay(500) // Post-raster settling delay (500ms)

                if (!sendChunksDirect(stopJobChunks, "JOB $jobNum STOP", 20)) {
                    return@withContext PrintResult.Error("Failed to send JOB $jobNum STOP")
                }
                Log.i(TAG, "[JOB $jobNum] STOP")

                if (jobNum < blocks.size) {
                    delay(300) // Inter-job settling delay without feed
                } else {
                    delay(150)
                }
            }

            // ==================== ONLY AFTER FINAL JOB: FINAL FEED (160px) & ALIGN ====================
            val feedChunks = splitIntoChunks(P50SProtocol.CMD_FEED_PAPER_160PX, chunkSize)
            if (!sendChunksDirect(feedChunks, "FINAL FEED 160px", 20)) {
                return@withContext PrintResult.Error("Failed to feed paper")
            }
            delay(350)

            val alignChunks = splitIntoChunks(P50SProtocol.CMD_ALIGN_END, chunkSize)
            if (!sendChunksDirect(alignChunks, "FINAL ALIGN", 20)) {
                return@withContext PrintResult.Error("Failed to align paper")
            }
            delay(200)

            Log.i(TAG, "Then final FEED and ALIGN.")
            Log.i(TAG, "[$receiptType]\nFINAL PRINT COMPLETE")
            val completionMsg = if (isDiagnostic) "${blocks.size}-Job Print Complete" else "$receiptType Printed Successfully"
            _connectionState.value = PrinterConnectionState.Printing(100, completionMsg)
            updatePrintStatus(PrintJobStatus.SUCCESS, completionMsg)
            return@withContext PrintResult.Success

        } catch (e: Exception) {
            Log.e(TAG, "Separate jobs print error: ${e.message}", e)
            updatePrintStatus(PrintJobStatus.FAILED, "Print failed: ${e.localizedMessage}")
            return@withContext PrintResult.Error("Separate-job print failed: ${e.localizedMessage}")
        } finally {
            val savedName = PrinterPreferences.getSavedPrinterName(context) ?: lastConnectedName ?: "P50S-496A-BLE"
            val savedMac = PrinterPreferences.getSavedPrinterMac(context) ?: lastConnectedMac ?: ""
            if (isPrinterConnected()) {
                _connectionState.value = PrinterConnectionState.Connected(savedName, savedMac)
            } else {
                _connectionState.value = PrinterConnectionState.Disconnected
            }
            pendingWriteCompleter = null
            isPrintInProgress = false
            printMutex.unlock()
        }
    }


    /**
     * Executes controlled Power/Buffer diagnostic tests (TEST A to TEST J)
     * to isolate height vs. black pixel density thresholds.
     */
    suspend fun printPowerDiagnosticTest(testId: String): PrintResult = withContext(Dispatchers.IO) {
        if (!isPrinterConnected()) {
            Log.w(TAG, "POWER_DIAG: Printer is not connected")
            return@withContext PrintResult.Error("Printer Not Connected (Disconnected)\nPlease connect P50S-496A-BLE and try again.")
        }

        val spec = ReceiptBitmapGenerator.getPowerTestSpec(testId)
        val bitmap = ReceiptBitmapGenerator.generatePowerDiagnosticBitmap(spec)
        val width = bitmap.width
        val height = bitmap.height

        val monoBytes = ReceiptBitmapGenerator.convertTo1BitMonochrome(bitmap)
        val compressed = P50SProtocol.compressZlib1KbLevel0(monoBytes)
        val chunkSize = getSafeChunkSize()
        val structuredPayload = P50SProtocol.buildStructuredPrintPayload(monoBytes, height, chunkSize)

        // Count exact black dots across raw bytes
        var blackDots = 0
        for (b in monoBytes) {
            val unsigned = b.toInt() and 0xFF
            blackDots += java.lang.Integer.bitCount(unsigned)
        }
        val blackPixelPercent = String.format(Locale.US, "%.1f%%", (blackDots.toDouble() * 100.0) / (width * height))

        val initChunks = splitIntoChunks(structuredPayload.initCommands, chunkSize)
        val stopChunks = splitIntoChunks(P50SProtocol.CMD_STOP_PRINT_JOB, chunkSize)
        val feedChunks = splitIntoChunks(P50SProtocol.CMD_FEED_PAPER_80PX, chunkSize)
        val alignChunks = splitIntoChunks(P50SProtocol.CMD_ALIGN_END, chunkSize)
        val sliceChunksTotal = structuredPayload.slices.sumOf { splitIntoChunks(it, chunkSize).size }
        val totalBleChunks = initChunks.size + sliceChunksTotal + stopChunks.size + feedChunks.size + alignChunks.size
        val totalPayloadBytes = structuredPayload.initCommands.size + structuredPayload.slices.sumOf { it.size } + P50SProtocol.CMD_STOP_PRINT_JOB.size + P50SProtocol.CMD_FEED_PAPER_80PX.size + P50SProtocol.CMD_ALIGN_END.size

        Log.i(
            TAG,
            "[POWER_DIAG]\n" +
            "testName=${spec.id}\n" +
            "width=$width\n" +
            "height=$height\n" +
            "blackPixelPercent=$blackPixelPercent\n" +
            "rawBytes=${monoBytes.size}\n" +
            "compressedBytes=${compressed.size}\n" +
            "payloadBytes=$totalPayloadBytes\n" +
            "bleChunkCount=$totalBleChunks\n" +
            "startJobCount=1\n" +
            "rasterCommandCount=1\n" +
            "stopJobCount=1\n" +
            "transmissionComplete=false"
        )

        val result = printStructuredData(
            bitmap = bitmap,
            structuredPayload = structuredPayload,
            isCustomerReceipt = false,
            isDiagnostic = false
        )

        if (result is PrintResult.Success) {
            Log.i(TAG, "[POWER_DIAG]\ntransmissionComplete=true")
            Log.i(TAG, "[POWER_DIAG] BLE transmission finished at timestamp=${System.currentTimeMillis()}")
        }
        result
    }

    suspend fun printNormalCustomerReceipt(
        repair: RepairEntity,
        items: List<RepairItemEntity>,
        payments: List<PaymentEntity>,
        settings: AppSettingsEntity,
        config: CustomerReceiptConfig = ReceiptPreferences.getCustomerReceiptConfig(context)
    ): PrintResult = withContext(Dispatchers.IO) {
        printCustomerReceipt(repair, items, payments, settings, config, forceNormalReceipt = true)
    }

    private suspend fun executeCustomerReceiptPipeline(
        bitmap: android.graphics.Bitmap,
        isDiagnostic: Boolean
    ): PrintResult = withContext(Dispatchers.IO) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val bytesPerRow = (width + 7) / 8
            val rawSize = bytesPerRow * height

            // 1. BITMAP_GENERATION STAGE
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            var slice0BlackPixels = 0
            var slice1BlackPixels = 0
            var slice2BlackPixels = 0
            val threshold = 190

            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    val pixel = pixels[rowOffset + x]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val alpha = (pixel shr 24) and 0xFF
                    val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    if (alpha > 50 && lum <= threshold) {
                        val sectionHeight = 200
                        when {
                            y < sectionHeight -> slice0BlackPixels++
                            y < sectionHeight * 2 -> slice1BlackPixels++
                            else -> slice2BlackPixels++
                        }
                    }
                }
            }

            val hasLowerContent = (slice1BlackPixels > 0 || slice2BlackPixels > 0)
            Log.i(
                TAG,
                "[BITMAP_GENERATION]\n" +
                "full width=$width\n" +
                "full height=$height\n" +
                "isDiagnostic=$isDiagnostic\n" +
                "slice 0 black pixels=$slice0BlackPixels (rows 0 until ${minOf(height, 200)})\n" +
                "slice 1 black pixels=$slice1BlackPixels (rows 200 until ${minOf(height, 400)})\n" +
                "slice 2 black pixels=$slice2BlackPixels (rows 400 until $height)\n" +
                "verify lower part contains actual black pixels/text=$hasLowerContent"
            )

            // Strict safety validation before sending to printer
            if (width != P50SProtocol.PRINTER_WIDTH_DOTS) {
                Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_ERROR: Abnormal width $width != 384")
                return@withContext PrintResult.Error("Validation failed: Abnormal receipt width ($width)")
            }
            if (height < 80 || height > 4000) {
                Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_ERROR: Abnormal height $height")
                return@withContext PrintResult.Error("Validation failed: Abnormal receipt height ($height)")
            }
            if (bytesPerRow != P50SProtocol.BYTES_PER_ROW) {
                Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_ERROR: Invalid bytes per row $bytesPerRow != 48")
                return@withContext PrintResult.Error("Validation failed: Invalid bytes per row ($bytesPerRow)")
            }

            val expectedJobCount = (height + 199) / 200
            Log.i(
                TAG,
                "CUSTOMER_RECEIPT_PRINT_START: Initiating proven separate-job print architecture ($expectedJobCount separate job(s), max 200px/job, height=$height px)"
            )

            val result = printSeparateJobsBitmap(
                bitmap = bitmap,
                isCustomerReceipt = true,
                isDiagnostic = isDiagnostic
            )
            when (result) {
                is PrintResult.Success -> {
                    Log.i(TAG, "CUSTOMER_RECEIPT_PRINT_SUCCESS: Customer receipt ($expectedJobCount job(s), $height px) printed successfully")
                }
                is PrintResult.Error -> {
                    Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_ERROR: ${result.message}")
                    Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: ${result.message}")
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_ERROR: Exception ${e.message}", e)
            Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Exception ${e.message}", e)
            PrintResult.Error("Print failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    suspend fun printPaymentReceipt(
        repair: RepairEntity,
        payment: PaymentEntity,
        settings: AppSettingsEntity,
        config: PaymentReceiptConfig = ReceiptPreferences.getPaymentReceiptConfig(context)
    ): PrintResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "PAYMENT_RECEIPT_START: Job #${repair.jobNumber}, Payment #${payment.paymentNumber}, Amount=${payment.amount}")

        if (!isPrinterConnected()) {
            Log.w(TAG, "PAYMENT_RECEIPT_PRINT_FAILED: Printer is not connected")
            return@withContext PrintResult.Error("Printer Not Connected (Disconnected)\nPlease connect P50S-496A-BLE and try again.")
        }
        val bitmap = ReceiptBitmapGenerator.generatePaymentReceipt(repair, payment, settings, config)
        executePaymentReceiptPipeline(bitmap = bitmap)
    }

    private suspend fun executePaymentReceiptPipeline(
        bitmap: android.graphics.Bitmap
    ): PrintResult = withContext(Dispatchers.IO) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val bytesPerRow = (width + 7) / 8

            // Strict safety validation before sending to printer
            if (width != P50SProtocol.PRINTER_WIDTH_DOTS) {
                Log.e(TAG, "PAYMENT_RECEIPT_PRINT_ERROR: Abnormal width $width != 384")
                return@withContext PrintResult.Error("Validation failed: Abnormal receipt width ($width)")
            }
            if (height < 80 || height > 4000) {
                Log.e(TAG, "PAYMENT_RECEIPT_PRINT_ERROR: Abnormal height $height")
                return@withContext PrintResult.Error("Validation failed: Abnormal receipt height ($height)")
            }
            if (bytesPerRow != P50SProtocol.BYTES_PER_ROW) {
                Log.e(TAG, "PAYMENT_RECEIPT_PRINT_ERROR: Invalid bytes per row $bytesPerRow != 48")
                return@withContext PrintResult.Error("Validation failed: Invalid bytes per row ($bytesPerRow)")
            }

            val expectedJobCount = (height + 199) / 200
            Log.i(
                TAG,
                "PAYMENT_RECEIPT_PRINT_START: Initiating proven separate-job print architecture ($expectedJobCount separate job(s), max 200px/job, height=$height px)"
            )

            val result = printSeparateJobsBitmap(
                bitmap = bitmap,
                isCustomerReceipt = false,
                isDiagnostic = false,
                receiptType = "Payment Receipt"
            )
            when (result) {
                is PrintResult.Success -> {
                    Log.i(TAG, "PAYMENT_RECEIPT_PRINT_SUCCESS: Payment receipt ($expectedJobCount job(s), $height px) printed successfully")
                }
                is PrintResult.Error -> {
                    Log.e(TAG, "PAYMENT_RECEIPT_PRINT_ERROR: ${result.message}")
                    Log.e(TAG, "PAYMENT_RECEIPT_PRINT_FAILED: ${result.message}")
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "PAYMENT_RECEIPT_PRINT_ERROR: Exception ${e.message}", e)
            Log.e(TAG, "PAYMENT_RECEIPT_PRINT_FAILED: Exception ${e.message}", e)
            PrintResult.Error("Print failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    /**
     * Compatibility wrapper for printBitmap. Converts bitmap to structured payload and prints.
     */
    suspend fun printBitmap(
        bitmap: android.graphics.Bitmap,
        isCustomerReceipt: Boolean = false,
        prebuiltPayload: ByteArray? = null
    ): PrintResult = withContext(Dispatchers.IO) {
        val monoBytes = ReceiptBitmapGenerator.convertTo1BitMonochrome(bitmap)
        val chunkSize = getSafeChunkSize()
        val structuredPayload = P50SProtocol.buildStructuredPrintPayload(monoBytes, bitmap.height, chunkSize)
        printStructuredData(bitmap, structuredPayload, isCustomerReceipt = isCustomerReceipt)
    }

    /**
     * Transmits structured print data over BLE to Marklife P50S using Protocol 0x1F.
     * Guaranteed safe:
     * - Only ONE print job can run at a time (locked against rapid clicks)
     * - Verifies active connection before sending (shows "Printer Not Connected" immediately if offline)
     * - Reuses active GATT connection immediately without reconnecting or redundant scanning
     * - Uses safe negotiated MTU chunking
     * - Strictly sequential async queue: sends slice 0 chunks -> waits -> sends slice 1 chunks -> waits -> only then finalizes
     * - Safe inter-slice pacing allows physical thermal print head time to burn and clear microcontroller buffer
     * - Credit-based flow control on characteristic 0xFF03 when supported by printer
     * - Packet-level retry with backoff and safe job timeout
     * - Guaranteed state reset in finally block so UI never stays stuck on PRINTING
     */
    private suspend fun printStructuredData(
        bitmap: android.graphics.Bitmap,
        structuredPayload: P50SProtocol.PrintPayloadStructure,
        isCustomerReceipt: Boolean = false,
        isDiagnostic: Boolean = false
    ): PrintResult = withContext(Dispatchers.IO) {
        if (!isBluetoothEnabled()) {
            if (isCustomerReceipt) {
                Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Bluetooth is disabled")
            }
            return@withContext PrintResult.Error("Please turn on Bluetooth")
        }

        if (!hasBluetoothPermissions()) {
            if (isCustomerReceipt) {
                Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Bluetooth permission not granted")
            }
            return@withContext PrintResult.Error("Bluetooth permission not granted")
        }

        if (!isPrinterConnected()) {
            if (isCustomerReceipt) {
                Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Printer not connected")
            }
            return@withContext PrintResult.Error("Printer Not Connected (Disconnected)\nPlease connect P50S-496A-BLE and try again.")
        }

        // Prevent duplicate simultaneous print requests
        if (isPrintInProgress || _printJobStatus.value == PrintJobStatus.PRINTING || _printJobStatus.value == PrintJobStatus.CONNECTING || _printJobStatus.value == PrintJobStatus.PREPARING) {
            if (isCustomerReceipt) {
                Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Print job already in progress")
            }
            return@withContext PrintResult.Error("A print job is already in progress. Please wait.")
        }
        if (!printMutex.tryLock()) {
            if (isCustomerReceipt) {
                Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Print mutex lock failed")
            }
            return@withContext PrintResult.Error("A print job is already in progress. Please wait.")
        }
        isPrintInProgress = true

        try {
            updatePrintStatus(PrintJobStatus.PREPARING, "Preparing print data...")
            flowCredits.set(4) // Initialize standard flow control window

            val gatt = activeGatt
            val writeChar = writeCharacteristic

            if (gatt == null || writeChar == null || !isPrinterConnected()) {
                cleanupGatt()
                Log.e(TAG, "PRINT FAILED: Printer not connected.")
                updatePrintStatus(PrintJobStatus.FAILED, "Printer Not Connected")
                return@withContext PrintResult.Error("Printer Not Connected\nPlease connect P50S-496A-BLE and try again.")
            }

            val chunkSize = getSafeChunkSize()
            val totalBytes = structuredPayload.totalBytes
            val totalSlices = structuredPayload.totalSlices
            val rasterCommandCount = totalSlices

            val initChunks = splitIntoChunks(structuredPayload.initCommands, chunkSize)
            val stopChunks = splitIntoChunks(P50SProtocol.CMD_STOP_PRINT_JOB, chunkSize)
            val feedChunks = splitIntoChunks(P50SProtocol.CMD_FEED_PAPER_80PX, chunkSize)
            val alignChunks = splitIntoChunks(P50SProtocol.CMD_ALIGN_END, chunkSize)
            val sliceChunksTotal = structuredPayload.slices.sumOf { splitIntoChunks(it, chunkSize).size }
            val totalBleChunks = initChunks.size + sliceChunksTotal + stopChunks.size + feedChunks.size + alignChunks.size
            val totalStreamPayloadBytes = structuredPayload.initCommands.size + structuredPayload.slices.sumOf { it.size } + P50SProtocol.CMD_STOP_PRINT_JOB.size + P50SProtocol.CMD_FEED_PAPER_80PX.size + P50SProtocol.CMD_ALIGN_END.size

            if (isDiagnostic) {
                Log.i(TAG, "[DIAG] bitmapHeight=${bitmap.height}")
                Log.i(TAG, "[DIAG] rasterCommandCount=$rasterCommandCount")
                Log.i(TAG, "[DIAG] startJobCount=1")
                Log.i(TAG, "[DIAG] stopJobCount=1")
                Log.i(TAG, "[DIAG] totalPayloadBytes=$totalStreamPayloadBytes")
                Log.i(TAG, "[DIAG] bleChunkCount=$totalBleChunks")
            }

            Log.i(TAG, "Starting P50S print transmission: $totalBytes bytes across $totalSlices raster command(s) (chunkSize=$chunkSize, MTU=$negotiatedMtu)")
            updatePrintStatus(PrintJobStatus.PRINTING, "Printing...")
            _connectionState.value = PrinterConnectionState.Printing(0, "Printing...")

            // Determine write type supported by characteristic
            val props = writeChar.properties
            val writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            writeChar.writeType = writeType

            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (_: Exception) {}

            var packetIndex = 0
            var totalBytesSent = 0
            var connectionLostDuringTransmission = false

            suspend fun sendChunkList(
                chunks: List<ByteArray>,
                blockName: String,
                isSlice: Boolean,
                sliceIdx: Int,
                sliceTotal: Int,
                isCustReceipt: Boolean
            ): Boolean {
                val totalChunksInBlock = chunks.size
                for ((chunkIdx, chunk) in chunks.withIndex()) {
                    val currentGatt = activeGatt
                    val currentWriteChar = writeCharacteristic
                    if (currentGatt == null || currentWriteChar == null || !isGattConnected) {
                        Log.e(TAG, "Print aborted: activeGatt/writeCharacteristic is null or disconnected")
                        connectionLostDuringTransmission = true
                        return false
                    }

                    // Credit-based flow control check on 0xFF03
                    if (flowControlObserved) {
                        if (flowCredits.get() <= 0) {
                            withTimeoutOrNull(400) {
                                while (flowCredits.get() <= 0 && isGattConnected) {
                                    flowCreditSignal.receive()
                                }
                            }
                        }
                        if (flowCredits.get() > 0) {
                            flowCredits.decrementAndGet()
                        }
                    }

                    packetIndex++
                    val currentChunkNumber = chunkIdx + 1

                    var packetSent = false
                    for (attempt in 1..4) {
                        if (!isGattConnected) {
                            connectionLostDuringTransmission = true
                            break
                        }
                        val completer = CompletableDeferred<Int>()
                        pendingWriteCompleter = completer

                        val writeInitiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val res = currentGatt.writeCharacteristic(currentWriteChar, chunk, writeType)
                            res == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            currentWriteChar.value = chunk
                            @Suppress("DEPRECATION")
                            currentGatt.writeCharacteristic(currentWriteChar)
                        }

                        if (!writeInitiated) {
                            Log.w(TAG, "writeCharacteristic call rejected on attempt $attempt for chunk $currentChunkNumber/$totalChunksInBlock in $blockName")
                            if (!isGattConnected) {
                                connectionLostDuringTransmission = true
                                break
                            }
                            delay(40L * attempt)
                            continue
                        }

                        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                            val callbackStatus = withTimeoutOrNull(2000) {
                                completer.await()
                            }

                            if (isCustReceipt && isSlice) {
                                Log.i(TAG, "[BLE_TRANSMISSION] slice $sliceIdx chunk $currentChunkNumber/$totalChunksInBlock WRITE CALLBACK status=$callbackStatus attempt=$attempt")
                            }

                            if (callbackStatus == BluetoothGatt.GATT_SUCCESS) {
                                packetSent = true
                                break
                            } else {
                                Log.w(TAG, "PACKET WRITE FAILURE: chunk $currentChunkNumber/$totalChunksInBlock attempt $attempt, status=$callbackStatus")
                                if (!isGattConnected) {
                                    connectionLostDuringTransmission = true
                                    break
                                }
                                delay(40L * attempt)
                            }
                        } else {
                            // WRITE_TYPE_NO_RESPONSE is accepted immediately by the radio buffer
                            if (isCustReceipt && isSlice) {
                                Log.i(TAG, "[BLE_TRANSMISSION] slice $sliceIdx chunk $currentChunkNumber/$totalChunksInBlock WRITE CALLBACK status=NO_RESPONSE_QUEUED attempt=$attempt")
                            }
                            packetSent = true
                            break
                        }
                    }

                    if (!packetSent) {
                        Log.e(TAG, "Chunk $currentChunkNumber/$totalChunksInBlock failed after retries in $blockName")
                        return false
                    }

                    totalBytesSent += chunk.size

                    if (isCustReceipt && isSlice) {
                        if (flowControlObserved) {
                            Log.i(TAG, "[BLE_TRANSMISSION] slice $sliceIdx ACK/CREDIT: creditsRemaining=${flowCredits.get()}")
                        }
                        val progress = ((totalBytesSent.toLong() * 100) / totalBytes.toLong()).toInt().coerceIn(0, 99)
                        Log.i(TAG, "[BLE_TRANSMISSION] slice $sliceIdx chunk progress: $currentChunkNumber/$totalChunksInBlock ($progress%)")
                        Log.i(TAG, "[CUSTOMER_RECEIPT] chunk $currentChunkNumber/$totalChunksInBlock")

                        if (currentChunkNumber == totalChunksInBlock) {
                            Log.i(TAG, "[BLE_TRANSMISSION] slice $sliceIdx LAST CHUNK SENT ($currentChunkNumber/$totalChunksInBlock)")
                        }
                    }

                    val progress = ((totalBytesSent.toLong() * 100) / totalBytes.toLong()).toInt().coerceIn(0, 99)
                    _connectionState.value = PrinterConnectionState.Printing(progress, "Printing ($progress%)...")

                    // Pacing delay between chunks to avoid radio / microcontroller buffer overrun
                    delay(20)
                }
                return true
            }

            // Phase 1: Transmit Protocol Initialization Commands
            val initSuccess = sendChunkList(
                chunks = initChunks,
                blockName = "initCommands",
                isSlice = false,
                sliceIdx = 0,
                sliceTotal = totalSlices,
                isCustReceipt = isCustomerReceipt
            )
            if (!initSuccess) {
                if (isCustomerReceipt) {
                    Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Failed transmitting init commands")
                }
                updatePrintStatus(PrintJobStatus.FAILED, "Failed to start print job")
                return@withContext PrintResult.Error("Failed to initialize printer")
            }
            delay(30)

            // Phase 2: Transmit Image Slices Sequentially (ONE AFTER ANOTHER)
            // DO NOT finalize or finish until ALL slices have completed
            for (sliceIdx in 0 until totalSlices) {
                val sliceBytes = structuredPayload.slices[sliceIdx]
                val sliceChunks = splitIntoChunks(sliceBytes, chunkSize)
                val sliceNumberDisplay = "$sliceIdx/$totalSlices"

                if (isCustomerReceipt) {
                    Log.i(TAG, "[BLE_TRANSMISSION]\nslice $sliceNumberDisplay START\ntotal chunks=${sliceChunks.size}\nsliceBytes=${sliceBytes.size}")
                    Log.i(TAG, "[CUSTOMER_RECEIPT]\nslice $sliceNumberDisplay START")
                } else {
                    Log.i(TAG, "Transmitting slice $sliceNumberDisplay (${sliceBytes.size} bytes, ${sliceChunks.size} chunks)")
                }

                val sliceSuccess = sendChunkList(
                    chunks = sliceChunks,
                    blockName = "slice $sliceIdx",
                    isSlice = true,
                    sliceIdx = sliceIdx,
                    sliceTotal = totalSlices,
                    isCustReceipt = isCustomerReceipt
                )

                if (!sliceSuccess) {
                    if (isCustomerReceipt) {
                        Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Failed transmitting slice $sliceNumberDisplay")
                    }
                    updatePrintStatus(PrintJobStatus.FAILED, "Printing failed during slice $sliceNumberDisplay")
                    return@withContext PrintResult.Error("Printing failed during slice $sliceNumberDisplay")
                }

                if (isCustomerReceipt) {
                    Log.i(TAG, "[BLE_TRANSMISSION] slice $sliceIdx COMPLETE")
                    Log.i(TAG, "[CUSTOMER_RECEIPT]\nslice $sliceIdx COMPLETE")
                } else {
                    Log.i(TAG, "Slice $sliceNumberDisplay completed successfully")
                }

                // Inter-slice pacing delay: gives thermal print head time to burn
                // and clear microcontroller buffer before sending the next slice
                if (sliceIdx < totalSlices - 1) {
                    if (flowControlObserved && flowCredits.get() <= 0) {
                        withTimeoutOrNull(600) {
                            while (flowCredits.get() <= 0 && isGattConnected) {
                                flowCreditSignal.receive()
                            }
                        }
                    }
                    delay(450)
                }
            }

            // Phase 3: Transmit Print Finalization & Paper Feed Commands
            // IMPORTANT: ONLY SENT AFTER ALL SLICES HAVE COMPLETED!
            // Log separately: final print command sent, paper feed command sent, alignment/end command sent, BLE disconnect

            // 1. Final print command (CMD_STOP_PRINT_JOB)
            val stopSuccess = sendChunkList(
                chunks = stopChunks,
                blockName = "CMD_STOP_PRINT_JOB",
                isSlice = false,
                sliceIdx = totalSlices,
                sliceTotal = totalSlices,
                isCustReceipt = isCustomerReceipt
            )
            if (isCustomerReceipt) {
                Log.i(TAG, "[PRINTER_FINALIZATION] final print command sent: CMD_STOP_PRINT_JOB (0x1F 0xC0 0x01 0x01, success=$stopSuccess)")
            }
            if (!stopSuccess) {
                if (isCustomerReceipt) {
                    Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Failed transmitting final print command")
                }
                updatePrintStatus(PrintJobStatus.FAILED, "Failed to finalize print job (stop command)")
                return@withContext PrintResult.Error("Failed to finalize print job (stop command)")
            }
            delay(150)

            // 2. Paper feed command (CMD_FEED_PAPER_80PX)
            val feedSuccess = sendChunkList(
                chunks = feedChunks,
                blockName = "CMD_FEED_PAPER_80PX",
                isSlice = false,
                sliceIdx = totalSlices,
                sliceTotal = totalSlices,
                isCustReceipt = isCustomerReceipt
            )
            if (isCustomerReceipt) {
                Log.i(TAG, "[PRINTER_FINALIZATION] paper feed command sent: CMD_FEED_PAPER_80PX (0x1F 0x11 0x00 0x00 0x50, success=$feedSuccess)")
            }
            if (!feedSuccess) {
                if (isCustomerReceipt) {
                    Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Failed transmitting paper feed command")
                }
                updatePrintStatus(PrintJobStatus.FAILED, "Failed to feed paper")
                return@withContext PrintResult.Error("Failed to feed receipt paper")
            }
            delay(350)

            // 3. Alignment/end command (CMD_ALIGN_END)
            val alignSuccess = sendChunkList(
                chunks = alignChunks,
                blockName = "CMD_ALIGN_END",
                isSlice = false,
                sliceIdx = totalSlices,
                sliceTotal = totalSlices,
                isCustReceipt = isCustomerReceipt
            )
            if (isCustomerReceipt) {
                Log.i(TAG, "[PRINTER_FINALIZATION] alignment/end command sent: CMD_ALIGN_END (0x1F 0x11 0x50, success=$alignSuccess)")
            }
            if (!alignSuccess) {
                if (isCustomerReceipt) {
                    Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Failed transmitting alignment end command")
                }
                updatePrintStatus(PrintJobStatus.FAILED, "Failed to finalize print job (align end command)")
                return@withContext PrintResult.Error("Failed to finalize print job (align command)")
            }
            delay(200)

            // 4. BLE disconnect verification
            if (isCustomerReceipt) {
                Log.i(TAG, "[PRINTER_FINALIZATION] BLE disconnect: connected=$isGattConnected (BLE connection kept alive for rapid re-use, no disconnect invoked)")
            }

            // Settling delay: allow the printer's stepper motor to complete paper feed to the tear bar
            delay(300)

            if (isCustomerReceipt) {
                Log.i(TAG, "[CUSTOMER_RECEIPT]\nFINAL PRINT COMPLETE")
            }
            if (isDiagnostic) {
                Log.i(TAG, "[DIAG] printStreamComplete=true")
            }
            Log.i(TAG, "PRINT SUCCESS: All $totalSlices raster command(s) ($totalBytes bytes) successfully printed")
            _connectionState.value = PrinterConnectionState.Printing(100, "Print complete")
            updatePrintStatus(PrintJobStatus.SUCCESS, "Print complete")
            return@withContext PrintResult.Success

        } catch (e: Exception) {
            if (isCustomerReceipt) {
                Log.e(TAG, "CUSTOMER_RECEIPT_PRINT_FAILED: Exception ${e.message}", e)
            }
            Log.e(TAG, "PRINT FAILED with exception", e)
            updatePrintStatus(PrintJobStatus.FAILED, "Print failed: ${e.localizedMessage ?: "Unknown error"}")
            return@withContext PrintResult.Error("Print failed. Please check the printer connection.")
        } finally {
            val savedName = PrinterPreferences.getSavedPrinterName(context) ?: lastConnectedName ?: "P50S-496A-BLE"
            val savedMac = PrinterPreferences.getSavedPrinterMac(context) ?: lastConnectedMac ?: ""
            if (isPrinterConnected()) {
                _connectionState.value = PrinterConnectionState.Connected(savedName, savedMac)
            } else {
                _connectionState.value = PrinterConnectionState.Disconnected
            }
            pendingWriteCompleter = null
            isPrintInProgress = false
            printMutex.unlock()
        }
    }
}

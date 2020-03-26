package com.welie.blessedexample

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Handler
import com.welie.blessed.*
import timber.log.Timber
import java.util.*

class BluetoothHandler private constructor(private val context: Context) {
    // Local variables
    private lateinit var central: BluetoothCentral
    private val handler = Handler()
    private var currentTimeCounter = 0

    // Callback for peripherals
    private val peripheralCallback: BluetoothPeripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            Timber.i("discovered services")

            // Request a new connection priority
            peripheral.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            // Read manufacturer and model number from the Device Information Service
            if (peripheral.getService(DIS_SERVICE_UUID) != null) {
                peripheral.readCharacteristic(peripheral.getCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID))
                peripheral.readCharacteristic(peripheral.getCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID))
            }

            // Turn on notifications for Current Time Service
            if (peripheral.getService(CTS_SERVICE_UUID) != null) {
                val currentTimeCharacteristic = peripheral.getCharacteristic(CTS_SERVICE_UUID, CURRENT_TIME_CHARACTERISTIC_UUID)
                peripheral.setNotify(currentTimeCharacteristic, true)

                // If it has the write property we write the current time
                if (currentTimeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0) {
                    // Write the current time unless it is an Omron device
                    if (!peripheral.name.contains("BLEsmart_")) {
                        val parser = BluetoothBytesParser()
                        parser.setCurrentTime(Calendar.getInstance())
                        peripheral.writeCharacteristic(currentTimeCharacteristic, parser.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    }
                }
            }

            // Turn on notifications for Battery Service
            if (peripheral.getService(BTS_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(BTS_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID), true)
            }

            // Turn on notifications for Blood Pressure Service
            if (peripheral.getService(BLP_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID), true)
            }

            // Turn on notification for Health Thermometer Service
            if (peripheral.getService(HTS_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(HTS_SERVICE_UUID, TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID), true)
            }

            // Turn on notification for Heart Rate  Service
            if (peripheral.getService(HRS_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(HRS_SERVICE_UUID, HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID), true)
            }
        }

        override fun onNotificationStateUpdate(peripheral: BluetoothPeripheral, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothPeripheral.GATT_SUCCESS) {
                if (peripheral.isNotifying(characteristic)) {
                    Timber.i("SUCCESS: Notify set to 'on' for %s", characteristic.uuid)
                } else {
                    Timber.i("SUCCESS: Notify set to 'off' for %s", characteristic.uuid)
                }
            } else {
                Timber.e("ERROR: Changing notification state failed for %s", characteristic.uuid)
            }
        }

        override fun onCharacteristicWrite(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothPeripheral.GATT_SUCCESS) {
                Timber.i("SUCCESS: Writing <%s> to <%s>", BluetoothBytesParser.bytes2String(value), characteristic.uuid.toString())
            } else {
                Timber.i("ERROR: Failed writing <%s> to <%s>", BluetoothBytesParser.bytes2String(value), characteristic.uuid.toString())
            }
        }

        override fun onCharacteristicUpdate(peripheral: BluetoothPeripheral, value: ByteArray, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothPeripheral.GATT_SUCCESS) return
            val characteristicUUID = characteristic.uuid
            val parser = BluetoothBytesParser(value)
            if (characteristicUUID == BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID) {
                val measurement = BloodPressureMeasurement(value)
                val intent = Intent("BluetoothMeasurement")
                intent.putExtra("BloodPressure", measurement)
                context.sendBroadcast(intent)
                Timber.d("%s", measurement)
            } else if (characteristicUUID == TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID) {
                val measurement = TemperatureMeasurement(value)
                val intent = Intent("TemperatureMeasurement")
                intent.putExtra("Temperature", measurement)
                context.sendBroadcast(intent)
                Timber.d("%s", measurement)
            } else if (characteristicUUID == HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID) {
                val measurement = HeartRateMeasurement(value)
                val intent = Intent("HeartRateMeasurement")
                intent.putExtra("HeartRate", measurement)
                context.sendBroadcast(intent)
                Timber.d("%s", measurement)
            } else if (characteristicUUID == CURRENT_TIME_CHARACTERISTIC_UUID) {
                val currentTime = parser.dateTime
                Timber.i("Received device time: %s", currentTime)

                // Deal with Omron devices where we can only write currentTime under specific conditions
                if (peripheral.name.contains("BLEsmart_")) {
                    val isNotifying = peripheral.isNotifying(peripheral.getCharacteristic(BLP_SERVICE_UUID, BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID))
                    if (isNotifying) currentTimeCounter++

                    // We can set device time for Omron devices only if it is the first notification and currentTime is more than 10 min from now
                    val interval = Math.abs(Calendar.getInstance().timeInMillis - currentTime.time)
                    if (currentTimeCounter == 1 && interval > 10 * 60 * 1000) {
                        parser.setCurrentTime(Calendar.getInstance())
                        peripheral.writeCharacteristic(characteristic, parser.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    }
                }
            } else if (characteristicUUID == BATTERY_LEVEL_CHARACTERISTIC_UUID) {
                val batteryLevel = parser.getIntValue(BluetoothBytesParser.FORMAT_UINT8)
                Timber.i("Received battery level %d%%", batteryLevel)
            } else if (characteristicUUID == MANUFACTURER_NAME_CHARACTERISTIC_UUID) {
                val manufacturer = parser.getStringValue(0)
                Timber.i("Received manufacturer: %s", manufacturer)
            } else if (characteristicUUID == MODEL_NUMBER_CHARACTERISTIC_UUID) {
                val modelNumber = parser.getStringValue(0)
                Timber.i("Received modelnumber: %s", modelNumber)
            }
        }
    }

    // Callback for central
    private val bluetoothCentralCallback: BluetoothCentralCallback = object : BluetoothCentralCallback() {
        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            Timber.i("connected to '%s'", peripheral.name)
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: Int) {
            Timber.e("connection '%s' failed with status %d", peripheral.name, status)
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: Int) {
            Timber.i("disconnected '%s' with status %d", peripheral.name, status)

            // Reconnect to this device when it becomes available again
            handler.postDelayed({ central.autoConnectPeripheral(peripheral, peripheralCallback) }, 5000)
        }

        override fun onDiscoveredPeripheral(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            Timber.i("Found peripheral '%s'", peripheral.name)
            central.stopScan()
            central.connectPeripheral(peripheral, peripheralCallback)
        }

        override fun onBluetoothAdapterStateChanged(state: Int) {
            Timber.i("bluetooth adapter changed state to %d", state)
            if (state == BluetoothAdapter.STATE_ON) {
                // Bluetooth is on now, start scanning again
                // Scan for peripherals with a certain service UUIDs
                central.startPairingPopupHack()
                central.scanForPeripheralsWithServices(arrayOf(BLP_SERVICE_UUID, HTS_SERVICE_UUID, HRS_SERVICE_UUID))
            }
        }
    }

    companion object {
        // UUIDs for the Blood Pressure service (BLP)
        private val BLP_SERVICE_UUID = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
        private val BLOOD_PRESSURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A35-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Health Thermometer service (HTS)
        private val HTS_SERVICE_UUID = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb")
        private val TEMPERATURE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A1C-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Heart Rate service (HRS)
        private val HRS_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        private val HEARTRATE_MEASUREMENT_CHARACTERISTIC_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Device Information service (DIS)
        private val DIS_SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_NAME_CHARACTERISTIC_UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_CHARACTERISTIC_UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Current Time service (CTS)
        private val CTS_SERVICE_UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
        private val CURRENT_TIME_CHARACTERISTIC_UUID = UUID.fromString("00002A2B-0000-1000-8000-00805f9b34fb")

        // UUIDs for the Battery Service (BAS)
        private val BTS_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")
        private var instance: BluetoothHandler? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): BluetoothHandler? {
            if (instance == null) {
                instance = BluetoothHandler(context.applicationContext)
            }
            return instance
        }
    }

    init {

        // Create BluetoothCentral
        central = BluetoothCentral(context, bluetoothCentralCallback, Handler())

        // Scan for peripherals with a certain service UUIDs
        central.startPairingPopupHack()
        central.scanForPeripheralsWithServices(arrayOf(BLP_SERVICE_UUID, HTS_SERVICE_UUID, HRS_SERVICE_UUID))
    }
}
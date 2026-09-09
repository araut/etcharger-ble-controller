package com.araut.etchargerblecontroller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID

/**
 * Independent Android BLE controller for ETCharger-compatible EVSE hardware.
 *
 * IMPORTANT:
 * - No device-specific identifiers are stored in this source file.
 * - Provide the serial number and authorized user ID at runtime.
 * - Use only with EVSE hardware that you own or are authorized to control.
 * - Raw BLE frames are intentionally not logged because frames may contain
 *   persistent device/account identifiers.
 */
class BleController(
    context: Context,
    private val config: EvseConfig,
    private val onLog: (String) -> Unit,
    private val onDeviceFound: (BluetoothDevice, Int) -> Unit
) {

    data class EvseConfig(
        val serialNumber: String,
        val userId: Long,

        /**
         * Protocol field observed in the original application handshake.
         *
         * This value is configurable because its exact semantics have not
         * been fully characterized.
         */
        val handshakeZone: Int = DEFAULT_HANDSHAKE_ZONE
    ) {
        init {
            require(serialNumber.isNotBlank()) {
                "EVSE serial number must be configured"
            }

            require(serialNumber.length <= SERIAL_FIELD_LENGTH) {
                "EVSE serial number must fit in $SERIAL_FIELD_LENGTH ASCII bytes"
            }

            require(serialNumber.all { it.code in 0x20..0x7e }) {
                "EVSE serial number must contain printable ASCII characters only"
            }

            require(userId > 0) {
                "Authorized EVSE user ID must be configured"
            }

            require(handshakeZone in 0..255) {
                "Handshake zone must fit in one byte"
            }
        }
    }

    private val appContext =
        context.applicationContext

    private val bluetoothManager =
        context.getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as BluetoothManager

    private val adapter =
        bluetoothManager.adapter

    private val scanner
        get() = adapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? =
        null

    private var handshakeAccepted =
        false

    private val rxBuffer =
        mutableListOf<Byte>()

    companion object {

        private const val LOG_TAG =
            "ETChargerBLE"

        private const val SERIAL_FIELD_LENGTH =
            16

        private const val FRAME_MARKER =
            0x68

        private const val DEFAULT_SEQUENCE =
            0x00

        private const val ACTION_START =
            0x01

        private const val ACTION_PAUSE =
            0x02

        /**
         * Confirmed by observing the original application's BLE traffic.
         *
         * Both Start and Pause used current=0x00.
         *
         * The exact semantic meaning of zero has not been fully established.
         */
        private const val START_STOP_CURRENT =
            0x00

        /**
         * Retained as a configurable protocol default rather than embedding
         * any location or account information in source.
         */
        const val DEFAULT_HANDSHAKE_ZONE =
            0x04

        val ET_SERVICE_UUID: UUID =
            UUID.fromString(
                "0000a002-0000-1000-8000-00805f9b34fb"
            )

        val ET_TX_UUID: UUID =
            UUID.fromString(
                "0000c302-0000-1000-8000-00805f9b34fb"
            )

        val ET_RX_UUID: UUID =
            UUID.fromString(
                "0000c305-0000-1000-8000-00805f9b34fb"
            )

        val CCCD_UUID: UUID =
            UUID.fromString(
                "00002902-0000-1000-8000-00805f9b34fb"
            )
    }

    // ---------------------------------------------------------------------
    // Primitive encoding
    // ---------------------------------------------------------------------

    private fun uint32be(
        value: Long
    ): ByteArray {

        return byteArrayOf(
            ((value shr 24) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            (value and 0xff).toByte()
        )
    }

    // ---------------------------------------------------------------------
    // Frame construction
    // ---------------------------------------------------------------------

    /**
     * ETCharger frame:
     *
     * byte 0       0x68
     * byte 1       protocol length
     * byte 2       sequence
     * byte 3       command
     * bytes 4..19  serial number, null padded
     * bytes 20..   command body
     * final byte   checksum
     *
     * protocol length = total frame size - 2
     *
     * checksum = sum(sequence through final body byte) mod 256
     */
    private fun buildFrame(
        command: Int,
        body: ByteArray
    ): ByteArray {

        require(command in 0..255)

        val serialBytes =
            ByteArray(
                SERIAL_FIELD_LENGTH
            )

        val source =
            config.serialNumber.toByteArray(
                Charsets.US_ASCII
            )

        source.copyInto(
            destination = serialBytes,
            endIndex = minOf(
                SERIAL_FIELD_LENGTH,
                source.size
            )
        )

        val payload =
            byteArrayOf(
                DEFAULT_SEQUENCE.toByte(),
                command.toByte()
            ) +
                    serialBytes +
                    body

        val checksum =
            payload.fold(0) { sum, byte ->
                (
                        sum +
                                (byte.toInt() and 0xff)
                        ) and 0xff
            }

        val protocolLength =
            body.size + 19

        return byteArrayOf(
            FRAME_MARKER.toByte(),
            protocolLength.toByte()
        ) +
                payload +
                checksum.toByte()
    }

    // ---------------------------------------------------------------------
    // Handshake request 0x01
    // ---------------------------------------------------------------------

    private fun buildHandshake(): ByteArray {

        val body =
            mutableListOf<Byte>()

        // userType
        body +=
            0x00.toByte()

        // userId
        body +=
            uint32be(
                config.userId
            ).toList()

        // authBeginTime
        body +=
            uint32be(
                0
            ).toList()

        // authEndTime
        body +=
            uint32be(
                0
            ).toList()

        // Current Unix timestamp
        body +=
            uint32be(
                System.currentTimeMillis() / 1000
            ).toList()

        // Protocol handshake zone field.
        body +=
            config.handshakeZone.toByte()

        return buildFrame(
            command = 0x01,
            body = body.toByteArray()
        )
    }

    // ---------------------------------------------------------------------
    // Start/Pause request 0x03
    // ---------------------------------------------------------------------

    private fun buildStartStopRequest(
        action: Int
    ): ByteArray {

        require(
            action == ACTION_START ||
                    action == ACTION_PAUSE
        ) {
            "Invalid Start/Pause action"
        }

        /*
         * Recovered and hardware-validated request body:
         *
         * userType uint8
         * userId   uint32 BE
         * current  uint8
         * action   uint8
         *
         * Observed:
         *
         * Start:
         *   current = 0x00
         *   action  = 0x01
         *
         * Pause:
         *   current = 0x00
         *   action  = 0x02
         *
         * No real device/account identifiers are included here.
         */

        val body =
            byteArrayOf(
                0x00
            ) +
                    uint32be(
                        config.userId
                    ) +
                    byteArrayOf(
                        START_STOP_CURRENT.toByte(),
                        action.toByte()
                    )

        return buildFrame(
            command = 0x03,
            body = body
        )
    }

    // ---------------------------------------------------------------------
    // EVSE Info request 0x07
    // ---------------------------------------------------------------------

    private fun buildEvseInfoRequest(): ByteArray {

        val body =
            byteArrayOf(
                0x00
            ) +
                    uint32be(
                        config.userId
                    )

        return buildFrame(
            command = 0x07,
            body = body
        )
    }

    // ---------------------------------------------------------------------
    // Current Status request 0x09
    // ---------------------------------------------------------------------

    private fun buildCurrentStatusRequest(): ByteArray {

        val body =
            byteArrayOf(
                0x00
            ) +
                    uint32be(
                        config.userId
                    )

        return buildFrame(
            command = 0x09,
            body = body
        )
    }

    // ---------------------------------------------------------------------
    // Public read operations
    // ---------------------------------------------------------------------

    fun readCurrentStatus(): Boolean {

        if (gatt == null) {
            log(
                "Current Status not sent: EVSE is not connected"
            )

            return false
        }

        if (!handshakeAccepted) {
            log(
                "Current Status not sent: handshake not authorized"
            )

            return false
        }

        return sendCurrentStatusRequest()
    }

    private fun sendCurrentStatusRequest(): Boolean {

        val packet =
            buildCurrentStatusRequest()

        log(
            "Sending Current Status request"
        )

        return write(
            ET_SERVICE_UUID,
            ET_TX_UUID,
            packet
        )
    }

    fun readEvseInfo(): Boolean {

        if (gatt == null) {
            log(
                "EVSE Info not sent: EVSE is not connected"
            )

            return false
        }

        if (!handshakeAccepted) {
            log(
                "EVSE Info not sent: handshake not authorized"
            )

            return false
        }

        return sendEvseInfoRequest()
    }

    private fun sendEvseInfoRequest(): Boolean {

        val packet =
            buildEvseInfoRequest()

        log(
            "Sending EVSE Info request"
        )

        return write(
            ET_SERVICE_UUID,
            ET_TX_UUID,
            packet
        )
    }

    // ---------------------------------------------------------------------
    // Public charging controls
    // ---------------------------------------------------------------------

    fun startCharging(): Boolean {

        if (gatt == null) {
            log(
                "Start refused: EVSE is not connected"
            )

            return false
        }

        if (!handshakeAccepted) {
            log(
                "Start refused: handshake not authorized"
            )

            return false
        }

        val packet =
            buildStartStopRequest(
                ACTION_START
            )

        log(
            "Sending Start Charging request"
        )

        return write(
            ET_SERVICE_UUID,
            ET_TX_UUID,
            packet
        )
    }

    fun pauseCharging(): Boolean {

        if (gatt == null) {
            log(
                "Pause refused: EVSE is not connected"
            )

            return false
        }

        if (!handshakeAccepted) {
            log(
                "Pause refused: handshake not authorized"
            )

            return false
        }

        val packet =
            buildStartStopRequest(
                ACTION_PAUSE
            )

        log(
            "Sending Pause Charging request"
        )

        return write(
            ET_SERVICE_UUID,
            ET_TX_UUID,
            packet
        )
    }

    // ---------------------------------------------------------------------
    // BLE scanning
    // ---------------------------------------------------------------------

    private val scanCallback =
        object : ScanCallback() {

            @SuppressLint("MissingPermission")
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {

                /*
                 * Do not log:
                 *
                 * - MAC address
                 * - advertised device name
                 * - serial number
                 *
                 * The UI receives the BluetoothDevice so it may display
                 * devices locally, but this controller does not persist
                 * or log device identifiers.
                 */

                log(
                    "BLE device discovered; RSSI=${result.rssi}"
                )

                onDeviceFound(
                    result.device,
                    result.rssi
                )
            }

            override fun onScanFailed(
                errorCode: Int
            ) {

                log(
                    "BLE scan failed: error=$errorCode"
                )
            }
        }

    @SuppressLint("MissingPermission")
    fun startScan() {

        log(
            "Starting BLE scan"
        )

        scanner?.startScan(
            scanCallback
        ) ?: log(
            "BLE scanner unavailable"
        )
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {

        scanner?.stopScan(
            scanCallback
        )

        log(
            "BLE scan stopped"
        )
    }

    // ---------------------------------------------------------------------
    // Connect / disconnect
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun connect(
        device: BluetoothDevice
    ) {

        stopScan()

        /*
         * Do not log device.address or device.name here.
         */
        log(
            "Connecting to selected EVSE"
        )

        gatt?.close()
        gatt =
            null

        handshakeAccepted =
            false

        rxBuffer.clear()

        gatt =
            device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {

        gatt?.disconnect()
        gatt?.close()

        gatt =
            null

        handshakeAccepted =
            false

        rxBuffer.clear()

        log(
            "Disconnected from EVSE"
        )
    }

    // ---------------------------------------------------------------------
    // GATT callbacks
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                log(
                    "BLE connection state changed: " +
                            "status=$status state=$newState"
                )

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS &&
                    newState ==
                    BluetoothProfile.STATE_CONNECTED
                ) {

                    handshakeAccepted =
                        false

                    rxBuffer.clear()

                    log(
                        "Connected; discovering GATT services"
                    )

                    val started =
                        gatt.discoverServices()

                    log(
                        "Service discovery started=$started"
                    )

                } else if (
                    newState ==
                    BluetoothProfile.STATE_DISCONNECTED
                ) {

                    handshakeAccepted =
                        false

                    rxBuffer.clear()

                    log(
                        "EVSE disconnected"
                    )
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    log(
                        "GATT service discovery failed: status=$status"
                    )

                    return
                }

                val service =
                    gatt.getService(
                        ET_SERVICE_UUID
                    )

                if (service == null) {
                    log(
                        "Expected EVSE BLE service not found"
                    )

                    return
                }

                val tx =
                    service.getCharacteristic(
                        ET_TX_UUID
                    )

                if (tx == null) {
                    log(
                        "Expected EVSE TX characteristic not found"
                    )

                    return
                }

                val rx =
                    service.getCharacteristic(
                        ET_RX_UUID
                    )

                if (rx == null) {
                    log(
                        "Expected EVSE RX characteristic not found"
                    )

                    return
                }

                log(
                    "ETCharger-compatible GATT interface found"
                )

                enableNotifications(
                    gatt,
                    rx
                )
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {

                log(
                    "Notification descriptor write completed: status=$status"
                )

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS &&
                    descriptor.characteristic.uuid ==
                    ET_RX_UUID
                ) {

                    log(
                        "EVSE notifications enabled"
                    )

                    val started =
                        gatt.requestMtu(
                            247
                        )

                    log(
                        "MTU request started=$started"
                    )
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {

                log(
                    "MTU negotiation completed: mtu=$mtu status=$status"
                )

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    log(
                        "MTU negotiation failed"
                    )

                    return
                }

                if (mtu < 42) {
                    log(
                        "Negotiated MTU is too small for handshake"
                    )

                    return
                }

                val handshake =
                    buildHandshake()

                log(
                    "Sending EVSE handshake"
                )

                val started =
                    write(
                        ET_SERVICE_UUID,
                        ET_TX_UUID,
                        handshake
                    )

                log(
                    "Handshake write started=$started"
                )
            }

            @Deprecated(
                "Deprecated in API 33 but retained for older Android versions"
            )
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {

                /*
                 * This EVSE has been observed to return a non-success
                 * Android GATT status while still accepting and processing
                 * the application-level packet.
                 *
                 * Therefore the protocol response is treated as the
                 * authoritative result.
                 */

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS
                ) {

                    log(
                        "BLE characteristic write callback succeeded"
                    )

                } else {

                    log(
                        "BLE write callback status=$status; " +
                                "waiting for EVSE protocol response"
                    )
                }
            }

            @Deprecated(
                "Deprecated in API 33"
            )
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {

                val value =
                    characteristic.value
                        ?: return

                handleNotification(
                    characteristic.uuid,
                    value
                )
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                handleNotification(
                    characteristic.uuid,
                    value
                )
            }
        }

    // ---------------------------------------------------------------------
    // Enable notifications
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {

        val localEnabled =
            gatt.setCharacteristicNotification(
                characteristic,
                true
            )

        if (!localEnabled) {
            log(
                "Unable to enable local EVSE notifications"
            )

            return
        }

        val cccd =
            characteristic.getDescriptor(
                CCCD_UUID
            )

        if (cccd == null) {
            log(
                "EVSE notification descriptor not found"
            )

            return
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val result =
                gatt.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor
                        .ENABLE_NOTIFICATION_VALUE
                )

            log(
                "Notification descriptor write started: result=$result"
            )

        } else {

            @Suppress("DEPRECATION")
            cccd.value =
                BluetoothGattDescriptor
                    .ENABLE_NOTIFICATION_VALUE

            @Suppress("DEPRECATION")
            val started =
                gatt.writeDescriptor(
                    cccd
                )

            log(
                "Notification descriptor write started=$started"
            )
        }
    }

    // ---------------------------------------------------------------------
    // Incoming notifications
    // ---------------------------------------------------------------------

    private fun handleNotification(
        uuid: UUID,
        value: ByteArray
    ) {

        if (
            uuid !=
            ET_RX_UUID
        ) {
            return
        }

        /*
         * Do NOT print the raw notification.
         *
         * Protocol frames contain the EVSE serial and may also contain
         * account/device identifiers or usage information.
         */
        log(
            "EVSE notification received: ${value.size} bytes"
        )

        handleIncomingBytes(
            value
        )
    }

    // ---------------------------------------------------------------------
    // Frame reassembly
    // ---------------------------------------------------------------------

    private fun handleIncomingBytes(
        data: ByteArray
    ) {

        rxBuffer.addAll(
            data.toList()
        )

        while (true) {

            if (
                rxBuffer.size < 3
            ) {
                return
            }

            while (
                rxBuffer.isNotEmpty() &&
                rxBuffer[0] !=
                FRAME_MARKER.toByte()
            ) {
                rxBuffer.removeAt(
                    0
                )
            }

            if (
                rxBuffer.size < 3
            ) {
                return
            }

            /*
             * byte 1 = single-byte protocol length
             * byte 2 = sequence
             */
            val protocolLength =
                rxBuffer[1]
                    .toInt() and 0xff

            val totalFrameLength =
                protocolLength + 2

            if (
                totalFrameLength < 4 ||
                totalFrameLength > 512
            ) {

                log(
                    "Invalid EVSE frame length"
                )

                rxBuffer.removeAt(
                    0
                )

                continue
            }

            if (
                rxBuffer.size <
                totalFrameLength
            ) {
                return
            }

            val frame =
                ByteArray(
                    totalFrameLength
                )

            for (
            i in
            0 until totalFrameLength
            ) {
                frame[i] =
                    rxBuffer[i]
            }

            repeat(
                totalFrameLength
            ) {
                rxBuffer.removeAt(
                    0
                )
            }

            handleCompleteFrame(
                frame
            )
        }
    }

    // ---------------------------------------------------------------------
    // Complete frame dispatcher
    // ---------------------------------------------------------------------

    private fun handleCompleteFrame(
        frame: ByteArray
    ) {

        if (
            frame.size < 4
        ) {
            return
        }

        val command =
            frame[3]
                .toInt() and 0xff

        val sequence =
            frame[2]
                .toInt() and 0xff

        /*
         * Logging command/sequence/frame length is safe.
         * The raw frame itself is intentionally omitted.
         */
        log(
            "EVSE frame received: " +
                    "cmd=0x%02X seq=%d len=%d"
                        .format(
                            command,
                            sequence,
                            frame.size
                        )
        )

        when (command) {

            0x02 ->
                parseHandshakeReply(
                    frame
                )

            0x04 ->
                parseStartChargingReply(
                    frame
                )

            0x08 ->
                parseEvseInfoReply(
                    frame
                )

            0x0A ->
                parseCurrentStatusReply(
                    frame
                )

            else ->
                log(
                    "Unhandled EVSE command " +
                            "0x%02X".format(
                                command
                            )
                )
        }
    }

    // ---------------------------------------------------------------------
    // Handshake reply 0x02
    // ---------------------------------------------------------------------

    private fun parseHandshakeReply(
        frame: ByteArray
    ) {

        if (
            frame.size < 52
        ) {

            log(
                "Handshake reply too short"
            )

            return
        }

        val offset =
            20

        try {

            /*
             * Parse only fields required for operation or useful
             * non-sensitive diagnostics.
             *
             * Deliberately avoid logging:
             * - returned user ID
             * - usage counters
             * - timestamps tied to the device/account
             */

            val permAck =
                u8(
                    frame,
                    offset + 5
                )

            val minCurrent =
                u8(
                    frame,
                    offset + 9
                )

            val maxCurrent =
                u8(
                    frame,
                    offset + 10
                )

            val deviceStatus =
                u8(
                    frame,
                    offset + 15
                )

            val leftHardwareVersion =
                u8(
                    frame,
                    offset + 16
                )

            val rightHardwareVersion =
                u8(
                    frame,
                    offset + 17
                )

            val bluetoothSoftwareVersion =
                u8(
                    frame,
                    offset + 18
                )

            log(
                "Handshake reply parsed"
            )

            log(
                "EVSE current capability: " +
                        "min=${minCurrent}A max=${maxCurrent}A"
            )

            log(
                "EVSE device status=$deviceStatus"
            )

            log(
                "EVSE firmware versions: " +
                        "left=$leftHardwareVersion " +
                        "right=$rightHardwareVersion " +
                        "bluetooth=$bluetoothSoftwareVersion"
            )

            if (
                permAck == 1
            ) {

                handshakeAccepted =
                    true

                log(
                    "Handshake authorization accepted"
                )

                /*
                 * Automatically perform initial read-only status request.
                 */
                sendCurrentStatusRequest()

            } else {

                handshakeAccepted =
                    false

                log(
                    "Handshake authorization not accepted"
                )
            }

        } catch (
            exception: IndexOutOfBoundsException
        ) {

            log(
                "Unable to parse handshake reply"
            )
        }
    }

    // ---------------------------------------------------------------------
    // Start/Pause reply 0x04
    // ---------------------------------------------------------------------

    private fun parseStartChargingReply(
        frame: ByteArray
    ) {

        val offset =
            20

        val knownPayloadLength =
            9

        if (
            frame.size <
            offset +
            knownPayloadLength +
            1
        ) {

            log(
                "Start/Pause reply too short"
            )

            return
        }

        try {

            val status =
                u8(
                    frame,
                    offset
                )

            /*
             * startTime and totalCharge exist in the response but are
             * deliberately not logged here because their semantics are
             * still under investigation and usage data may be private.
             */

            log(
                "Start/Pause reply received: status=$status"
            )

            /*
             * Do not infer actual charger state from 0x04 alone.
             * Read Current Status afterward.
             */
            sendCurrentStatusRequest()

        } catch (
            exception: IndexOutOfBoundsException
        ) {

            log(
                "Unable to parse Start/Pause reply"
            )
        }
    }

    // ---------------------------------------------------------------------
    // Current Status reply 0x0A
    // ---------------------------------------------------------------------

    private fun parseCurrentStatusReply(
        frame: ByteArray
    ) {

        val offset =
            20

        val knownPayloadLength =
            21

        if (
            frame.size <
            offset +
            knownPayloadLength +
            1
        ) {

            log(
                "Current Status reply too short"
            )

            return
        }

        try {

            val status =
                u8(
                    frame,
                    offset
                )

            val faultCode =
                u8(
                    frame,
                    offset + 1
                )

            val voltageRaw =
                u16be(
                    frame,
                    offset + 2
                )

            val currentRaw =
                u16be(
                    frame,
                    offset + 4
                )

            /*
             * offset + 6  : powerRaw
             * offset + 8  : chargePower
             * offset + 12 : totalChargePower
             * offset + 16 : chargeTime
             *
             * These values are intentionally not logged publicly because
             * some units remain unverified and cumulative values may
             * disclose usage history.
             */

            val outputCurrentRaw =
                u16be(
                    frame,
                    offset + 18
                )

            val realMode =
                u8(
                    frame,
                    offset + 20
                )

            val voltage =
                voltageRaw / 10.0

            val current =
                currentRaw / 10.0

            val outputCurrent =
                outputCurrentRaw / 10.0

            log(
                "Current Status:"
            )

            log(
                "  status=$status faultCode=$faultCode"
            )

            log(
                "  voltage=${"%.1f".format(voltage)}V"
            )

            log(
                "  current=${"%.1f".format(current)}A"
            )

            log(
                "  configuredOutputCurrent=" +
                        "${"%.1f".format(outputCurrent)}A"
            )

            log(
                "  realMode=$realMode"
            )

        } catch (
            exception: IndexOutOfBoundsException
        ) {

            log(
                "Unable to parse Current Status reply"
            )
        }
    }

    // ---------------------------------------------------------------------
    // EVSE Info reply 0x08
    // ---------------------------------------------------------------------

    private fun parseEvseInfoReply(
        frame: ByteArray
    ) {

        val offset =
            20

        val knownPayloadLength =
            18

        if (
            frame.size <
            offset +
            knownPayloadLength +
            1
        ) {

            log(
                "EVSE Info reply too short"
            )

            return
        }

        try {

            val status =
                u8(
                    frame,
                    offset
                )

            val faultCode =
                u8(
                    frame,
                    offset + 1
                )

            val voltageRaw =
                u16be(
                    frame,
                    offset + 2
                )

            val currentRaw =
                u16be(
                    frame,
                    offset + 4
                )

            /*
             * Power / energy / cumulative values are intentionally
             * not logged by the public controller.
             */

            val voltage =
                voltageRaw / 10.0

            val current =
                currentRaw / 10.0

            log(
                "EVSE Info:"
            )

            log(
                "  status=$status faultCode=$faultCode"
            )

            log(
                "  voltage=${"%.1f".format(voltage)}V"
            )

            log(
                "  current=${"%.1f".format(current)}A"
            )

        } catch (
            exception: IndexOutOfBoundsException
        ) {

            log(
                "Unable to parse EVSE Info reply"
            )
        }
    }

    // ---------------------------------------------------------------------
    // Decoding helpers
    // ---------------------------------------------------------------------

    private fun u8(
        data: ByteArray,
        offset: Int
    ): Int {

        return data[offset]
            .toInt() and 0xff
    }

    private fun u16be(
        data: ByteArray,
        offset: Int
    ): Int {

        return (
                (
                        data[offset]
                            .toInt() and 0xff
                        ) shl 8
                ) or
                (
                        data[offset + 1]
                            .toInt() and 0xff
                        )
    }

    @Suppress("unused")
    private fun u32be(
        data: ByteArray,
        offset: Int
    ): Long {

        return (
                (
                        data[offset]
                            .toLong() and 0xff
                        ) shl 24
                ) or
                (
                        (
                                data[offset + 1]
                                    .toLong() and 0xff
                                ) shl 16
                        ) or
                (
                        (
                                data[offset + 2]
                                    .toLong() and 0xff
                                ) shl 8
                        ) or
                (
                        data[offset + 3]
                            .toLong() and 0xff
                        )
    }

    // ---------------------------------------------------------------------
    // Generic BLE writer
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun write(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray
    ): Boolean {

        val currentGatt =
            gatt ?: run {

                log(
                    "BLE write failed: no GATT connection"
                )

                return false
            }

        val service =
            currentGatt.getService(
                serviceUuid
            ) ?: run {

                log(
                    "BLE write failed: expected service not found"
                )

                return false
            }

        val characteristic =
            service.getCharacteristic(
                characteristicUuid
            ) ?: run {

                log(
                    "BLE write failed: expected characteristic not found"
                )

                return false
            }

        /*
         * DO NOT log payload here.
         *
         * The packet contains:
         * - EVSE serial number
         * - potentially an authorized user ID
         * - potentially operational/usage data
         */

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val result =
                currentGatt.writeCharacteristic(
                    characteristic,
                    payload,
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_DEFAULT
                )

            val started =
                result ==
                        BluetoothStatusCodes.SUCCESS

            log(
                "BLE write submitted: started=$started"
            )

            started

        } else {

            @Suppress("DEPRECATION")
            characteristic.value =
                payload

            @Suppress("DEPRECATION")
            characteristic.writeType =
                BluetoothGattCharacteristic
                    .WRITE_TYPE_DEFAULT

            @Suppress("DEPRECATION")
            val started =
                currentGatt.writeCharacteristic(
                    characteristic
                )

            log(
                "BLE write submitted: started=$started"
            )

            started
        }
    }

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    private fun log(
        message: String
    ) {

        Log.d(
            LOG_TAG,
            message
        )

        onLog(
            message
        )
    }
}
package com.araut.etchargerblecontroller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * UI for the independent ETCharger-compatible BLE controller.
 *
 * Configuration sources:
 *
 * 1. Local DEBUG configuration from evse.local.properties
 * 2. Manual runtime configuration entered through the UI
 *
 * Privacy:
 *
 * - No EVSE serial number is logged.
 * - No authorized user ID is logged.
 * - No Bluetooth MAC address is displayed or logged.
 * - No advertised device name is displayed.
 * - Raw BLE protocol frames are not displayed.
 *
 * Intended only for EVSE hardware the user owns or is
 * authorized to control.
 */
class MainActivity :
    AppCompatActivity() {

    private var bleController:
            BleController? =
        null

    private lateinit var serialInput:
            EditText

    private lateinit var userIdInput:
            EditText

    private lateinit var configurationStatus:
            TextView

    private lateinit var deviceSpinner:
            Spinner

    private lateinit var logText:
            TextView

    private val devices =
        mutableListOf<BluetoothDevice>()

    private val deviceLabels =
        mutableListOf<String>()

    private lateinit var deviceAdapter:
            ArrayAdapter<String>

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions()
        ) { permissions ->

            val denied =
                permissions
                    .filterValues { granted ->
                        !granted
                    }
                    .keys

            if (
                denied.isEmpty()
            ) {

                appendLog(
                    "Bluetooth permissions granted"
                )

            } else {

                appendLog(
                    "Required Bluetooth permissions were not granted"
                )
            }
        }

    // ---------------------------------------------------------------------
    // Activity lifecycle
    // ---------------------------------------------------------------------

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        createUi()

        requestBluetoothPermissions()

        /*
         * Development convenience:
         *
         * Attempt to load local DEBUG configuration.
         *
         * If none exists, retain the normal manual configuration
         * workflow.
         */
        val localConfigurationLoaded =
            loadLocalConfiguration()

        if (
            !localConfigurationLoaded
        ) {

            appendLog(
                "Enter EVSE configuration to begin"
            )
        }
    }

    // ---------------------------------------------------------------------
    // Local development configuration
    // ---------------------------------------------------------------------

    /**
     * Loads optional DEBUG configuration originating from
     * evse.local.properties.
     *
     * No configuration values are displayed or logged.
     */
    private fun loadLocalConfiguration():
            Boolean {

        val config =
            LocalEvseConfig.load()
                ?: return false

        createController(
            config
        )

        configurationStatus.text =
            "Local development configuration loaded"

        appendLog(
            "Local EVSE development configuration loaded"
        )

        return true
    }

    // ---------------------------------------------------------------------
    // Manual controller configuration
    // ---------------------------------------------------------------------

    private fun applyConfiguration() {

        val serialNumber =
            serialInput.text
                .toString()
                .trim()

        val userIdText =
            userIdInput.text
                .toString()
                .trim()

        if (
            serialNumber.isBlank()
        ) {

            appendLog(
                "Configuration rejected: EVSE serial is required"
            )

            return
        }

        val userId =
            userIdText
                .toLongOrNull()

        if (
            userId == null ||
            userId <= 0
        ) {

            appendLog(
                "Configuration rejected: valid authorized user ID is required"
            )

            return
        }

        val config =
            try {

                BleController.EvseConfig(
                    serialNumber =
                        serialNumber,

                    userId =
                        userId
                )

            } catch (
                exception: IllegalArgumentException
            ) {

                appendLog(
                    "Configuration rejected"
                )

                return
            }

        createController(
            config
        )

        configurationStatus.text =
            "Manual configuration loaded"

        appendLog(
            "EVSE configuration loaded"
        )

        /*
         * Remove values from visible UI after loading them.
         */
        serialInput.text.clear()
        userIdInput.text.clear()
    }

    // ---------------------------------------------------------------------
    // Controller creation
    // ---------------------------------------------------------------------

    /**
     * Single controller creation path used by both:
     *
     * - local DEBUG configuration
     * - manual configuration
     */
    private fun createController(
        config: BleController.EvseConfig
    ) {

        /*
         * Dispose of any previous BLE connection/controller.
         */
        bleController
            ?.disconnect()

        bleController =
            BleController(
                context =
                    this,

                config =
                    config,

                onLog = { message ->

                    runOnUiThread {

                        appendLog(
                            message
                        )
                    }
                },

                onDeviceFound = {
                        device,
                        rssi ->

                    runOnUiThread {

                        addOrUpdateDevice(
                            device,
                            rssi
                        )
                    }
                }
            )
    }

    /**
     * Returns the configured controller or writes a safe message.
     */
    private fun controllerOrLog():
            BleController? {

        val controller =
            bleController

        if (
            controller == null
        ) {

            appendLog(
                "Configure the EVSE before using BLE controls"
            )

            return null
        }

        return controller
    }

    // ---------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------

    private fun createUi() {

        val root =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    24,
                    64,
                    24,
                    64
                )
            }

        // -----------------------------------------------------------------
        // Title
        // -----------------------------------------------------------------

        val title =
            TextView(
                this
            ).apply {

                text =
                    "ETCharger BLE Controller"

                textSize =
                    22f
            }

        root.addView(
            title
        )

        val subtitle =
            TextView(
                this
            ).apply {

                text =
                    "Independent local BLE controller"

                textSize =
                    13f

                setPadding(
                    0,
                    0,
                    0,
                    24
                )
            }

        root.addView(
            subtitle
        )

        // -----------------------------------------------------------------
        // Configuration
        // -----------------------------------------------------------------

        val configurationTitle =
            TextView(
                this
            ).apply {

                text =
                    "EVSE Configuration"

                textSize =
                    17f
            }

        root.addView(
            configurationTitle
        )

        val configurationHelp =
            TextView(
                this
            ).apply {

                text =
                    "Local DEBUG configuration is loaded automatically " +
                            "when available. Otherwise enter configuration " +
                            "for EVSE hardware you own or are authorized " +
                            "to control."

                textSize =
                    11f

                setPadding(
                    0,
                    4,
                    0,
                    12
                )
            }

        root.addView(
            configurationHelp
        )

        serialInput =
            EditText(
                this
            ).apply {

                hint =
                    "EVSE serial number"

                inputType =
                    InputType.TYPE_CLASS_TEXT

                isSingleLine =
                    true

                importantForAutofill =
                    EditText
                        .IMPORTANT_FOR_AUTOFILL_NO

                contentDescription =
                    "EVSE serial number"
            }

        root.addView(
            serialInput
        )

        userIdInput =
            EditText(
                this
            ).apply {

                hint =
                    "Authorized user ID"

                inputType =
                    InputType.TYPE_CLASS_NUMBER

                isSingleLine =
                    true

                importantForAutofill =
                    EditText
                        .IMPORTANT_FOR_AUTOFILL_NO

                contentDescription =
                    "Authorized EVSE user ID"
            }

        root.addView(
            userIdInput
        )

        val applyConfigurationButton =
            Button(
                this
            ).apply {

                text =
                    "Apply Configuration"

                setOnClickListener {

                    applyConfiguration()
                }
            }

        root.addView(
            applyConfigurationButton
        )

        configurationStatus =
            TextView(
                this
            ).apply {

                text =
                    "Configuration not loaded"

                textSize =
                    11f

                setPadding(
                    0,
                    4,
                    0,
                    20
                )
            }

        root.addView(
            configurationStatus
        )

        // -----------------------------------------------------------------
        // BLE scanning
        // -----------------------------------------------------------------

        val scanTitle =
            TextView(
                this
            ).apply {

                text =
                    "Bluetooth Devices"

                textSize =
                    17f
            }

        root.addView(
            scanTitle
        )

        val scanRow =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val scanButton =
            Button(
                this
            ).apply {

                text =
                    "Scan"

                setOnClickListener {

                    val controller =
                        controllerOrLog()
                            ?: return@setOnClickListener

                    devices.clear()

                    deviceLabels.clear()

                    deviceAdapter
                        .notifyDataSetChanged()

                    controller.startScan()
                }
            }

        val stopScanButton =
            Button(
                this
            ).apply {

                text =
                    "Stop Scan"

                setOnClickListener {

                    controllerOrLog()
                        ?.stopScan()
                }
            }

        scanRow.addView(
            scanButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        scanRow.addView(
            stopScanButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(
            scanRow
        )

        // -----------------------------------------------------------------
        // Device selection
        // -----------------------------------------------------------------

        deviceSpinner =
            Spinner(
                this
            )

        deviceAdapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                deviceLabels
            )

        deviceAdapter
            .setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )

        deviceSpinner.adapter =
            deviceAdapter

        root.addView(
            deviceSpinner
        )

        // -----------------------------------------------------------------
        // Connection
        // -----------------------------------------------------------------

        val connectRow =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val connectButton =
            Button(
                this
            ).apply {

                text =
                    "Connect Selected"

                setOnClickListener {

                    connectSelectedDevice()
                }
            }

        val disconnectButton =
            Button(
                this
            ).apply {

                text =
                    "Disconnect"

                setOnClickListener {

                    controllerOrLog()
                        ?.disconnect()
                }
            }

        connectRow.addView(
            connectButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        connectRow.addView(
            disconnectButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(
            connectRow
        )

        // -----------------------------------------------------------------
        // Read-only operations
        // -----------------------------------------------------------------

        val readRow =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val evseInfoButton =
            Button(
                this
            ).apply {

                text =
                    "Read EVSE Info"

                setOnClickListener {

                    appendLog(
                        "Read EVSE Info requested"
                    )

                    controllerOrLog()
                        ?.readEvseInfo()
                }
            }

        val currentStatusButton =
            Button(
                this
            ).apply {

                text =
                    "Read Status"

                setOnClickListener {

                    appendLog(
                        "Read Current Status requested"
                    )

                    controllerOrLog()
                        ?.readCurrentStatus()
                }
            }

        readRow.addView(
            evseInfoButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        readRow.addView(
            currentStatusButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(
            readRow
        )

        // -----------------------------------------------------------------
        // Charging controls
        // -----------------------------------------------------------------

        val controlRow =
            LinearLayout(
                this
            ).apply {

                orientation =
                    LinearLayout.HORIZONTAL
            }

        val startChargingButton =
            Button(
                this
            ).apply {

                text =
                    "Start Charging"

                setOnClickListener {

                    appendLog(
                        "Start Charging requested"
                    )

                    controllerOrLog()
                        ?.startCharging()
                }
            }

        val pauseChargingButton =
            Button(
                this
            ).apply {

                text =
                    "Pause Charging"

                setOnClickListener {

                    appendLog(
                        "Pause Charging requested"
                    )

                    controllerOrLog()
                        ?.pauseCharging()
                }
            }

        controlRow.addView(
            startChargingButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        controlRow.addView(
            pauseChargingButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(
            controlRow
        )

        // -----------------------------------------------------------------
        // Safety notice
        // -----------------------------------------------------------------

        val warningText =
            TextView(
                this
            ).apply {

                text =
                    "Experimental interoperability software. " +
                            "Start and Pause change EVSE state. " +
                            "Use only with equipment you own or are " +
                            "authorized to control."

                textSize =
                    11f

                setPadding(
                    8,
                    16,
                    8,
                    16
                )
            }

        root.addView(
            warningText
        )

        // -----------------------------------------------------------------
        // Log
        // -----------------------------------------------------------------

        val logTitle =
            TextView(
                this
            ).apply {

                text =
                    "Controller Log"

                textSize =
                    17f
            }

        root.addView(
            logTitle
        )

        val logPrivacyNote =
            TextView(
                this
            ).apply {

                text =
                    "Device identifiers, configuration values, " +
                            "and raw protocol frames are not logged."

                textSize =
                    10f
            }

        root.addView(
            logPrivacyNote
        )

        val scrollView =
            ScrollView(
                this
            )

        logText =
            TextView(
                this
            ).apply {

                text =
                    ""

                textSize =
                    12f

                setTextIsSelectable(
                    true
                )

                setPadding(
                    8,
                    16,
                    8,
                    16
                )
            }

        scrollView.addView(
            logText
        )

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(
            root
        )
    }

    // ---------------------------------------------------------------------
    // Device list
    // ---------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun addOrUpdateDevice(
        device: BluetoothDevice,
        rssi: Int
    ) {

        val existingIndex =
            devices
                .indexOfFirst { existing ->

                    existing.address ==
                            device.address
                }

        if (
            existingIndex >= 0
        ) {

            devices[
                existingIndex
            ] =
                device

            deviceLabels[
                existingIndex
            ] =
                buildDeviceLabel(
                    existingIndex,
                    rssi
                )

        } else {

            devices.add(
                device
            )

            deviceLabels.add(
                buildDeviceLabel(
                    devices.lastIndex,
                    rssi
                )
            )
        }

        deviceAdapter
            .notifyDataSetChanged()
    }

    private fun buildDeviceLabel(
        index: Int,
        rssi: Int
    ): String {

        return "BLE device ${index + 1} | RSSI $rssi"
    }

    // ---------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------

    private fun connectSelectedDevice() {

        val controller =
            controllerOrLog()
                ?: return

        val position =
            deviceSpinner
                .selectedItemPosition

        if (
            position < 0 ||
            position >= devices.size
        ) {

            appendLog(
                "No BLE device selected"
            )

            return
        }

        val device =
            devices[
                position
            ]

        appendLog(
            "Connecting to selected BLE device"
        )

        controller.connect(
            device
        )
    }

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    private fun requestBluetoothPermissions() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            val missing =
                mutableListOf<String>()

            if (
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_SCAN
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {

                missing.add(
                    Manifest.permission.BLUETOOTH_SCAN
                )
            }

            if (
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) !=
                PackageManager.PERMISSION_GRANTED
            ) {

                missing.add(
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            }

            if (
                missing.isNotEmpty()
            ) {

                permissionLauncher.launch(
                    missing.toTypedArray()
                )
            }

        } else {

            appendLog(
                "Using legacy Android Bluetooth permission model"
            )
        }
    }

    // ---------------------------------------------------------------------
    // UI logging
    // ---------------------------------------------------------------------

    private fun appendLog(
        message: String
    ) {

        logText.append(
            message
        )

        logText.append(
            "\n"
        )

        logText.post {

            val parent =
                logText.parent

            if (
                parent is ScrollView
            ) {

                parent.fullScroll(
                    ScrollView.FOCUS_DOWN
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------

    override fun onDestroy() {

        bleController
            ?.disconnect()

        bleController =
            null

        super.onDestroy()
    }
}
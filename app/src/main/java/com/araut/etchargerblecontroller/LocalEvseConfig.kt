package com.araut.etchargerblecontroller

/**
 * Provides optional local EVSE configuration for development builds.
 *
 * Values originate from:
 *
 *     evse.local.properties
 *
 * and are exposed to the DEBUG build through BuildConfig.
 *
 * IMPORTANT:
 *
 * - No configuration values are logged here.
 * - Release builds receive empty/default BuildConfig values.
 * - If configuration is unavailable, load() returns null.
 * - The existing manual configuration UI remains the fallback.
 */
object LocalEvseConfig {

    /**
     * Returns local development configuration when valid values
     * are available.
     *
     * Returns null when no local configuration has been supplied.
     */
    fun load():
            BleController.EvseConfig? {

        val serialNumber =
            BuildConfig
                .EVSE_SERIAL_NUMBER
                .trim()

        val userId =
            BuildConfig
                .EVSE_USER_ID

        val handshakeZone =
            BuildConfig
                .EVSE_HANDSHAKE_ZONE

        if (
            serialNumber.isBlank()
        ) {

            return null
        }

        if (
            userId <= 0
        ) {

            return null
        }

        if (
            handshakeZone !in 0..255
        ) {

            return null
        }

        return try {

            BleController.EvseConfig(
                serialNumber =
                    serialNumber,

                userId =
                    userId,

                handshakeZone =
                    handshakeZone
            )

        } catch (
            exception: IllegalArgumentException
        ) {

            /*
             * Do not include configuration values in errors/logs.
             */
            null
        }
    }
}
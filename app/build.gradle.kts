import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// -------------------------------------------------------------------------
// Local ETCharger development configuration
// -------------------------------------------------------------------------
//
// Real EVSE values are stored in:
//
//     <project-root>/evse.local.properties
//
// That file is ignored by Git.
//
// Values are exposed only to DEBUG builds.
//
// Release builds always receive empty/default values so that private
// hardware/account configuration cannot accidentally be embedded in a
// distributable APK.
// -------------------------------------------------------------------------

val evseProperties =
    Properties()

val evsePropertiesFile =
    rootProject.file(
        "evse.local.properties"
    )

if (
    evsePropertiesFile.exists()
) {

    evsePropertiesFile
        .inputStream()
        .use { inputStream ->

            evseProperties.load(
                inputStream
            )
        }
}

val localEvseSerialNumber =
    evseProperties
        .getProperty(
            "EVSE_SERIAL_NUMBER",
            ""
        )
        .trim()

val localEvseUserId =
    evseProperties
        .getProperty(
            "EVSE_USER_ID",
            "0"
        )
        .trim()
        .toLongOrNull()
        ?: 0L

val localEvseHandshakeZone =
    evseProperties
        .getProperty(
            "EVSE_HANDSHAKE_ZONE",
            "4"
        )
        .trim()
        .toIntOrNull()
        ?.takeIf { value ->
            value in 0..255
        }
        ?: 4

/**
 * Escapes text so it can safely be used as a generated Java/Kotlin
 * BuildConfig String literal.
 */
fun buildConfigString(
    value: String
): String {

    val escaped =
        value
            .replace(
                "\\",
                "\\\\"
            )
            .replace(
                "\"",
                "\\\""
            )

    return "\"$escaped\""
}

android {

    namespace =
        "com.araut.etchargerblecontroller"

    compileSdk =
        37

    defaultConfig {

        applicationId =
            "com.araut.etchargerblecontroller"

        minSdk =
            26

        targetSdk =
            35

        versionCode =
            1

        versionName =
            "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        // -------------------------------------------------------------
        // Safe defaults
        // -------------------------------------------------------------
        //
        // Release builds receive these values.
        //
        // LocalEvseConfig.load() will therefore return null and the app
        // will use the manual configuration UI.
        // -------------------------------------------------------------

        buildConfigField(
            "String",
            "EVSE_SERIAL_NUMBER",
            "\"\""
        )

        buildConfigField(
            "long",
            "EVSE_USER_ID",
            "0L"
        )

        buildConfigField(
            "int",
            "EVSE_HANDSHAKE_ZONE",
            "4"
        )
    }

    // -----------------------------------------------------------------
    // Build types
    // -----------------------------------------------------------------

    buildTypes {

        getByName(
            "debug"
        ) {

            /*
             * Only DEBUG builds receive values from
             * evse.local.properties.
             */

            buildConfigField(
                "String",
                "EVSE_SERIAL_NUMBER",
                buildConfigString(
                    localEvseSerialNumber
                )
            )

            buildConfigField(
                "long",
                "EVSE_USER_ID",
                "${localEvseUserId}L"
            )

            buildConfigField(
                "int",
                "EVSE_HANDSHAKE_ZONE",
                localEvseHandshakeZone
                    .toString()
            )
        }

        getByName(
            "release"
        ) {

            /*
             * Deliberately do not load evse.local.properties here.
             *
             * Release builds retain the safe defaults declared in
             * defaultConfig.
             */
        }
    }

    // -----------------------------------------------------------------
    // BuildConfig generation
    // -----------------------------------------------------------------

    buildFeatures {

        buildConfig =
            true
    }

    // -----------------------------------------------------------------
    // Java
    // -----------------------------------------------------------------

    compileOptions {

        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17
    }
}

dependencies {

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.appcompat
    )

    implementation(
        libs.material
    )
}
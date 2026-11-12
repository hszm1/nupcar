import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val targetAndroidSdk = 35
val sampleVersionMajor = 1
val sampleVersionMinor = 2
val sampleVersionPatch = 3
val simulatedVehicle = providers.gradleProperty("nupcar.simulated").orNull == "true"

/**
 * Release signing comes from local.properties, which git ignores, so the key and its
 * password never enter the repository. Without these entries the release build is unsigned.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStoreFile: String? = localProperties.getProperty("nupcar.release.storeFile")

android {
    namespace = "com.arc.nupcar"
    compileSdk = targetAndroidSdk
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.arc.nupcar"
        minSdk = 28
        targetSdk = targetAndroidSdk
        versionCode =
            targetAndroidSdk * 1_000_000 +
                sampleVersionMajor * 10_000 +
                sampleVersionMinor * 100 +
                sampleVersionPatch
        versionName =
            "$sampleVersionMajor.$sampleVersionMinor.$sampleVersionPatch-sdk$targetAndroidSdk"

        buildConfigField(
            "String",
            "VEHICLE_SDK_VERSION",
            "\"${libs.versions.vehicleSdk.get()}\""
        )

        buildConfigField("boolean", "SIMULATED_VEHICLE", simulatedVehicle.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProperties.getProperty("nupcar.release.storePassword")
                keyAlias = localProperties.getProperty("nupcar.release.keyAlias")
                keyPassword = localProperties.getProperty("nupcar.release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.vehicle.sdk)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

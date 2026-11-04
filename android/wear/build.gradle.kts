import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The watch app is signed with the same key as the phone app when android/keystore.properties exists (see app/build.gradle.kts).
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "dev.nanodash.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.levi2m.nanodash"   // the same id as the phone app: they are installed on different devices
        minSdk = 30
        targetSdk = 37
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1   // CI passes its run number, as for the phone app
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
}

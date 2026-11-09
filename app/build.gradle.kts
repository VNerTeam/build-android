plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Khoa ky: CI dua vao bien moi truong (xem .github/workflows/build-apk.yml)
val ksPath: String? = System.getenv("KEYSTORE_FILE")
val hasKeystore = ksPath != null && file(ksPath).exists()

android {
    namespace = "com.lightplayer.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lightplayer.android"
        minSdk = 24
        targetSdk = 34
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0.0"
    }

    if (hasKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Media3 danh dau nhieu API la @UnstableApi; opt-in toan module cho gon
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}

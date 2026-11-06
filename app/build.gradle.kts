plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ernesto.mirastreador"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ernesto.mirastreador"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    // Clave fija: así cada nueva versión se instala encima de la anterior
    // sin tener que desinstalar (y sin perder el historial).
    signingConfigs {
        create("fija") {
            storeFile = file("mirastreador.jks")
            storePassword = "mirastreador"
            keyAlias = "mirastreador"
            keyPassword = "mirastreador"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fija")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}

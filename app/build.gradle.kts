plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tr.hastane.sayim"
    compileSdk = 34

    defaultConfig {
        applicationId = "tr.hastane.sayim"
        minSdk = 24
        targetSdk = 34          // 35 ve üstü kenardan-kenara (edge-to-edge) zorunlu kılar; sayfa buna göre düzenlenmedi
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Mağazaya konmayacak, hastane içinde elle kurulacak APK: debug anahtarıyla imzalanır ki doğrudan kurulabilsin.
            // Kendi anahtarınız varsa burayı değiştirin.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    // Bağlantı QR'ı okuma (CameraX + ML Kit; model APK içinde, internet/Google Play hizmetleri gerekmez)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}

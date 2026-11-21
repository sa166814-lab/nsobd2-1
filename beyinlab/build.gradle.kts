// NS BEYİN LAB — ana NS OBD2 uygulamasından AYRI uygulama.
// Beyin Lab testleri ELM327'nin ayarlarını (header, adres, baud) değiştirdiği için
// ana uygulamanın bağlantısını bozuyordu; bu yüzden ayrı APK olarak derleniyor.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.nsobd2.lab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.nsbeyinlab"
        minSdk = 24
        targetSdk = 34
        val runNo = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNo
        versionName = "1.0.$runNo"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/nsobd2-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
}

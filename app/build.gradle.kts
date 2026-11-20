plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.nsobd2"
    compileSdk = 34 // Hatalı olan release(36) kısmını stabil hale getirdik

    defaultConfig {
        applicationId = "com.example.nsobd2.pro"
        minSdk = 24
        targetSdk = 34 // Uyum sorunu olmaması için 34'e sabitledik
        // GitHub Actions her derlemede numarayi otomatik arttirir
        val runNo = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNo
        versionName = "1.0.$runNo"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Sabit imza: her derleme ayni anahtarla imzalanir, boylece
    // yeni APK eskisinin ustune (verileri silmeden) kurulur.
    signingConfigs {
        getByName("debug") {
            storeFile = file("nsobd2-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Kotlin Coroutines (Arka plan ağ işlemleri için)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ViewModel & LiveData (Verileri ekranda canlı tutmak için)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // İŞTE DÜZELTİLMİŞ KADRAN KÜTÜPHANESİ (Nokta hatası giderildi, sürüm güncellendi)
    implementation("com.github.anastr:speedviewlib:1.6.1")
// GPS / Konum servisleri
    implementation("com.google.android.gms:play-services-location:21.0.1")
}
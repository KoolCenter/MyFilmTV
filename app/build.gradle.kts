plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.myfilmtv"
    compileSdk = 36
    defaultConfig { applicationId = "com.example.myfilmtv"; minSdk = 24; targetSdk = 35; versionCode = 7; versionName = "0.7" }
    signingConfigs {
        create("persistent") {
            storeFile = file(System.getenv("MYFILM_KEYSTORE") ?: "myfilm-release.jks")
            storePassword = System.getenv("MYFILM_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("MYFILM_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("MYFILM_KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        getByName("debug") {
            if(System.getenv("MYFILM_KEYSTORE") != null) signingConfig = signingConfigs.getByName("persistent")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.audiostickerwa"
    compileSdk = 36

    defaultConfig {
        // Change this to your own unique id before you publish.
        applicationId = "com.audiostickerwa"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The authority must start with the applicationId.
        val authority = "$applicationId.stickercontentprovider"
        manifestPlaceholders["contentProviderAuthority"] = authority
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"$authority\"")
    }

    buildTypes {
        release {
            // R8: removes unused code and resources (smaller APK). Keep rules for the Go engine: proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // For side-loading and testing only. Use your own release key before you publish to Google Play.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // Compress the Go engine library in the APK (~19 MB -> ~7 MB download); Android unpacks it at install.
    packaging { jniLibs { useLegacyPackaging = true } }
    // The Go engine is arm64 only (see engine/build.sh).
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // WhatsApp engine (whatsmeow via gomobile). Rebuild with engine/build.sh.
    implementation(files("libs/waengine.aar"))
    testImplementation("junit:junit:4.13.2")
}

// Optional: real Opus packets for OggOpusTest (see test). Run with -Popus.packets=... -Popus.out=...
tasks.withType<Test>().configureEach {
    project.findProperty("opus.packets")?.let { systemProperty("opus.packets", it) }
    project.findProperty("opus.out")?.let { systemProperty("opus.out", it) }
    project.findProperty("webp.dir")?.let { systemProperty("webp.dir", it) }
}

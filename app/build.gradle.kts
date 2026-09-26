import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Engineering decision: the Gemini API key is injected at BUILD TIME from local.properties
// (a git-ignored file) into BuildConfig — no secret is ever hardcoded in version control.
// A runtime fallback (key entered in-app, stored in private app storage) keeps the app fully
// usable even when the build-time key is absent.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY") ?: ""
// Engineering decision: mandated primary model is "gemini-3.5-flash"; the network layer
// keeps an automatic fallback chain to other live Flash models so the app never dies on a 404.
val geminiModel: String = localProperties.getProperty("GEMINI_MODEL") ?: "gemini-3.5-flash"

android {
    namespace = "com.arenaai.duagents"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arenaai.duagents"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GEMINI_MODEL", "\"$geminiModel\"")
    }

    // Engineering decision: release builds are signed with the debug key and left unminified
    // so the produced APK installs and runs on any device with zero extra setup.
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") }
    lint { abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Engineering decision: icons-extended pulled in for Mic/Build/Stop glyphs; build time cost
    // accepted in exchange for a single consistent icon source.
    implementation("androidx.compose.material:material-icons-extended")
    // Real network layer — mandated OkHttpClient.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Engineering decision: the real org.json implementation for JVM unit tests — the Android
    // Gradle plugin ships only stubbed org.json classes in unit tests, and the live-network
    // integration test exercises the REAL org.json parsing path used in production.
    testImplementation("org.json:json:20240303")
}

import java.util.Properties

plugins {
    id("com.android.application")
}

val versionFile = file("../version.json")
var vCode = 1
var vName = "1.0.0"
if (versionFile.exists()) {
    val text = versionFile.readText()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("\"versionCode\":")) {
            vCode = trimmed.replace("\"versionCode\":", "").replace(",", "").trim().toIntOrNull() ?: 1
        } else if (trimmed.startsWith("\"versionName\":")) {
            vName = trimmed.replace("\"versionName\":", "").replace("\"", "").replace(",", "").trim()
        }
    }
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { stream ->
            load(stream)
        }
    }
}
val defaultBaseUrl = localProps.getProperty("codex.default.baseurl", "http://127.0.0.1:8317/v1")
val overrideApinexKey = localProps.getProperty("codex.override.apinex.key", "")
val overrideBaiKey = localProps.getProperty("codex.override.bai.key", "")
val overrideCodexLocalKey = localProps.getProperty("codex.override.codex.local.key", "")
val overrideE2bKey = localProps.getProperty("codex.override.e2b.key", "")

android {
    namespace = "com.codex.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.chat"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = vName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_BASE_URL", "\"$defaultBaseUrl\"")
        buildConfigField("String", "OVERRIDE_APINEX_KEY", "\"$overrideApinexKey\"")
        buildConfigField("String", "OVERRIDE_BAI_KEY", "\"$overrideBaiKey\"")
        buildConfigField("String", "OVERRIDE_CODEX_LOCAL_KEY", "\"$overrideCodexLocalKey\"")
        buildConfigField("String", "OVERRIDE_E2B_KEY", "\"$overrideE2bKey\"")
    }

    signingConfigs {
        create("release") {
            val storePath = localProps.getProperty("codex.release.storeFile")
                ?: System.getenv("CODEX_RELEASE_STORE_FILE")
            val storePass = localProps.getProperty("codex.release.storePassword")
                ?: System.getenv("CODEX_RELEASE_STORE_PASSWORD")
            val alias = localProps.getProperty("codex.release.keyAlias")
                ?: System.getenv("CODEX_RELEASE_KEY_ALIAS")
            val keyPass = localProps.getProperty("codex.release.keyPassword")
                ?: System.getenv("CODEX_RELEASE_KEY_PASSWORD")
            if (storePath != null && storePass != null && alias != null && keyPass != null) {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Unit Testing
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("xmlpull:xmlpull:1.1.3.4a")
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    // Android Instrumented Testing on Real Device / Emulator
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

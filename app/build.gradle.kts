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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

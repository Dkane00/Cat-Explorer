plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.kanenet.catexplorer"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "net.kanenet.catexplorer"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
        // the RTL-SDR driver (src/main/cpp) is native code: build it for real tablets and phones, and the x86_64 emulator
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Android calls such as Log.d do nothing in unit tests instead of failing them
    testOptions { unitTests.isReturnDefaultValues = true }
    // the licence and the third-party notices travel inside the app, shown under Settings → About
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legal-assets"))
}

val copyLegal by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD-PARTY-NOTICES.md"))
    into(layout.buildDirectory.dir("generated/legal-assets"))
}
tasks.named("preBuild") { dependsOn(copyLegal) }
tasks.matching { it.name.endsWith("Assets") || it.name.contains("Lint") }.configureEach { if (name != "copyLegal") dependsOn(copyLegal) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.graphics:graphics-path:1.0.1")   // the 16 KB-page-aligned build of a library Compose uses
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

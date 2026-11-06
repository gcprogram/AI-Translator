plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Das Modell auf dem Gerät braucht llama.cpp (scripts/fetch-llama-cpp.sh). Ohne den Quellordner, oder mit -PnoNative, entsteht eine
// App ohne native Bibliothek: alles andere läuft, nur dieses Backend meldet dann, dass es fehlt.
val buildNative = file("../third_party/llama.cpp/CMakeLists.txt").exists() && !project.hasProperty("noNative")

android {
    namespace = "app.aitranslator"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.aitranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Für schnelle Entwicklungsläufe nur die Handy-Architektur bauen: ./gradlew assembleDebug -Pabis=arm64-v8a
            abiFilters += ((project.findProperty("abis") as String?) ?: "arm64-v8a,x86_64").split(',')
        }
    }
    if (buildNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // Dieselben Tests laufen zusätzlich auf dem Gerät: Androids Regex-, XML- und ZIP-Implementierungen
    // unterscheiden sich von der JVM, das sieht ein reiner JVM-Test nicht.
    sourceSets.getByName("androidTest").java.srcDir("src/test/kotlin")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.19.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

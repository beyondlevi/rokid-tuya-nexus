plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.beyondlevi.nexus.plugin.tuya"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.beyondlevi.nexus.plugin.tuya"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
}

dependencies {
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:sdk-v0.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Android ships org.json; the JVM unit-test JVM does not.
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// The live account test reads the Tuya credentials from the environment; Gradle
// does not forward them to the test JVM on its own.
tasks.withType<Test>().configureEach {
    listOf("TUYA_ACCESS_ID", "TUYA_ACCESS_SECRET", "TUYA_REGION", "TUYA_UID").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    testLogging { showStandardStreams = false }
}

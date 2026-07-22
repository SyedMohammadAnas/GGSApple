import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun localProp(name: String, default: String = ""): String =
    localProperties.getProperty(name)?.trim().orEmpty().ifEmpty { default }

android {
    namespace = "com.cgsapple.remotear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cgsapple.remotear"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SUPABASE_URL", "\"${localProp("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProp("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "LIVEKIT_URL", "\"${localProp("LIVEKIT_URL")}\"")
        buildConfigField("String", "API_URL", "\"${localProp("API_URL")}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "ANNOTATION_DEBUG_OVERLAY", "false")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ANNOTATION_DEBUG_OVERLAY", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    flavorDimensions += "tier"
    productFlavors {
        create("master") {
            dimension = "tier"
            buildConfigField("boolean", "IS_PREMIUM", "true")
        }
        create("instant") {
            dimension = "tier"
            applicationIdSuffix = ".instant"
            buildConfigField("boolean", "IS_PREMIUM", "false")
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
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Compress .so files so 16 KB page-size ELF zip-alignment warnings/failures
        // from third-party ARCore/Filament/LiveKit natives do not block debug installs
        // on Android 15/16 devices that advertise 16 KB page support.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // ARCore (keep reasonably current for OEM + 16 KB packaging fixes)
    implementation("com.google.ar:core:1.48.0")

    // SceneView (ARCore + Filament rendering)
    implementation("io.github.sceneview:arsceneview:2.2.1")

    // LiveKit Android SDK v2
    implementation("io.livekit:livekit-android:2.9.0")
    implementation("io.livekit:livekit-android-camerax:2.9.0")

    // Supabase Kotlin SDK
    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("io.ktor:ktor-client-websockets:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Serialisation
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Local preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}

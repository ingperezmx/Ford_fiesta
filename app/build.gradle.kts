plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.x.carlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.x.carlauncher"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Firma de depuracion fijada: el keystore de depuracion vive en
    // <workspace>/tools/debug.keystore (fuera del repo y persistente entre
    // reemplazos de la VM). Asi la huella SHA-1 del APK no cambia y el
    // cliente OAuth de Android registrado en Google Cloud sigue valiendo.
    // Si el archivo no existe (por ejemplo en GitHub Actions), se usa la
    // configuracion de depuracion normal.
    val pinnedDebugKeystore = rootProject.file("../tools/debug.keystore")
    val releaseSigning = if (pinnedDebugKeystore.exists()) {
        signingConfigs.create("pinnedDebug") {
            storeFile = pinnedDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("com.google.maps.android:maps-compose:4.3.0")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    // Ubicación real de Google (FusedLocationProvider): GPS del teléfono/pantalla,
    // sin ubicaciones simuladas. Es la fuente principal de VehicleLocationManager.
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // Compose BOM — manages all Compose library versions
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Lifecycle / ViewModel for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Maps — OpenStreetMap
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // Networking — Retrofit + Gson converter
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Material XML components (for native dialogs and themes)
    implementation("com.google.android.material:material:1.11.0")

    // Accompanist permissions
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Preferences DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Media3 ExoPlayer for rock-solid Live Radio Streaming
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Debug tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

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
        // versionCode sube en cada entrega: Android solo actualiza la app
        // instalada si el APK nuevo trae un versionCode mayor o igual.
        // (Antes siempre era 1, y con cada cambio de firma habia que
        // desinstalar; con la clave de lanzamiento fijada y este numero
        // subiendo, las proximas actualizaciones se instalan encima.)
        versionCode = 10
        versionName = "1.0.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Firma de lanzamiento propia: el APK se firmaba con la clave de
    // depuracion de Android (una clave publica y conocida), y Play Protect
    // lo marcaba como app insegura y bloqueaba permisos especiales.
    // Ahora se firma con un keystore de lanzamiento propio y unico que
    // vive en <workspace>/tools/release.keystore (fuera del repo y
    // persistente entre reemplazos de la VM), con su clave en
    // <workspace>/tools/release-keystore.properties. Asi la huella SHA-1
    // del APK es estable y propia. Si esos archivos no existen (por
    // ejemplo en GitHub Actions), se usa la clave de depuracion fijada
    // y en ultimo caso la de depuracion normal.
    // (En Kotlin DSL no se puede usar java.util.Properties: "java" es el
    // accesorio de la extension de AGP; se lee el archivo a mano.)
    val releaseKeystorePropsFile = rootProject.file("../../tools/release-keystore.properties")
    val releaseKeystoreProps: Map<String, String> = if (releaseKeystorePropsFile.exists()) {
        releaseKeystorePropsFile.readLines()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()
    } else {
        emptyMap()
    }
    val releaseKeystoreFile = rootProject.file("../../tools/release.keystore")
    val pinnedDebugKeystore = rootProject.file("../../tools/debug.keystore")
    val releaseSigning = when {
        releaseKeystoreFile.exists() && releaseKeystoreProps["storePassword"] != null -> {
            signingConfigs.create("releaseKey") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystoreProps["storePassword"]
                keyAlias = releaseKeystoreProps["keyAlias"] ?: "caros"
                keyPassword = releaseKeystoreProps["keyPassword"] ?: releaseKeystoreProps["storePassword"]
            }
        }
        pinnedDebugKeystore.exists() -> {
            logger.warn("ADVERTENCIA: no se encontro el keystore de lanzamiento; el APK se firmara con la clave de depuracion y NO se podra actualizar encima de la version de lanzamiento. Restaura <workspace>/tools/release.keystore.")
            signingConfigs.create("pinnedDebug") {
                storeFile = pinnedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        else -> {
            logger.warn("ADVERTENCIA: no se encontro ningun keystore fijado; el APK se firmara con la clave de depuracion por defecto y NO se podra actualizar encima de la version de lanzamiento.")
            signingConfigs.getByName("debug")
        }
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

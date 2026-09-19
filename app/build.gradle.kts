plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.htn.breadboardar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.htn.breadboardar"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.ar:core:1.33.0")
    // Filament publishes Android releases as AARs on its official GitHub release
    // page, rather than the Maven artifacts that older setup guides reference.
    // Bundling the matched 1.71.4 set keeps debug APK builds reproducible. This is
    // the newest tested release whose AAR metadata supports this project's API-35
    // compile SDK.
    implementation(files("libs/filament-v1.71.4-android.aar"))
    implementation(files("libs/gltfio-v1.71.4-android.aar"))
    implementation(files("libs/filament-utils-v1.71.4-android.aar"))
    // ModelViewer in filament-utils uses coroutines for optional external glTF
    // resources. The supplied GLB is self-contained, but the runtime class still
    // needs this lightweight Android dependency present.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.application")
}

val unityAvailable = findProject(":unityLibrary") != null

android {
    namespace = "com.htn.breadboardar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.htn.breadboardar"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional HTTPS service origin. App Settings can override it; no provider secrets.
        val apiUrl = providers.gradleProperty("circuitApiUrl").orElse("").get()
        require(apiUrl.isEmpty() || apiUrl.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?"))) { "circuitApiUrl must be an HTTPS origin" }
        buildConfigField("String", "CIRCUIT_API_URL", "\"$apiUrl\"")
        buildConfigField("boolean", "UNITY_AVAILABLE", unityAvailable.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets.getByName("test").resources.srcDir("src/main/assets")

    sourceSets.getByName("main") {
        if (unityAvailable) {
            java.srcDir("src/unity/java")
            manifest.srcFile("src/unity/AndroidManifest.xml")
        }
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

    packaging { jniLibs { useLegacyPackaging = true } }

    androidResources {
        noCompress += listOf("unity3d", "ress", "resource", "obb", "bundle", "unityexp")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    if (unityAvailable) {
        implementation(project(":unityLibrary"))
        // Unity's generated module uses implementation for its JAR, but our Activity subclasses its public types.
        compileOnly(files("../unity-export/unityLibrary/libs/unity-classes.jar"))
    }
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}


// Fail explicitly rather than shipping a release whose AR button cannot work.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst { check(unityAvailable) { "Export Unity first: scripts/export-unity.sh. Debug builds can preview the designer only." } }
}

import java.util.Properties

plugins {
    id("com.android.application") version "9.0.0" apply false
    id("com.android.library") version "9.0.0" apply false
}

val unityProperties = Properties()
file("unity-export/unity-host.properties").takeIf { it.isFile }?.inputStream()?.use(unityProperties::load)
subprojects {
    unityProperties.forEach { key, value -> extensions.extraProperties.set(key.toString(), value.toString()) }
    if (!extensions.extraProperties.has("unityStreamingAssets")) extensions.extraProperties.set("unityStreamingAssets", "")
}

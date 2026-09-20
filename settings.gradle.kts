pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        flatDir { dirs("unity-export/unityLibrary/libs") }
    }
}

rootProject.name = "Circuit"
include(":app")


if (file("unity-export/unityLibrary/build.gradle").isFile) {
    include(":unityLibrary")
    project(":unityLibrary").projectDir = file("unity-export/unityLibrary")
    file("unity-export/unityLibrary").listFiles()?.filter { it.isDirectory && it.extension == "androidlib" }?.forEach {
        include(":unityLibrary:${it.name}")
        project(":unityLibrary:${it.name}").projectDir = it
    }
}

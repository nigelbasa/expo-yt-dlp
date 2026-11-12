// The Kotlin library (io.github.nigelbasa:ytdlp-android). It builds the same sources, native
// libraries and assets as the Expo module, from ../android/src/main, minus the Expo layer.
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "ytdlp-android"

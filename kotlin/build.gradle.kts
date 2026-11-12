import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
  id("com.android.library") version "8.7.3"
  id("org.jetbrains.kotlin.android") version "2.1.0"
  id("com.vanniktech.maven.publish") version "0.30.0"
}

// One version for both packages: the npm package's.
val packageJson = groovy.json.JsonSlurper().parse(file("../package.json")) as Map<*, *>
group = "io.github.nigelbasa"
version = packageJson["version"] as String

val shared = file("../android/src/main")

android {
  namespace = "io.github.nigelbasa.ytdlp"
  compileSdk = 35

  defaultConfig {
    minSdk = 24 // the Python runtime's minimum
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // The local test server for the instrumented tests (10.0.2.2 is the host from an emulator).
    testInstrumentationRunnerArguments["testServer"] = "http://10.0.2.2:8765"
  }

  sourceSets["main"].apply {
    java.srcDir(shared.resolve("java"))
    jniLibs.srcDir(shared.resolve("jniLibs"))
    assets.srcDir(shared.resolve("assets"))
  }

  // The test APK runs the binaries from nativeLibraryDir too, so they must be extracted.
  packaging {
    jniLibs { useLegacyPackaging = true }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

  androidTestImplementation("androidx.test:runner:1.6.2")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

mavenPublishing {
  configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = false))
  publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
  signAllPublications()
  coordinates("io.github.nigelbasa", "ytdlp-android", version.toString())

  pom {
    name.set("ytdlp-android")
    description.set("yt-dlp for Android apps, with its own Python runtime, a long-running engine and signed over-the-air yt-dlp updates.")
    url.set("https://github.com/nigelbasa/expo-yt-dlp")
    licenses {
      license {
        name.set("MIT")
        url.set("https://github.com/nigelbasa/expo-yt-dlp/blob/main/LICENSE")
      }
    }
    developers {
      developer {
        id.set("nigelbasa")
        name.set("Nigel Basarokwe")
        url.set("https://github.com/nigelbasa")
      }
    }
    scm {
      url.set("https://github.com/nigelbasa/expo-yt-dlp")
      connection.set("scm:git:https://github.com/nigelbasa/expo-yt-dlp.git")
      developerConnection.set("scm:git:ssh://git@github.com/nigelbasa/expo-yt-dlp.git")
    }
  }
}

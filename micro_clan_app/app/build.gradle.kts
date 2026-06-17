plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "be.heyman.android.etymoclan"
    compileSdk = 36
    defaultConfig {
        applicationId = "be.heyman.android.etymoclan"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  
  // LiteRT-LM pour le modèle Gemma Local et le tool-calling natif
  implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
  
  // Jsoup pour le scraping/parsing de pages web offline
  implementation("org.jsoup:jsoup:1.18.1")

  // OCR Latin standard
  implementation("com.google.mlkit:text-recognition:16.0.1")
  // OCR Japonais (support des Kanji, Hiragana, Katakana et alphabet latin)
  implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
  // OCR Chinois (support des Kanjis chinois et alphabet latin)
  implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
  // OCR Coréen
  implementation("com.google.mlkit:text-recognition-korean:16.0.1")
  // OCR Devanagari
  implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
  // Identification automatique de la langue pour aiguiller l'OCR
  implementation("com.google.mlkit:language-id:17.0.6")

  // MediaPipe Image Generator pour la génération d'images on-device (déprécié mais inclus pour comparaison)
  implementation("com.google.mediapipe:tasks-vision-image-generator:0.10.14")
}

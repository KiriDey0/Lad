// SPDX-License-Identifier: AGPL-3.0-only
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 useLibrary("android.test.runner")
 useLibrary("android.test.base")
 useLibrary("android.test.mock")
 namespace = "com.lad.music"
 compileSdk = 35
 defaultConfig { applicationId = "com.lad.music.tv"; minSdk = 28; targetSdk = 35; versionCode = 4; versionName = "0.1.3-tv"; testInstrumentationRunner = "android.test.InstrumentationTestRunner"; ndk { abiFilters += "armeabi-v7a" } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { buildConfig = true }
 buildTypes { debug { applicationIdSuffix = ".qa" }; release { isMinifyEnabled = true; isShrinkResources = true; signingConfig = signingConfigs.getByName("debug"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
 testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
 implementation("androidx.core:core-ktx:1.13.1")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("io.coil-kt:coil:2.7.0")
 implementation("org.jmdns:jmdns:3.6.4")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
 testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

plugins { id("com.android.application"); kotlin("android") }
android {
 namespace = "still.app"
 compileSdk = 35
 defaultConfig { applicationId = "org.still.reader"; minSdk = 28; targetSdk = 35; versionCode = 5; versionName = "0.1.4" }
 compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
dependencies {
 coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
 implementation(project(":core"))
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("androidx.fragment:fragment-ktx:1.8.5")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("androidx.work:work-runtime-ktx:2.10.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.jsoup:jsoup:1.18.3")
 implementation("org.readium.kotlin-toolkit:readium-shared:3.0.3")
 implementation("org.readium.kotlin-toolkit:readium-streamer:3.0.3")
 implementation("org.readium.kotlin-toolkit:readium-navigator:3.0.3")
 implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}

plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.jsoup:jsoup:1.18.3")
 implementation("org.json:json:20240303")
 testImplementation(kotlin("test"))
 testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test { testLogging { events("passed", "failed", "skipped"); showStandardStreams = true } }

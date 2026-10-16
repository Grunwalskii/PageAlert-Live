import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Signing credentials live outside the public checkout. A different private
// properties file can be selected with -PpagealertSigningProperties=/path/to/file.
val uploadSigningFile = providers.gradleProperty("pagealertSigningProperties")
    .map { rootProject.file(it) }
    .getOrElse(File(System.getProperty("user.home"), ".android/pagealertlive/signing.properties"))
val uploadSigning = Properties().apply {
    if (uploadSigningFile.isFile) uploadSigningFile.inputStream().use { load(it) }
}
val hasUploadSigning = uploadSigningFile.isFile
fun uploadSigningValue(name: String): String = requireNotNull(uploadSigning.getProperty(name)?.takeIf { it.isNotBlank() }) {
    "Missing '$name' in the private upload signing configuration."
}

android {
    namespace = "com.clinscout.pagealertlive"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.clinscout.pagealertlive"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.0.13-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = rootProject.file(uploadSigningValue("storeFile"))
                storePassword = uploadSigningValue("storePassword")
                keyAlias = uploadSigningValue("keyAlias")
                keyPassword = uploadSigningValue("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasUploadSigning) signingConfig = signingConfigs.getByName("upload")
        }
    }
}

val requireUploadSigning by tasks.registering {
    doLast {
        check(hasUploadSigning) {
            "Release builds require a private upload signing configuration. See artifacts/PLAY_STORE.md."
        }
    }
}
tasks.configureEach {
    if (name in setOf("packageReleaseBundle", "packageRelease", "bundleRelease", "assembleRelease")) {
        dependsOn(requireUploadSigning)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

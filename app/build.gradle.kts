import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val publicAssets = file("src/publicAssets")
val publicResources = publicAssets.resolve("res")
val localResources = file("src/main/res")
val resourceOverrides = fileTree(publicResources).files.map { it.relativeTo(publicResources) }
val localOverrideCount = resourceOverrides.count { localResources.resolve(it).isFile }
val previewNumber = providers.gradleProperty("catscoPreviewNumber").orElse("17").get().toInt()
require(previewNumber in 3..999999999) { "Preview number must be in the supported update range." }
val previewRevision = providers.gradleProperty("catscoPreviewRevision").orNull
if (previewRevision != null) {
    require(previewRevision.matches(Regex("[0-9a-f]{40}"))) { "Use the full Git commit for catscoPreviewRevision." }
    val head = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
    val changes = providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get().trim()
    require(head == previewRevision && changes.isEmpty()) { "Build distributable previews from the specified clean Git commit." }
}
val feedbackOrigin = providers.gradleProperty("catscoFeedbackWorkbenchUrl").orElse("").get().trim().trimEnd('/')
if (feedbackOrigin.isNotEmpty()) {
    val origin = URI(feedbackOrigin)
    require(origin.host != null && origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null && origin.rawPath.isEmpty() &&
        (origin.scheme == "https" || (origin.scheme == "http" && origin.host in setOf("127.0.0.1", "localhost", "[::1]")))) {
        "catscoFeedbackWorkbenchUrl must be an HTTPS origin or a local loopback HTTP origin for debug builds."
    }
}
check(localOverrideCount == 0 || localOverrideCount == resourceOverrides.size) {
    "Only part of the local artwork is present. Restore every local asset or use a clean checkout for the public artwork."
}

android {
    namespace = "cc.catsco.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "cc.catsco.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FEEDBACK_ORIGIN", "\"${feedbackOrigin.takeIf { it.startsWith("https://") }.orEmpty()}\"")
    }
    buildTypes {
        debug {
            manifestPlaceholders["previewRevision"] = previewRevision ?: "local"
            buildConfigField("String", "FEEDBACK_ORIGIN", "\"$feedbackOrigin\"")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-preview.$previewNumber"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    if (localOverrideCount == 0) {
        sourceSets.getByName("main") {
            res.srcDir(publicResources)
            assets.srcDir(publicAssets.resolve("assets"))
        }
    }
    if (previewRevision != null) {
        sourceSets.getByName("debug") {
            manifest.srcFile("src/previewDistribution/AndroidManifest.xml")
            java.setSrcDirs(listOf("src/release/java"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.outputs.forEach { it.versionCode.set(previewNumber) }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.exif)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines)
    implementation(libs.okhttp)
    implementation(libs.markwon.core)
    implementation(libs.markwon.tables)
    implementation(libs.haze)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    if (previewRevision == null) {
        debugImplementation(libs.compose.tooling)
        debugImplementation(libs.compose.test.manifest)
    }
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit)
}

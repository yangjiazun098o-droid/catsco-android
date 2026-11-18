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
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
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
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit)
}

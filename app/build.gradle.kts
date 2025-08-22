plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
// downloadModel:
// - If `download_models.sh` is present, mark it executable and run it.
// - If not present, task is skipped (onlyIf).
tasks.register<Exec>("downloadModel") {
    description = "Execute model download script (download_models.sh) if it exists."
    group = "setup"

    // Skip the task when the script is absent — avoids failure in CI if you don't include the script.
    onlyIf {
        val script = file("download_models.sh")
        if (!script.exists()) {
            logger.warn("⚠️  download_models.sh not found; skipping model download.")
            false
        } else {
            true
        }
    }

    // Ensure the script has execute permission immediately before running.
    doFirst {
        val script = file("download_models.sh")
        if (!script.canExecute()) {
            logger.lifecycle("🔧 Giving execute permission to download_models.sh")
            script.setExecutable(true)
        }
    }

    // Exec task runs the script using bash; change if you want a different shell.
    commandLine("bash", "./download_models.sh")
}

// Make sure preBuild depends on our setup tasks so they run automatically before building.
tasks.named("preBuild") {
    dependsOn("downloadModel")
}

android {
    namespace = "com.negi.slm_chat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.negi.slm_chat"
        minSdk = 31
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.compose.navigation)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.material3)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.material.icons.extended)

    implementation(libs.mediapipe.tasks.text)
    implementation(libs.mediapipe.tasks.genai)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0+ provides Kotlin support built-in; explicit kotlin.android is no
    // longer needed (and is rejected when android.builtInKotlin=true, which is
    // the default in AGP 9). compose/serialization/ksp still apply explicitly.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Bundled dev Brave API key for internal builds. The file is gitignored; engineers
// drop their own dev key in `secrets.properties` at the project root. Production
// builds leave the key empty and require BYOK in Settings.
val secretsFile = rootProject.file("secrets.properties")
val devBraveKey: String = if (secretsFile.exists()) {
    Properties().apply { secretsFile.inputStream().use { load(it) } }
        .getProperty("BRAVE_DEV_KEY", "")
} else ""

// Toggles the InferenceEngine binding between StubInferenceEngine and the real
// LiteRT-LM-backed implementation (see InferenceModule). Override from the
// command line: `./gradlew :androidApp:assembleDebug -PuseStubEngine=true`.
val useStubEngine: String = (project.findProperty("useStubEngine") as String? ?: "false")

android {
    namespace = "com.contextsolutions.mobileagent.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.contextsolutions.mobileagent"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-m0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Pixel 7 is ARM64-only for our purposes; we don't need 32-bit splits.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            buildConfigField("String", "BRAVE_DEV_KEY", "\"$devBraveKey\"")
            buildConfigField("boolean", "INTERNAL_BUILD", "true")
            buildConfigField("boolean", "USE_STUB_ENGINE", useStubEngine)
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Production builds never bundle a key — BYOK only.
            buildConfigField("String", "BRAVE_DEV_KEY", "\"\"")
            buildConfigField("boolean", "INTERNAL_BUILD", "false")
            // Production never uses the stub.
            buildConfigField("boolean", "USE_STUB_ENGINE", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.work.runtime.ktx)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.navigation)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

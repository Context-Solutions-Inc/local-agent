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

// Bundled dev Brave API key for internal builds. The file lives next to
// android-app/settings.gradle.kts — i.e. `android-app/secrets.properties`,
// not the repo root — because that's what Gradle's `rootProject.file(...)`
// resolves to (the Gradle root project is android-app/, where settings.gradle.kts
// lives). It's gitignored. See android-app/secrets.properties.example for the
// expected fields. Production builds leave the Brave key empty and require BYOK
// in Settings.
val secretsFile = rootProject.file("secrets.properties")
val secrets: Properties = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}
val devBraveKey: String = secrets.getProperty("BRAVE_DEV_KEY", "")

// Gemma 4 download spec. URL is the HuggingFace direct-download form (see
// secrets.properties.example). SHA-256 + size are the checksum-pinned values
// committed alongside the URL; the worker fail-fast verifies both. HF gates the
// repo, so HF_AUTH_TOKEN is forwarded as a Bearer Authorization header on
// debug builds only — production must resolve the hosting story (PHASE1_PLAN
// risk row "checksum-pinned URL under our control") before launch.
val modelDownloadUrl: String = secrets.getProperty(
    "MODEL_DOWNLOAD_URL",
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
)
val modelSha256: String = secrets.getProperty("MODEL_SHA256", "")
val modelSizeBytes: String = secrets.getProperty("MODEL_SIZE_BYTES", "0")
val hfAuthToken: String = secrets.getProperty("HF_AUTH_TOKEN", "")

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
            buildConfigField("String", "MODEL_DOWNLOAD_URL", "\"$modelDownloadUrl\"")
            buildConfigField("String", "MODEL_SHA256", "\"$modelSha256\"")
            buildConfigField("long", "MODEL_SIZE_BYTES", "${modelSizeBytes}L")
            buildConfigField("String", "HF_AUTH_TOKEN", "\"$hfAuthToken\"")
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
            // The URL/SHA/size go to release too — the model artifact is public-
            // ish (gated, but fine for an installed app to fetch). HF token does
            // NOT go to release; production must use a hosting path that doesn't
            // require auth, or BYO-token UX (TBD before launch).
            buildConfigField("String", "MODEL_DOWNLOAD_URL", "\"$modelDownloadUrl\"")
            buildConfigField("String", "MODEL_SHA256", "\"$modelSha256\"")
            buildConfigField("long", "MODEL_SIZE_BYTES", "${modelSizeBytes}L")
            buildConfigField("String", "HF_AUTH_TOKEN", "\"\"")
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

    // OkHttp for the model download Worker (Range-resume, streaming SHA-256). Ktor
    // already brings okhttp transitively via :shared, but we want a direct
    // OkHttpClient surface here without going through Ktor's HttpClient.
    implementation(libs.okhttp)

    // SQLDelight Android driver — the schema lives in :shared but the driver is
    // platform-specific and constructed here in DatabaseModule alongside the rest
    // of the Android wiring.
    implementation(libs.sqldelight.android.driver)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    // JDBC in-memory SQLite for unit-testing :shared's SQLDelight schema
    // without an Android Context.
    testImplementation(libs.sqldelight.sqlite.driver)
    // kotlinx-datetime for tests that build deterministic LocalDateTime fixtures
    // (PromptAssembler temporal block coverage). :shared brings it transitively
    // for production code; tests need it on their compile classpath explicitly.
    testImplementation(libs.kotlinx.datetime)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
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
    // M6 Phase C — reads androidApp/google-services.json and generates the
    // FirebaseApp initializer + resources for FirebaseAnalytics. The .json
    // file is gitignored via the root .gitignore "**/google-services.json"
    // rule (see CLAUDE.md "Secrets" section).
    alias(libs.plugins.google.services)
    // M6 Phase D — uploads symbol mappings to Crashlytics for release builds
    // and wires the SDK's auto-collection of native + JVM crashes. Debug
    // builds skip the mappingFileUploadEnabled step automatically.
    alias(libs.plugins.firebase.crashlytics)
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

    // M4 / WS-8 — JVM unit tests for the tokenizer pull vocab.txt +
    // preflight_config.json + tokenizer_canonical_inputs.json onto the test
    // classpath via a small generated resources dir. We deliberately don't
    // expose all of src/main/assets to the test source set because that
    // pulls the 67 MB .tflite into test resource processing for every
    // test run.
    sourceSets {
        getByName("test") {
            resources.directories.add(
                layout.buildDirectory.dir("generated/classifierTestResources").get().asFile.absolutePath,
            )
        }
    }
}

// Stage just the small text fixtures needed by JVM unit tests into a
// dedicated build dir. Binds in via the test source set above.
val collectClassifierTestResources = tasks.register<Copy>("collectClassifierTestResources") {
    description = "Stage vocab.txt + preflight_config.json + tokenizer fixtures for JVM tests."
    from(rootDir.resolve("androidApp/src/main/assets/vocab.txt"))
    from(rootDir.resolve("androidApp/src/main/assets/preflight_config.json"))
    from(rootDir.resolve("../classifier-training/tests/fixtures/tokenizer_canonical_inputs.json"))
    // M5 — MiniLM tokenizer fixture for the embedder. Same WordPiece vocab
    // as DistilBERT (bert-base-uncased), so the existing tokenizer should
    // produce byte-exact results for these strings too.
    from(rootDir.resolve("../classifier-training/tests/fixtures/minilm_tokenizer_canonical_inputs.json"))
    // M5 — embedder canonical reference vectors (used by the on-device
    // EmbedderEndToEndTest, NOT by JVM tests, but harmless to stage here too).
    from(rootDir.resolve("../classifier-training/tests/fixtures/embedder_canonical_outputs.json"))
    into(layout.buildDirectory.dir("generated/classifierTestResources"))
}
tasks.matching { it.name.endsWith("UnitTestJavaRes") || it.name.endsWith("UnitTestSources") }
    .configureEach { dependsOn(collectClassifierTestResources) }

// M4 / WS-8 asset bundling. The INT8 .tflite is gitignored (`models/`) so it's
// not in the source tree by default; this task copies it into the assets dir
// at build time and verifies SHA-256 against the v1.0 ship hash. Failure
// surfaces a pointer to docs/M3_M4_HANDOFF.md so a fresh checkout knows where
// to fetch the artifact from.
val copyClassifierTflite = tasks.register("copyClassifierTflite") {
    description = "Copy + verify the pre-flight classifier .tflite into androidApp assets."
    group = "build"
    val srcFile = rootDir.resolve("../models/preflight_memory_shared_v1.0.0_int8.tflite")
    val dstFile = rootDir.resolve("androidApp/src/main/assets/preflight_memory_shared_v1.0.0_int8.tflite")
    val expectedSha = "5920733f96bfc2f193fdebc7ef5585cd37ecc3b9f23b21259e448410679ea83d"
    inputs.file(srcFile)
    outputs.file(dstFile)
    doLast {
        if (!srcFile.exists()) {
            throw GradleException(
                "Pre-flight classifier artifact missing: ${srcFile.absolutePath}\n" +
                    "Fetch it via the steps in docs/M3_M4_HANDOFF.md §1 (or rebuild from " +
                    "`ct-export-litert` per the same doc) before running this build."
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        srcFile.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
        if (actual != expectedSha) {
            throw GradleException(
                "Pre-flight classifier SHA-256 mismatch.\n" +
                    "  expected: $expectedSha (v1.0 ship)\n" +
                    "  actual:   $actual\n" +
                    "Update build.gradle.kts after a deliberate classifier re-export."
            )
        }
        dstFile.parentFile.mkdirs()
        srcFile.copyTo(dstFile, overwrite = true)
    }
}

// M5 / WS-9 asset bundling. Mirror of copyClassifierTflite for the embedder
// (`all-MiniLM-L6-v2` INT8). Same gitignored-source-of-truth + SHA-verify
// pattern. Note the vocab is NOT bundled separately — MiniLM ships the
// bert-base-uncased WordPiece vocab which is byte-identical to the
// distilbert-base-uncased vocab we already ship at assets/vocab.txt
// (verified via classifier-training/scripts/export_minilm_litert.py).
val copyEmbedderTflite = tasks.register("copyEmbedderTflite") {
    description = "Copy + verify the all-MiniLM-L6-v2 embedder .tflite into androidApp assets."
    group = "build"
    val srcFile = rootDir.resolve("../models/all-MiniLM-L6-v2_int8.tflite")
    val dstFile = rootDir.resolve("androidApp/src/main/assets/all-MiniLM-L6-v2_int8.tflite")
    val expectedSha = "d4320c6f082450d542949ca1067cbc82de4c0c4c4f2ff8915752ff0885c55dcb"
    inputs.file(srcFile)
    outputs.file(dstFile)
    doLast {
        if (!srcFile.exists()) {
            throw GradleException(
                "Embedder artifact missing: ${srcFile.absolutePath}\n" +
                    "Build it via classifier-training/scripts/export_minilm_litert.py " +
                    "(see docs/M5_PLAN.md §9) before running this build."
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        srcFile.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf); if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
        if (actual != expectedSha) {
            throw GradleException(
                "Embedder SHA-256 mismatch.\n" +
                    "  expected: $expectedSha (v1.0 ship)\n" +
                    "  actual:   $actual\n" +
                    "Update build.gradle.kts after a deliberate embedder re-export."
            )
        }
        dstFile.parentFile.mkdirs()
        srcFile.copyTo(dstFile, overwrite = true)
    }
}

androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar { c -> c.titlecase() }
    project.tasks
        .matching { it.name == "merge${variantName}Assets" }
        .configureEach {
            dependsOn(copyClassifierTflite)
            dependsOn(copyEmbedderTflite)
        }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // M6 Phase C — Firebase. BoM aligns transitive firebase-* versions; the
    // analytics-ktx artifact pulls in FirebaseAnalytics + auto-init. Drop
    // additional firebase-* deps here as later phases bring them in
    // (firebase-crashlytics in Phase D).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
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

    // Play Services LiteRT direct API for the M4 Phase A spike + future
    // classifier instrumentation tests in :androidApp/src/androidTest/. The
    // production engine lives in :shared/androidMain and consumes the same
    // libraries via implementation() there — surfacing them here only
    // for tests that exercise the API surface directly without going through
    // the ClassifierEngine seam.
    androidTestImplementation(libs.play.services.tflite.java)
    androidTestImplementation(libs.play.services.tflite.gpu)
    androidTestImplementation(libs.play.services.tflite.support)
    androidTestImplementation(libs.ai.edge.litert)
}

// Same rationale as :shared — keep litert's bundled `org.tensorflow.lite.*`
// classes as the canonical copy by excluding the transitive tensorflow-lite-api
// pulled in by play-services-tflite-java.
configurations.configureEach {
    exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
}

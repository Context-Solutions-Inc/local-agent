import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0+ provides Kotlin support built-in; explicit kotlin.android is no
    // longer needed (and is rejected when android.builtInKotlin=true, which is
    // the default in AGP 9). compose/serialization still apply explicitly.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // M6 Phase C — reads androidApp/google-services.json and generates the
    // FirebaseApp initializer + resources for FirebaseAnalytics. The .json
    // file is gitignored via the root .gitignore "**/google-services.json"
    // rule (see CLAUDE.md "Secrets" section).
    //
    // PR #10 CI fix — declared `apply false` here and applied conditionally
    // below so the unit-test workflow on GitHub (which does not have access
    // to google-services.json) does not blow up at
    // processDebugGoogleServices. Local development with the file present
    // applies both plugins exactly as before.
    alias(libs.plugins.google.services) apply false
    // M6 Phase D — uploads symbol mappings to Crashlytics for release builds
    // and wires the SDK's auto-collection of native + JVM crashes. Debug
    // builds skip the mappingFileUploadEnabled step automatically.
    alias(libs.plugins.firebase.crashlytics) apply false
}

// Apply the Firebase plugins only when google-services.json is present.
// The file is gitignored (carries per-project Firebase identifiers) and
// absent in CI + on fresh contributor checkouts. Without the conditional,
// `processDebugGoogleServices` fails the build before unit tests run.
//
// Runtime impact when the file is missing: FirebaseApp.initializeApp() is
// never auto-generated, so LocalAgentApplication.onCreate's
// FirebaseAnalytics.getInstance(this) call throws on launch. CI does not
// instantiate the Application (unit tests are pure JVM); developers
// without the file should not install the APK on-device.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
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

// Fail a debug DEVICE build when secrets.properties is missing. The BRAVE_DEV_KEY
// getProperty below has a default, so without this guard `assembleDebug`/`installDebug`
// silently produce an app with no bundled dev Brave key — it installs but can't search
// on-device without the user supplying their own key. (Models now download from the
// public CDN with no secrets, PR #22.) Scoped to the two debug device tasks (mirrors
// the build-identity banner wiring below): release builds ship empty keys by design
// (BYOK), and unit tests / the desktop build / CI never touch this file, so they stay
// green. Gated on the REQUESTED task names so it fails fast at configuration time,
// before the long compile, and only for an actual device build. Fix: copy
// android-app/secrets.properties.example to android-app/secrets.properties and fill it in.
val deviceBuildRequested = gradle.startParameter.taskNames.any {
    val taskName = it.substringAfterLast(':')
    taskName == "assembleDebug" || taskName == "installDebug"
}
if (deviceBuildRequested && !secretsFile.exists()) {
    throw GradleException(
        """

        android-app/secrets.properties was not found at:
          ${secretsFile.absolutePath}

        It is required for a debug device build (assembleDebug/installDebug) — it bundles
        the dev Brave Search key. Without it the installed app can't search on-device
        unless the user supplies their own Brave key.

        Fix: copy android-app/secrets.properties.example to android-app/secrets.properties
        and fill in the values.

        """.trimIndent(),
    )
}

val secrets: Properties = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}
val devBraveKey: String = secrets.getProperty("BRAVE_DEV_KEY", "")

// PR #22 — the Gemma 4 artifact now downloads from the public R2 CDN with no
// auth (URL + sha256 + size are pinned directly in ModelInventory.SPEC, like the
// aux models in AndroidAuxModels). The old HuggingFace BuildConfig fields
// (MODEL_DOWNLOAD_URL / MODEL_SHA256 / MODEL_SIZE_BYTES / HF_AUTH_TOKEN) and the
// HfAuthTokenProvider plumbing are gone — all models ship from the CDN.

// Release signing. For distribution supply a private keystore via secrets.properties
// (RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD).
// When those are absent we fall back to the standard Android debug keystore so that
// `./gradlew :androidApp:installRelease` works out of the box for on-device testing of the
// minified build. A debug-signed release is for LOCAL VERIFICATION ONLY — never ship it
// (Play requires an upload key + App Signing). When no keystore is available at all (CI,
// fresh checkout that has never run a debug build) the release stays unsigned, exactly as
// before — CI never assembles the APK, so this is just a safety net.
val releaseStoreFile: String = secrets.getProperty("RELEASE_STORE_FILE", "")
val debugKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")
val hasReleaseSigning = releaseStoreFile.isNotEmpty() || debugKeystore.exists()

// Security M5: fail fast on a DISTRIBUTION build with no real keystore. The debug-keystore
// fallback above exists only so `installRelease` works for local on-device verification —
// the distribution artifacts (assembleRelease/bundleRelease, the APK/AAB you would ship)
// must be signed with a real upload key, never the public debug keystore. Without this
// guard `assembleRelease` silently produced a debug-signed "release" (the build is green,
// the artifact is forgeable). Mirrors the #63 debug-secrets guard above: gated on the
// REQUESTED task names so it fails at configuration time, before the long compile, and ONLY
// for an actual distribution build — `installRelease`, unit tests, the desktop build, and CI
// (which never assembles the APK) are unaffected.
val distributionBuildRequested = gradle.startParameter.taskNames.any {
    val taskName = it.substringAfterLast(':')
    taskName == "assembleRelease" || taskName == "bundleRelease"
}
if (distributionBuildRequested && releaseStoreFile.isEmpty()) {
    throw GradleException(
        """

        A distribution release build (assembleRelease/bundleRelease) was requested but
        RELEASE_STORE_FILE is not set in android-app/secrets.properties.

        Distribution artifacts must be signed with a real upload key — never the public
        Android debug keystore (a debug-signed release is forgeable and must not be shipped).

        Fix: set RELEASE_STORE_FILE (+ RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
        RELEASE_KEY_PASSWORD) in android-app/secrets.properties.

        For local on-device verification of the minified build (debug-keystore fallback is
        fine there), use `./gradlew :androidApp:installRelease` instead.

        """.trimIndent(),
    )
}

// Toggles the InferenceEngine binding between StubInferenceEngine and the real
// LiteRT-LM-backed implementation (see InferenceModule). Override from the
// command line: `./gradlew :androidApp:assembleDebug -PuseStubEngine=true`.
val useStubEngine: String = (project.findProperty("useStubEngine") as String? ?: "false")

// Build number == HEAD's committer-date epoch seconds, computed at configuration
// time. This is monotonic AND deterministic per commit: every commit — including
// a squash-merge commit on `main` — has a strictly newer committer date than its
// ancestors, so the code only ever increases as history advances, and rebuilding
// the same commit yields the same code. We previously used `rev-list --count
// HEAD`, but that is NOT monotonic under squash merges: a PR's N branch commits
// (built & installed during dev) collapse into ONE commit on main, so a fresh
// branch can produce a LOWER count than an APK already on the device →
// INSTALL_FAILED_VERSION_DOWNGRADE. Fits Android's versionCode Int cap (~2.1e9)
// until ~2036. `providers.exec` (not the deprecated `project.exec`) keeps this
// configuration-cache compatible. Falls back to 1 outside a git checkout.
val gitCommitTimestamp: Int =
    providers.exec { commandLine("git", "log", "-1", "--format=%ct", "HEAD") }
        .standardOutput.asText.get().trim().toIntOrNull() ?: 1

val appVersionName = "0.2.0"

// Short HEAD SHA with a `-dirty` suffix when the working tree has uncommitted
// edits. versionCode (above) only changes when HEAD changes, so during dev a
// working-tree rebuild keeps the SAME versionCode as the last commit — this
// SHA/dirty marker is what actually distinguishes "the build I just made" from
// the committed one. Surfaced in BuildConfig (on-device About dialog) and
// printed at build time so a stale install is obvious. `--always` falls back to
// the SHA when there are no tags; empty (no git) → "unknown".
val gitDescribe: String =
    providers.exec { commandLine("git", "describe", "--always", "--dirty", "--abbrev=7") }
        .standardOutput.asText.get().trim().ifEmpty { "unknown" }

// Echo the build identity after assembling/installing the debug app so it can be
// compared at a glance against the app's About dialog (tap the brand logo). A
// stale install shows a different versionCode/SHA on the phone than what printed.
val buildIdentityBanner = """
    |────────────────────────────────────────────────────────
    |  Local Agent (debug) build
    |    versionName : $appVersionName
    |    versionCode : $gitCommitTimestamp   <- 'Build' in the About dialog
    |    git         : $gitDescribe
    |────────────────────────────────────────────────────────
""".trimMargin()
val printBuildIdentity = tasks.register("printBuildIdentity") {
    // Capture a plain String local (not the script-level val) so the doLast
    // action stays configuration-cache compatible.
    val banner = buildIdentityBanner
    doLast { println(banner) }
}
tasks.matching { it.name == "assembleDebug" || it.name == "installDebug" }
    .configureEach { finalizedBy(printBuildIdentity) }

android {
    namespace = "com.contextsolutions.localagent.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.contextsolutions.localagent"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = gitCommitTimestamp
        versionName = appVersionName

        // Git SHA + dirty marker; shown in the About dialog so an on-device
        // build can be matched to a working-tree build whose versionCode is
        // unchanged. All variants get it.
        buildConfigField("String", "GIT_DESCRIBE", "\"$gitDescribe\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Pixel 7 is ARM64-only for our purposes; we don't need 32-bit splits.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                if (releaseStoreFile.isNotEmpty()) {
                    storeFile = file(releaseStoreFile)
                    storePassword = secrets.getProperty("RELEASE_STORE_PASSWORD", "")
                    keyAlias = secrets.getProperty("RELEASE_KEY_ALIAS", "")
                    keyPassword = secrets.getProperty("RELEASE_KEY_PASSWORD", "")
                } else {
                    // Local-testing fallback: the standard debug keystore.
                    storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
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
            // Attach the signing config when a keystore is available (see above) so
            // `installRelease` produces an installable APK; otherwise the release is
            // left unsigned, the prior behaviour.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        // PR #48 — native-lib collision introduced by LiteRT-LM 0.12.0. Where
        // 0.10.2 statically linked its LiteRT core into liblitertlm_jni.so (fully
        // isolated from the classifier runtime — invariant #18), 0.12.0 ships the
        // core as a standalone libLiteRt.so + libLiteRtClGlAccelerator.so. Those
        // share a name with the classifier/embedder's com.google.ai.edge.litert
        // :litert builds, so AGP packages only one of each. pickFirst keeps the
        // first — but cross-dependency order is non-deterministic, and litertlm's
        // copy lacks the classifier JNI symbols (UnsatisfiedLinkError on
        // Environment.nativeCreate). The `extractLitertJni` task below feeds
        // litert's copies in as project-local jniLibs, which ALWAYS win pickFirst,
        // so the winner is deterministic and litertlm_jni shares litert's superset
        // libLiteRt.so (#40). Keep this pickFirst — it's what lets the project copy
        // override the two dependency copies instead of failing the merge.
        jniLibs {
            pickFirsts += setOf(
                "**/libLiteRt.so",
                "**/libLiteRtClGlAccelerator.so",
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

    // M7 watchdog PR — let android.* stubs return default values from JVM
    // unit tests instead of throwing "Method X not mocked." This is the
    // canonical Android pattern for keeping fast JVM tests free of
    // Robolectric. `Log.w/e` and friends become no-ops, which is exactly
    // what we want for the watchdog tests (the log calls are observability
    // only). Doesn't affect Android-side tests (androidTest/) or
    // production behaviour.
    testOptions {
        unitTests.isReturnDefaultValues = true
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

// PR #3 — the classifier + embedder .tflite are no longer bundled into the APK.
// They download from the CDN on first run into filesDir/models/ (folded into the
// same first-run download gate as the Gemma LLM); see AndroidAuxModels +
// ModelDownloadWorker. The old copyClassifierTflite/copyEmbedderTflite SHA-verify
// copy tasks and the -PexternalModels strip path were removed with this change.

// --- invariant #40/#18: deterministically resolve the libLiteRt.so collision ---
// litertlm-android (LLM runtime) and com.google.ai.edge.litert (classifier +
// embedder runtime) BOTH ship libLiteRt.so + libLiteRtClGlAccelerator.so under
// the same name, so the pickFirst in `packaging` keeps exactly one of each.
// litertlm's copy LACKS the classifier JNI symbols, so when it wins the
// classifier dies at warmUp with
//   UnsatisfiedLinkError: com.google.ai.edge.litert.Environment.nativeCreate
// and the agent silently falls through to Gemma. The cross-dependency native
// merge order is NOT deterministic (declaration order has no effect, and debug
// vs release picked different winners), which is how moving these deps into
// :shared during the desktop port silently flipped the winner — caught only at
// on-device run. Project-local jniLibs, however, ALWAYS beat dependency-provided
// libs in pickFirst, so extract litert's two natives from its own AAR at build
// time and feed them in as a jniLibs source dir: litert's copies win
// deterministically AND track the resolved litert version (no stale vendored
// binary on a bump — #40's standing hazard). Per #40 litert:2.1.5's libLiteRt.so
// is a superset that also drives litertlm_jni, so one shared copy is correct for
// both runtimes. arm64-v8a only (the single Pixel-7 abiFilter above).
val litertAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false // just litert's own AAR — not its transitive natives
}
dependencies { litertAar(libs.ai.edge.litert) }

val litertJniOutDir = layout.buildDirectory.dir("generated/litertJni")
val extractLitertJni = tasks.register<Copy>("extractLitertJni") {
    from(provider { zipTree(litertAar.singleFile) }) {
        include("jni/arm64-v8a/libLiteRt.so", "jni/arm64-v8a/libLiteRtClGlAccelerator.so")
        eachFile { path = path.removePrefix("jni/") } // jniLibs srcDir wants <abi>/lib.so
        includeEmptyDirs = false
    }
    into(litertJniOutDir)
}

// AGP 9's SourceSet API rejects Provider/TaskProvider instances, so register the
// output as a plain path and wire the task dependency on the jni-merge steps
// explicitly (srcDir(File) doesn't carry a builtBy).
android.sourceSets.getByName("main").jniLibs.srcDir(litertJniOutDir.get().asFile)
tasks.matching {
    it.name.startsWith("merge") &&
        (it.name.endsWith("JniLibFolders") || it.name.endsWith("NativeLibs"))
}.configureEach { dependsOn(extractLitertJni) }

dependencies {
    implementation(project(":shared"))
    // Shared Compose Multiplatform UI (docs/DESKTOP_PORT_PLAN.md Phase 9). Screens
    // migrate into :ui one at a time; the Android shell renders them from here.
    implementation(project(":ui"))

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

    // Markdown + LaTeX rendering in the assistant bubble (PR #50)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.inline.parser)


    // Koin — the app's DI (Hilt fully removed in Phase 3, docs/DESKTOP_PORT_PLAN.md).
    // koin-android adds androidContext()/by inject()/by viewModel(); koin-core arrives
    // transitively via :shared.
    implementation(libs.koin.android)
    // Multiplatform Compose ViewModel integration (koinViewModel()); the artifact :ui
    // commonMain shares as screens migrate (Phase 9).
    implementation(libs.koin.compose.viewmodel)

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
    // M1 — SQLCipher for the at-rest-encrypted SQLDelight DB. net.zetetic's
    // sqlcipher-android implements the AndroidX SupportSQLiteOpenHelper.Factory,
    // fed into AndroidSqliteDriver(...) in AndroidDatabaseFactory.
    implementation(libs.sqlcipher.android)

    // PR #15 — kotlinx-datetime for the TODO management screen (relative
    // date labels, DatePicker → local-tz midnight conversion). :shared
    // uses it but only as an `implementation` dep, so it doesn't leak
    // here transitively.
    implementation(libs.kotlinx.datetime)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    // Ktor MockEngine for offline adapter tests (StockLookupAdapter).
    testImplementation(libs.ktor.client.mock)
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

    // Secure Gateway Android AAR — surfaced here only for RelayCryptoSmokeTest, which
    // exercises the real lazysodium-android crypto on-device (#55). The production path
    // consumes it via :shared/androidMain's implementation() behind the transport seam.
    androidTestImplementation(libs.securegateway.android)
}

// Same rationale as :shared — keep litert's bundled `org.tensorflow.lite.*`
// classes as the canonical copy by excluding the transitive tensorflow-lite-api
// pulled in by play-services-tflite-java.
configurations.configureEach {
    exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
}

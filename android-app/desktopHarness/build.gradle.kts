plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Reuses the desktop (jvm) variant of :shared — the whole agent loop, search,
    // memory, classifier, prompt assembly, and the desktopMain platform impls
    // (LlamaCppInferenceEngine, DesktopHttpEngineFactory, NoOp aux engines).
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    // JDBC SQLite driver so the harness can stand up an in-memory MobileAgentDatabase
    // (proves the SQLDelight JVM driver seam too).
    implementation(libs.sqldelight.sqlite.driver)
}

application {
    mainClass.set("com.contextsolutions.mobileagent.desktop.harness.MainKt")
}

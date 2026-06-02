pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mobile-agent"

include(":shared")
include(":androidApp")
// Headless desktop integration harness — Phase 0 of the desktop port
// (docs/DESKTOP_PORT_PLAN.md). Drives AgentLoop + llama.cpp with no UI/DI.
include(":desktopHarness")
// Phase 1 — shared Compose Multiplatform UI + thin Compose Desktop shell.
include(":ui")
include(":desktopApp")

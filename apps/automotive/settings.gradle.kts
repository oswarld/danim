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
        // Official Pleos Connect SDK repository (public releases, no credentials): Vehicle, TextToSpeech,
        // SpeechToText, NaviHelper and their transitive `security` artifacts.
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupByRegex("ai\\.pleos\\.playground(\\..*)?") }
        }
    }
}

rootProject.name = "danim-automotive"
include(":core")
// The Android module needs a local Android SDK (accepted licences). Pass
// -Pdanim.coreOnly=true to build and test only the pure Kotlin :core module.
if (providers.gradleProperty("danim.coreOnly").orNull != "true") {
    include(":app")
}

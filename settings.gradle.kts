pluginManagement {
    repositories {
        google()
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

rootProject.name = "KinNav"

// :shared holds the relay authorisation engine, used by both the Android client (:family)
// and the standalone server (:relay) so the two cannot drift apart.
include(":shared")
include(":relay")

// Configuring :app and :family requires the Android Gradle plugin and an installed SDK,
// neither of which the relay container image has. Skipping the modules keeps the image free
// of the SDK and keeps the server build honest about only depending on :shared.
if (providers.gradleProperty("relayOnly").orNull != "true") {
    include(":app")
    include(":family")
}

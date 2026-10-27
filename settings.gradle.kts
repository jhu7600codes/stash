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
        // NewPipeExtractor is only published on jitpack
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\..*") } }
    }
}

rootProject.name = "stash"

include(":app")

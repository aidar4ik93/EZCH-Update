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

rootProject.name = "EZCH Update"
include(":app")
include(":launcher")
include(":eva-core", ":eva-llm")
project(":launcher").projectDir = file("launcher/app")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.2.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
        id("com.google.dagger.hilt.android") version "2.59.2"
        id("com.google.devtools.ksp") version "2.3.7"
        id("org.jlleitschuh.gradle.ktlint") version "10.2.1"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OnionShare"
include(":app")

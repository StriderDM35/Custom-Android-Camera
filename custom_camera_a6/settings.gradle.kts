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

rootProject.name = "CustomCameraA6"
include(":app")
include(":opencv")
project(":opencv").projectDir = File(rootDir, "opencv")
// project(":sdk").projectDir = File(rootDir, "OpenCV-android-sdk/sdk/")
include(":sdk")
include(":sdk")
include(":opencv")

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

rootProject.name = "ReceiptBridge"
include(":app")
project(":app").projectDir = file("App/app")
include(":windowsApp")
project(":windowsApp").projectDir = file("WindowsExecutable/ReceiptBridgeDesktop")

// Trigger sync

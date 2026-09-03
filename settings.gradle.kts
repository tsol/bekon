pluginManagement {
        repositories {
            gradlePluginPortal()
            mavenCentral()
            google()
        }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "bekon"
include("wlya-core")
project(":wlya-core").projectDir = file("packages/wlya-core")
include("wlya-tunnel")
project(":wlya-tunnel").projectDir = file("apps/wlya-tunnel")
include("android-client:app")
project(":android-client").projectDir = file("apps/android-gateway")
project(":android-client:app").projectDir = file("apps/android-gateway/app")
include("bekon-call")
project(":bekon-call").projectDir = file("packages/bekon-call")
include("bekon-phone")
project(":bekon-phone").projectDir = file("apps/android-phone")

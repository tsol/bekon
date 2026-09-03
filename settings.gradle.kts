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
include("wlya-desktop")
project(":wlya-desktop").projectDir = file("packages/wlya-desktop")
include("android-client:app")
project(":android-client").projectDir = file("apps/gateway")
project(":android-client:app").projectDir = file("apps/gateway/app")
include("bekon-call")
project(":bekon-call").projectDir = file("packages/bekon-call")
include("bekon-phone")
project(":bekon-phone").projectDir = file("apps/phone")

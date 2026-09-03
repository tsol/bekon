plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
}

android {
    namespace = "pro.potoki.bekon"
    compileSdk = 34

    defaultConfig {
        applicationId = "pro.potoki.bekon"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "1.10-wlya"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    val adaptersRoot = rootProject.file("packages/wlya-adapters")
    val adapterAndroidDirs = adaptersRoot.walkTopDown()
        .filter { it.isDirectory && it.name == "ui-android" }
        .map { it.absolutePath }
        .toList()

    sourceSets {
        getByName("main") {
            kotlin.srcDirs(adapterAndroidDirs)
            kotlin.srcDir(layout.buildDirectory.dir("generated/kotlin"))
        }
    }
}

tasks.named("preBuild") {
    dependsOn(":wlya-core:generateAdapterRegistries")
}

dependencies {
    implementation(project(":wlya-core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Android JavaMail (provides the javax.mail API used by the shared wlya-core EmailAdapter)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}

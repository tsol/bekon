plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":wlya-core"))

    // JavaMail transport (the shared core only declares javax.mail as compileOnly)
    implementation("com.sun.mail:javax.mail:1.6.2")

    // Kotlin coroutines (needed for runBlocking in routes)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Javalin HTTP server
    implementation("io.javalin:javalin:6.6.0")
    implementation("io.javalin.community.openapi:javalin-openapi-plugin:6.6.0")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.wlya.desktop.MainKt")
}

// Use repo root as CWD so `.wlya/` is shared with the Vite UI and jar runs.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.wlya.desktop.MainKt"
    }
}

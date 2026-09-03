plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

apply(from = rootProject.file("gradle/generate-adapters.gradle.kts"))

kotlin {
    jvmToolchain(21)
    sourceSets {
        named("main") {
            kotlin.srcDir(layout.buildDirectory.dir("generated/kotlin"))
            kotlin.srcDir(rootProject.file("packages/wlya-adapters"))
            kotlin.exclude("**/ui-android/**")
            kotlin.exclude("**/ui-vue/**")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // JavaMail (javax) for IMAP/SMTP — compileOnly so the shared core stays free of
    // javax.activation and can be consumed by Android (which provides android-mail).
    compileOnly("com.sun.mail:javax.mail:1.6.2")

    // JavaMail for the JVM unit tests (EmailAdapterTest).
    testImplementation("com.sun.mail:javax.mail:1.6.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}

tasks.test {
    dependsOn("generateAdapterRegistries")
    useJUnitPlatform()
}

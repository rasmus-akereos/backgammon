import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-Kotlin AI module. Like :core, compile with the running JDK (21) but target JVM 17 bytecode
// so it stays compatible with the Android :app module. Depends only on :core; coroutine-free.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-ea") // enable assertions so check()/require() invariants fire under test
    // Forward opt-in gate flags to the forked test JVM (e.g. -Dbackgammon.calibrate=true),
    // which Gradle does NOT propagate by default. Covers the gated benchmark + calibration harnesses.
    for (key in listOf("backgammon.benchmark", "backgammon.calibrate")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

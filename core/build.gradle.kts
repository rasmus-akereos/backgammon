import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Compile with whatever JDK runs Gradle (JDK 21 here) but target JVM 17 bytecode, so the
// engine stays compatible with the later Android modules without needing a separate JDK 17.
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
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-ea") // enable assertions so check()/require() invariants fire under test
}

plugins {
    kotlin("jvm")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.snakeyaml:snakeyaml-engine:2.8")
    testImplementation(kotlin("test"))
}

// Assumption: a library for the services to come; JVM 17 bytecode runs on JDK 21 and 25 (ADR-0008..0015 target 25).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

tasks.test {
    useJUnitPlatform()
    // The hub profiles are never copied here: contracts.lock pins an EV_PNC_hub-contracts commit, `make contracts`
    // fetches it into .contracts/, or point EV_PNC_CONTRACTS_DIR at any checkout.
    environment("EV_PNC_CONTRACTS_DIR", System.getenv("EV_PNC_CONTRACTS_DIR") ?: rootProject.file(".contracts").absolutePath)
}

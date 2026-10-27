plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":hub-core"))
    implementation("org.yaml:snakeyaml:2.4")
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":hub-core")))
}

// The pinned OICP contract lives in EV_PNC_hub-contracts. Tests that compare against it read it from CONTRACTS_DIR
// (CI checks the repository out at the SHA in contracts.lock; see tools/fetch-contracts.sh).
tasks.test {
    environment("CONTRACTS_DIR", System.getenv("CONTRACTS_DIR") ?: rootProject.file(".contracts").absolutePath)
}

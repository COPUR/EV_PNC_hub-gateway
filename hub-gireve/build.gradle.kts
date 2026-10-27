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

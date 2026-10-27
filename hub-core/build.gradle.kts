plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-test-fixtures`
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.yaml:snakeyaml:2.4")
    testImplementation(kotlin("test"))
    testFixturesApi("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
}

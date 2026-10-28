plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("org.yaml:snakeyaml:2.4")

    testFixturesApi(platform("org.junit:junit-bom:5.12.2"))
    testFixturesApi("org.junit.jupiter:junit-jupiter")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

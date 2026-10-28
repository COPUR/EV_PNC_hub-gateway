plugins {
    `java-library`
}

dependencies {
    api(project(":hub-core"))
    implementation("org.yaml:snakeyaml:2.4")

    testImplementation(testFixtures(project(":hub-core")))
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

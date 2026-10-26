plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
    testImplementation(testFixtures(project(":libs:platform")))
    testImplementation("org.testcontainers:localstack")
}

tasks.bootJar { archiveFileName.set("audit.jar") }

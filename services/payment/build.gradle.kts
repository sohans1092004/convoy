plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
    testImplementation(testFixtures(project(":libs:contracts")))
    testImplementation(testFixtures(project(":libs:platform")))
    testImplementation("org.testcontainers:localstack")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.bootJar { archiveFileName.set("payment.jar") }

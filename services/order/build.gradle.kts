plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
    implementation(project(":libs:price-engine"))
    testImplementation(testFixtures(project(":libs:contracts")))
    testImplementation(testFixtures(project(":libs:platform")))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.bootJar { archiveFileName.set("order.jar") }

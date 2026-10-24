plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
    implementation("org.springframework.boot:spring-boot-starter-mail")
    testImplementation(testFixtures(project(":libs:contracts")))
    testImplementation(testFixtures(project(":libs:platform")))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.bootJar { archiveFileName.set("identity.jar") }

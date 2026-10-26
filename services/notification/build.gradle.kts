plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
    implementation("org.springframework.boot:spring-boot-starter-mail") // AMENDMENT-011: SMTP everywhere (Mailpit locally, SES SMTP on AWS)
    testImplementation(testFixtures(project(":libs:platform")))
    testImplementation("org.testcontainers:localstack")
}

tasks.bootJar { archiveFileName.set("notification.jar") }

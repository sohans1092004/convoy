plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
    implementation("software.amazon.awssdk:ses") // email (LocalStack SES -> Mailpit locally)
    testImplementation(testFixtures(project(":libs:platform")))
    testImplementation("org.testcontainers:localstack")
}

tasks.bootJar { archiveFileName.set("notification.jar") }

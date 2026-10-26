plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
    implementation(project(":libs:price-engine"))
    implementation("software.amazon.awssdk:s3") // dispute evidence: pre-signed URLs (LocalStack locally; P4.E)
    testImplementation(testFixtures(project(":libs:contracts")))
    testImplementation(testFixtures(project(":libs:platform")))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:localstack")
}

tasks.bootJar { archiveFileName.set("order.jar") }

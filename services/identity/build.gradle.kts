plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation(libs.webauthn4j.core) // passkeys (E003-E005); Apache-2.0
    testImplementation(testFixtures(project(":libs:contracts")))
    testImplementation(testFixtures(project(":libs:platform")))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.bootJar { archiveFileName.set("identity.jar") }

// webauthn4j uses Jackson 3, which shares jackson-annotations with Spring Boot's Jackson 2 and needs 2.22
// (Boot 3.5 pins an older one -> NoSuchMethodError). Annotation releases are additive, so Jackson 2 is unaffected.
dependencyManagement { dependencies { dependency("com.fasterxml.jackson.core:jackson-annotations:2.22") } }

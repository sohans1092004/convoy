// Shared platform for every Spring MVC service (problem+json, idempotency, internal JWT,
// outbox/inbox over SNS/SQS, aggregate versions, tracing, state-machine tables).
dependencies {
    api(project(":libs:contracts"))
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.flywaydb:flyway-core")
    api("org.flywaydb:flyway-database-postgresql")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api(libs.springdoc.webmvc)
    api(libs.nimbus.jose.jwt)
    api(libs.otel.api)
    api("software.amazon.awssdk:sns")
    api("software.amazon.awssdk:sqs")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(testFixtures(project(":libs:contracts")))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:localstack")
    testRuntimeOnly("org.postgresql:postgresql")
}

// Shared spec data the services read at runtime: state-machine tables and the ruleset.
tasks.processResources {
    from(rootProject.file("spec-tools/vectors")) {
        include("transitions.json", "rules.json")
        into("convoy")
    }
}

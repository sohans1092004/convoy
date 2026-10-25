plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation(testFixtures(project(":libs:platform")))
}

tasks.bootJar { archiveFileName.set("realtime.jar") }

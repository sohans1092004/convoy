plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.nimbus.jose.jwt)
    testImplementation("io.projectreactor:reactor-test")
}

tasks.bootJar { archiveFileName.set("gateway.jar") }

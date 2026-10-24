plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":libs:platform"))
}

tasks.bootJar { archiveFileName.set("dealer.jar") }

dependencies {
    testImplementation(testFixtures(project(":libs:platform")))
}

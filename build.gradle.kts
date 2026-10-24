// Shared conventions for every JVM module: Java 21 toolchain, JUnit 5, Spring dependency BOMs.
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

subprojects {
    if (buildFile.exists().not()) return@subprojects
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    group = "in.convoy"
    version = "0.1.0"

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(rootProject.libs.versions.java.get())) }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}")
            mavenBom(rootProject.libs.spring.cloud.bom.get().toString())
            mavenBom(rootProject.libs.awssdk.bom.get().toString())
        }
    }

    dependencies {
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> { options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked")) }
    tasks.withType<Test> {
        useJUnitPlatform()
        // Shared spec data (vectors, machines, schemas) lives at the repo root.
        systemProperty("convoy.repoRoot", rootDir.absolutePath)
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

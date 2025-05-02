// build.gradle.kts
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")
    implementation("org.tmatesoft.svnkit:svnkit:1.10.11")

    implementation("org.modelmapper:modelmapper:3.1.1")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

// The 'application' plugin is applied, but we configure the Main-Class in the Shadow JAR manifest
// No need for an application { mainClass.set(...) } block here

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "21"
}

// Shadow JAR configuration, including manifest Main-Class
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("deploy-project-cli")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    manifest {
        attributes(
            "Main-Class" to "com.deployproject.util.DeployProjectApplicationKt"
        )
    }
}

// Disable Spring Boot's default packaging tasks
tasks.named("bootJar") { enabled = false }

// Ensure the shadowJar is built as part of the standard build
tasks.named("build") { dependsOn(tasks.named("shadowJar")) }

tasks.withType<Test> {
    useJUnitPlatform()
}

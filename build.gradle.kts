// build.gradle.kts
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar
plugins {
    // Kotlin/JVM, Spring Boot, Dependency Management…
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    application
}

group = "com.deployproject"
version = "1.0.0"

java {
    // 컴파일 대상 JVM 버전 17 설정
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    // (선택) NimblyGames 릴리스 저장소
    maven("https://artifactory.nimblygames.com/artifactory/ng-public-release/")
}

dependencies {
    // Kotlin 표준 라이브러리 명시적 추가
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.badlogicgames.packr:packr:3.0.3")

    // Lombok (컴파일 타임만)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // Git & SVN
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")
    implementation("org.tmatesoft.svnkit:svnkit:1.10.11")

    // 기타
    implementation("org.modelmapper:modelmapper:3.1.1")
    runtimeOnly("com.mysql:mysql-connector-j")

    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

// CLI entry point 설정

// Kotlin 컴파일 옵션
tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}


application {
    // Kotlin DSL 에서는 이렇게
    mainClass.set("com.deployProject.DeployProjectApplicationKt")
}

springBoot {
    // Spring Boot 플러그인 버전에 따라
    mainClass.set("com.deployProject.DeployProjectApplicationKt")
}

// 테스트: JUnit Platform 사용
tasks.withType<Test> {
    useJUnitPlatform()
}

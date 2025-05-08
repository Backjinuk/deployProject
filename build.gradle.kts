// build.gradle.kts
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType

plugins {
    // Kotlin/JVM 플러그인
    kotlin("jvm") version "1.9.25"
    // Spring 지원 플러그인
    kotlin("plugin.spring") version "1.9.25"
    // JPA 지원
    kotlin("plugin.jpa") version "1.9.25"

    // Spring Boot Application 플러그인 (패키징 비활성화)
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"

    // Application 및 Shadow JAR 플러그인
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
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
application {
    mainClass.set("com.deployproject.util.GitInfoCli")
}

// Kotlin 컴파일 옵션
tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

// Spring Boot 기본 bootJar 비활성화 (shadowJar 사용)
tasks.named<BootJar>("bootJar") {
    enabled = false
}

// shadowJar 설정: fat-JAR 생성 및 Manifest Main-Class, 의존성 포함
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("deploy-project-cli")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    // 런타임 클래스패스 의존성까지 모두 병합 (main source set 사용)
    from({
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        sourceSets.getByName("main").runtimeClasspath
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })

    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to application.mainClass.get()
        )
    }
}

// build 시 shadowJar 실행 보장
tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

// 테스트: JUnit Platform 사용
tasks.withType<Test> {
    useJUnitPlatform()
}

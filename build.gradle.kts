// build.gradle.kts
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

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
version = "1.0.1"

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
    implementation("org.tmatesoft.svnkit:svnkit:1.10.3")

    // 기타
    implementation("org.modelmapper:modelmapper:3.1.1")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("com.h2database:h2")

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

val isWindowsHost = System.getProperty("os.name").lowercase().contains("windows")
val npmCommand = if (isWindowsHost) "npm.cmd" else "npm"
val desktopJdkLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

fun resolveJpackageCommand(): String {
    val executableName = if (isWindowsHost) "jpackage.exe" else "jpackage"
    val candidates = mutableListOf<File>()

    candidates += desktopJdkLauncher.get().executablePath.asFile.parentFile.resolve(executableName)
    System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let {
        candidates += file("$it/bin/$executableName")
    }
    providers.gradleProperty("org.gradle.java.home").orNull?.takeIf { it.isNotBlank() }?.let {
        candidates += file("$it/bin/$executableName")
    }
    candidates += file("${System.getProperty("java.home")}/bin/$executableName")

    if (isWindowsHost) {
        candidates += file("C:/Program Files/Java/jdk-17/bin/$executableName")
        candidates += file("C:/Program Files/Java/jdk-21/bin/$executableName")
    }

    return candidates.firstOrNull { it.exists() }?.absolutePath ?: executableName
}

fun resolveWixBinDir(): File? {
    val candidates = mutableListOf<File>()

    System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let {
        candidates += file("$it/WiXToolset/wix314")
    }
    candidates += file("C:/Program Files (x86)/WiX Toolset v3.14/bin")
    candidates += file("C:/Program Files (x86)/WiX Toolset v3.11/bin")

    return candidates.firstOrNull {
        it.resolve("candle.exe").exists() && it.resolve("light.exe").exists()
    }
}

val frontendDir = layout.projectDirectory.dir("src/main/front")
val frontendBuildDir = frontendDir.dir("build")
val generatedFrontendResourcesDir = layout.buildDirectory.dir("generated/frontend-resources")
val desktopInputDir = layout.buildDirectory.dir("jpackage-input")
val desktopOutputDir = layout.buildDirectory.dir("jpackage-output")

tasks.register<Exec>("buildFrontend") {
    group = "build"
    description = "Builds the React frontend for the desktop/local Spring Boot jar."
    workingDir = frontendDir.asFile
    commandLine(npmCommand, "run", "build")

    inputs.files(
        fileTree(frontendDir) {
            include("src/**")
            include("public/**")
            include("package.json")
            include("package-lock.json")
            include("tsconfig.json")
        }
    )
    outputs.dir(frontendBuildDir)
}

tasks.register<Sync>("copyFrontendBuild") {
    group = "build"
    description = "Copies React build output into Spring Boot static resources."
    dependsOn("buildFrontend")
    from(frontendBuildDir)
    into(generatedFrontendResourcesDir.map { it.dir("static") })
}

sourceSets {
    main {
        resources.srcDir(generatedFrontendResourcesDir)
    }
}

tasks.named("processResources") {
    dependsOn("copyFrontendBuild")
}

tasks.register<Sync>("prepareDesktopImageInput") {
    group = "distribution"
    description = "Prepares the executable Spring Boot jar for jpackage."
    dependsOn(tasks.named<BootJar>("bootJar"))
    from(tasks.named<BootJar>("bootJar").flatMap { it.archiveFile })
    into(desktopInputDir)
}

tasks.register<Exec>("desktopImage") {
    group = "distribution"
    description = "Creates a local desktop app image under build/jpackage/DeployProject."
    dependsOn("prepareDesktopImageInput")

    doFirst {
        delete(desktopOutputDir)
        executable = resolveJpackageCommand()
        resolveWixBinDir()?.let { wixBin ->
            environment("PATH", "${wixBin.absolutePath}${File.pathSeparator}${System.getenv("PATH")}")
        }
        args(
            "--type", "app-image",
            "--name", "DeployProject",
            "--input", desktopInputDir.get().asFile.absolutePath,
            "--main-jar", tasks.named<BootJar>("bootJar").get().archiveFileName.get(),
            "--dest", desktopOutputDir.get().asFile.absolutePath,
            "--java-options", "-Dspring.profiles.active=desktop",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dsun.stdout.encoding=UTF-8",
            "--java-options", "-Dsun.stderr.encoding=UTF-8"
        )
    }
}

tasks.register<Zip>("desktopImageZip") {
    group = "distribution"
    description = "Zips the generated desktop app image."
    dependsOn("desktopImage")
    archiveFileName.set("DeployProject-desktop-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(desktopOutputDir.map { it.dir("DeployProject") }) {
        into("DeployProject")
    }
}

tasks.register<Exec>("desktopInstaller") {
    group = "distribution"
    description = "Creates a Windows installer exe with jpackage. Requires WiX Toolset on Windows."
    onlyIf { isWindowsHost }
    dependsOn("prepareDesktopImageInput")

    doFirst {
        delete(desktopOutputDir)
        executable = resolveJpackageCommand()
        resolveWixBinDir()?.let { wixBin ->
            environment("PATH", "${wixBin.absolutePath}${File.pathSeparator}${System.getenv("PATH")}")
        }
        args(
            "--type", "exe",
            "--name", "DeployProject",
            "--app-version", project.version.toString(),
            "--vendor", "DeployProject",
            "--input", desktopInputDir.get().asFile.absolutePath,
            "--main-jar", tasks.named<BootJar>("bootJar").get().archiveFileName.get(),
            "--dest", desktopOutputDir.get().asFile.absolutePath,
            "--java-options", "-Dspring.profiles.active=desktop",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dsun.stdout.encoding=UTF-8",
            "--java-options", "-Dsun.stderr.encoding=UTF-8",
            "--win-menu",
            "--win-shortcut",
            "--win-dir-chooser"
        )
    }
}

tasks.register<Sync>("installerDownloadFile") {
    group = "distribution"
    description = "Copies the Windows installer to build/download/DeployProject.exe for the download server."
    dependsOn("desktopInstaller")
    from(desktopOutputDir.map { it.file("DeployProject-${project.version}.exe") })
    into(layout.buildDirectory.dir("download"))
    rename { "DeployProject.exe" }
}

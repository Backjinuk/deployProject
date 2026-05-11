package com.deployProject.cli.utilCli

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class ProjectJavaSpec(
    val targetJavaVersion: Int?,
    val buildJavaVersion: Int?,
    val detectedFrom: String
)

object ProjectJavaInspector {
    fun inspect(projectDir: File): ProjectJavaSpec? {
        return inspectGradle(projectDir)
            ?: inspectMaven(projectDir)
            ?: inspectVersionManagerFiles(projectDir)
    }

    private fun inspectGradle(projectDir: File): ProjectJavaSpec? {
        val versionManagerSpec = inspectVersionManagerFiles(projectDir)
        val gradleFile = listOf("build.gradle.kts", "build.gradle")
            .map { File(projectDir, it) }
            .firstOrNull(File::isFile)
            ?: return versionManagerSpec

        val text = runCatching { gradleFile.readText() }.getOrNull() ?: return versionManagerSpec

        val toolchainVersion = firstDetectedVersion(
            text,
            listOf(
                Regex("""languageVersion\s*\.\s*set\s*\(\s*JavaLanguageVersion\.of\((\d+)\)\s*\)"""),
                Regex("""JavaLanguageVersion\.of\((\d+)\)"""),
                Regex("""jvmToolchain\s*\(\s*(\d+)\s*\)""")
            )
        )
        val releaseVersion = firstDetectedVersion(
            text,
            listOf(
                Regex("""options\.release(?:\.set)?\s*\(?\s*["']?([0-9.]+)["']?""")
            )
        )
        val sourceVersion = firstDetectedVersion(
            text,
            listOf(
                Regex("""sourceCompatibility\s*=\s*JavaVersion\.VERSION_(?:1_)?(\d+)"""),
                Regex("""sourceCompatibility\s*=\s*["']?([0-9.]+)["']?""")
            )
        )
        val targetVersion = firstDetectedVersion(
            text,
            listOf(
                Regex("""targetCompatibility\s*=\s*JavaVersion\.VERSION_(?:1_)?(\d+)"""),
                Regex("""targetCompatibility\s*=\s*["']?([0-9.]+)["']?"""),
                Regex("""kotlinOptions\.jvmTarget\s*=\s*["']([^"']+)["']""")
            )
        )

        val resolvedTarget = releaseVersion
            ?: targetVersion
            ?: sourceVersion
            ?: versionManagerSpec?.targetJavaVersion
        val resolvedBuild = toolchainVersion
            ?: versionManagerSpec?.buildJavaVersion
            ?: resolvedTarget

        if (resolvedTarget == null && resolvedBuild == null) return versionManagerSpec

        val sourceLabel = when {
            toolchainVersion != null && releaseVersion != null -> "${gradleFile.name}(toolchain+release)"
            toolchainVersion != null -> "${gradleFile.name}(toolchain)"
            releaseVersion != null -> "${gradleFile.name}(release)"
            targetVersion != null -> "${gradleFile.name}(targetCompatibility)"
            sourceVersion != null -> "${gradleFile.name}(sourceCompatibility)"
            else -> versionManagerSpec?.detectedFrom ?: gradleFile.name
        }

        return ProjectJavaSpec(
            targetJavaVersion = resolvedTarget ?: resolvedBuild,
            buildJavaVersion = resolvedBuild ?: resolvedTarget,
            detectedFrom = sourceLabel
        )
    }

    private fun inspectMaven(projectDir: File): ProjectJavaSpec? {
        val versionManagerSpec = inspectVersionManagerFiles(projectDir)
        val pomFile = File(projectDir, "pom.xml")
        if (!pomFile.isFile) return versionManagerSpec

        val document = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().parse(pomFile)
        }.getOrNull() ?: return versionManagerSpec

        val propertyMap = linkedMapOf<String, String>()
        document.getElementsByTagName("properties").item(0)?.childNodes?.let { nodes ->
            for (idx in 0 until nodes.length) {
                val node = nodes.item(idx)
                if (node is Element) {
                    propertyMap[node.tagName] = node.textContent?.trim().orEmpty()
                }
            }
        }

        val releaseVersion = resolvePropertyVersion(propertyMap["maven.compiler.release"], propertyMap)
        val targetVersion = resolvePropertyVersion(propertyMap["maven.compiler.target"], propertyMap)
        val sourceVersion = resolvePropertyVersion(propertyMap["maven.compiler.source"], propertyMap)
        val javaVersion = resolvePropertyVersion(propertyMap["java.version"], propertyMap)
        val toolchainVersion = findMavenToolchainVersion(document)

        val resolvedTarget = releaseVersion
            ?: targetVersion
            ?: sourceVersion
            ?: javaVersion
            ?: versionManagerSpec?.targetJavaVersion
        val resolvedBuild = toolchainVersion
            ?: versionManagerSpec?.buildJavaVersion
            ?: resolvedTarget

        if (resolvedTarget == null && resolvedBuild == null) return versionManagerSpec

        val sourceLabel = when {
            toolchainVersion != null && releaseVersion != null -> "pom.xml(toolchain+release)"
            toolchainVersion != null -> "pom.xml(toolchain)"
            releaseVersion != null -> "pom.xml(maven.compiler.release)"
            targetVersion != null -> "pom.xml(maven.compiler.target)"
            sourceVersion != null -> "pom.xml(maven.compiler.source)"
            javaVersion != null -> "pom.xml(java.version)"
            else -> versionManagerSpec?.detectedFrom ?: "pom.xml"
        }

        return ProjectJavaSpec(
            targetJavaVersion = resolvedTarget ?: resolvedBuild,
            buildJavaVersion = resolvedBuild ?: resolvedTarget,
            detectedFrom = sourceLabel
        )
    }

    private fun inspectVersionManagerFiles(projectDir: File): ProjectJavaSpec? {
        val javaVersionFile = File(projectDir, ".java-version")
        if (javaVersionFile.isFile) {
            val version = inspectVersionToken(runCatching { javaVersionFile.readText().trim() }.getOrNull())
            if (version != null) {
                return ProjectJavaSpec(version, version, ".java-version")
            }
        }

        val sdkmanFile = File(projectDir, ".sdkmanrc")
        if (sdkmanFile.isFile) {
            val version = runCatching { sdkmanFile.readLines() }.getOrDefault(emptyList())
                .firstOrNull { it.trim().startsWith("java=") }
                ?.substringAfter("=")
                ?.let(::inspectVersionToken)
            if (version != null) {
                return ProjectJavaSpec(version, version, ".sdkmanrc")
            }
        }

        val asdfFile = File(projectDir, ".tool-versions")
        if (asdfFile.isFile) {
            val version = runCatching { asdfFile.readLines() }.getOrDefault(emptyList())
                .firstOrNull { it.trim().startsWith("java ") }
                ?.substringAfter("java")
                ?.trim()
                ?.let(::inspectVersionToken)
            if (version != null) {
                return ProjectJavaSpec(version, version, ".tool-versions")
            }
        }

        return null
    }

    private fun firstDetectedVersion(text: String, patterns: List<Regex>): Int? {
        patterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.let { token ->
                inspectVersionToken(token)?.let { return it }
            }
        }
        return null
    }

    private fun resolvePropertyVersion(rawValue: String?, properties: Map<String, String>): Int? {
        if (rawValue.isNullOrBlank()) return null
        val trimmed = rawValue.trim()
        val resolved = if (trimmed.startsWith("\${") && trimmed.endsWith("}")) {
            properties[trimmed.removePrefix("\${").removeSuffix("}")]
        } else {
            trimmed
        }
        return inspectVersionToken(resolved)
    }

    private fun findMavenToolchainVersion(document: org.w3c.dom.Document): Int? {
        val pluginNodes = document.getElementsByTagName("plugin")
        for (idx in 0 until pluginNodes.length) {
            val plugin = pluginNodes.item(idx) as? Element ?: continue
            val artifactId = plugin.getElementsByTagName("artifactId").item(0)?.textContent?.trim()
            if (artifactId != "maven-toolchains-plugin") continue

            val versionNodes = plugin.getElementsByTagName("jdk")
            for (jdkIdx in 0 until versionNodes.length) {
                val jdkNode = versionNodes.item(jdkIdx) as? Element ?: continue
                val versionText = jdkNode.getElementsByTagName("version").item(0)?.textContent?.trim()
                inspectVersionToken(versionText)?.let { return it }
            }
        }
        return null
    }

    fun inspectVersionToken(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().trim('"', '\'')

        Regex("""^1\.(\d+)(?:[^\d].*)?$""").find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""^(\d+)(?:\.\d+.*)?$""").find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""(?:^|[^\d])(\d{1,2})(?:[^\d]|$)""").find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        return null
    }
}

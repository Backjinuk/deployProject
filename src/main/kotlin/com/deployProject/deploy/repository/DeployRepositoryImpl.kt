package com.deployProject.deploy.repository

import com.deployProject.deploy.domain.site.SiteDto
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime

@Repository
class DeployRepositoryImpl(
    private val objectMapper: ObjectMapper,
    @Value("\${deploy.storage.sites-file:}")
    private val configuredSitesFile: String
) : DeployRepository {

    private data class SiteStore(
        val nextId: Long = 1,
        val sites: List<SiteDto> = emptyList()
    )

    private val lock = Any()

    override fun getSites(): List<SiteDto> = synchronized(lock) {
        readStore().sites
            .filter(::isActive)
            .sortedWith(compareBy<SiteDto> { it.createdAt ?: LocalDateTime.MIN }.thenBy { it.id ?: Long.MAX_VALUE })
    }

    override fun getPathList(): List<SiteDto> = getSites()

    override fun savedPath(site: SiteDto) = synchronized(lock) {
        val store = readStore()
        val now = LocalDateTime.now()
        val duplicateSite = if (site.id == null) store.sites.firstOrNull { current ->
            isActive(current) && hasPathIdentity(current) && hasPathIdentity(site) && pathKey(current) == pathKey(site)
        } else null
        val id = site.id ?: duplicateSite?.id ?: store.nextId
        val saved = site.copyForStorage().apply {
            this.id = id
            this.createdAt = site.createdAt ?: duplicateSite?.createdAt ?: now
            this.updatedAt = now
            this.useYn = site.useYn?.takeIf { it.isNotBlank() } ?: "Y"
        }

        val sites = store.sites
            .filterNot { it.id == id }
            .plus(saved)
        writeStore(SiteStore(nextId = maxOf(store.nextId, id + 1), sites = sites))
    }

    override fun updatePath(site: SiteDto) = synchronized(lock) {
        val siteId = requireNotNull(site.id) { "site.id is required for updatePath()" }
        val store = readStore()
        val sites = store.sites.map { current ->
            if (current.id != siteId) {
                current
            } else {
                current.copyForStorage().apply {
                    site.text?.let { text = it }
                    site.homePath?.let { homePath = it }
                    site.localPath?.let { localPath = it }
                    site.jdkPath?.let { jdkPath = it }
                    site.useYn?.let { useYn = it }
                    updatedAt = LocalDateTime.now()
                }
            }
        }

        writeStore(store.copy(sites = sites))
    }

    private fun readStore(): SiteStore {
        val path = sitesFile()
        if (!Files.isRegularFile(path)) return SiteStore()

        return runCatching {
            objectMapper.readValue<SiteStore>(path.toFile())
        }.getOrElse {
            SiteStore()
        }
    }

    private fun writeStore(store: SiteStore) {
        val path = sitesFile()
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "sites-", ".tmp")
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), store)
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sitesFile(): Path {
        val configured = configuredSitesFile.trim()
        if (configured.isNotBlank()) return Path.of(configured).toAbsolutePath().normalize()

        return Path.of(System.getProperty("user.home"), ".deploy-project", "sites.json")
            .toAbsolutePath()
            .normalize()
    }

    private fun isActive(site: SiteDto): Boolean =
        !site.useYn.equals("N", ignoreCase = true)

    private fun hasPathIdentity(site: SiteDto): Boolean =
        !site.text.isNullOrBlank() && !site.homePath.isNullOrBlank() && !site.localPath.isNullOrBlank()

    private fun normalizePathPart(value: String?): String =
        value.orEmpty().trim().replace('\\', '/').trimEnd('/').lowercase()

    private fun pathKey(site: SiteDto): Triple<String, String, String> =
        Triple(
            normalizePathPart(site.text),
            normalizePathPart(site.homePath),
            normalizePathPart(site.localPath)
        )

    private fun SiteDto.copyForStorage(): SiteDto =
        SiteDto().also { target ->
            target.id = id
            target.text = text
            target.homePath = homePath
            target.localPath = localPath
            target.jdkPath = jdkPath
            target.createdAt = createdAt
            target.updatedAt = updatedAt
            target.useYn = useYn
        }
}

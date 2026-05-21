package com.deployProject.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@RestController
@RequestMapping("/api/preferences")
class UserPreferenceController(
    private val objectMapper: ObjectMapper,
    @Value("\${deploy.storage.preferences-file:}")
    private val configuredPreferencesFile: String
) {
    data class ThemePreferenceRequest(val themeMode: String?)
    data class ThemePreferenceResponse(val themeMode: String?)

    private data class UserPreferences(
        val themeMode: String? = null
    )

    private val lock = Any()

    @GetMapping("/theme")
    fun getTheme(): ThemePreferenceResponse = synchronized(lock) {
        ThemePreferenceResponse(normalizeThemeMode(readPreferences().themeMode))
    }

    @PutMapping("/theme")
    fun updateTheme(@RequestBody request: ThemePreferenceRequest): ResponseEntity<ThemePreferenceResponse> {
        val themeMode = normalizeThemeMode(request.themeMode)
            ?: return ResponseEntity.badRequest().body(ThemePreferenceResponse(null))

        synchronized(lock) {
            writePreferences(readPreferences().copy(themeMode = themeMode))
        }

        return ResponseEntity.ok(ThemePreferenceResponse(themeMode))
    }

    private fun readPreferences(): UserPreferences {
        val path = preferencesFile()
        if (!Files.isRegularFile(path)) return UserPreferences()

        return runCatching {
            objectMapper.readValue<UserPreferences>(path.toFile())
        }.getOrElse {
            UserPreferences()
        }
    }

    private fun writePreferences(preferences: UserPreferences) {
        val path = preferencesFile()
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "preferences-", ".tmp")
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), preferences)
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun preferencesFile(): Path {
        val configured = configuredPreferencesFile.trim()
        if (configured.isNotBlank()) return Path.of(configured).toAbsolutePath().normalize()

        return Path.of(System.getProperty("user.home"), ".deploy-project", "preferences.json")
            .toAbsolutePath()
            .normalize()
    }

    private fun normalizeThemeMode(value: String?): String? =
        when (value?.trim()?.lowercase()) {
            "light" -> "light"
            "dark" -> "dark"
            else -> null
        }
}

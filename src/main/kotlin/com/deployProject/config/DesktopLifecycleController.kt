package com.deployProject.config

import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

@RestController
@Profile("desktop")
@RequestMapping("/api/app")
class DesktopLifecycleController(
    private val applicationContext: ConfigurableApplicationContext
) {
    private val logger = LoggerFactory.getLogger(DesktopLifecycleController::class.java)

    @PostMapping("/shutdown")
    fun shutdown(): ResponseEntity<Void> {
        if (shutdownRequested.compareAndSet(false, true)) {
            Thread {
                Thread.sleep(300)
                val exitCode = runCatching {
                    applicationContext.close()
                    0
                }.getOrElse { error ->
                    logger.warn("deployKit shutdown request failed", error)
                    1
                }

                exitProcess(exitCode)
            }.apply {
                name = "deploykit-ui-shutdown"
                isDaemon = false
                start()
            }
        }

        return ResponseEntity.accepted().build()
    }

    companion object {
        private val shutdownRequested = AtomicBoolean(false)
    }
}

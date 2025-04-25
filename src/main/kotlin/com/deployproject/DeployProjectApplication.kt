package com.deployproject

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DeployProjectApplication

fun main(args: Array<String>) {
    runApplication<DeployProjectApplication>(*args)
}

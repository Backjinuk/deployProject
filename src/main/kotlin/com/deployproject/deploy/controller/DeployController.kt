package com.deployproject.deploy.controller

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class Controller {


    @RequestMapping("/api/hello")
    fun hello(): String {
        return "Hello, World!"
    }
}
package com.deployproject.deploy.controller

import com.deployproject.deploy.domain.deployUser.DeployUserDto
import com.deployproject.deploy.domain.site.SiteDto
import com.deployproject.deploy.service.DeployService
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class DeployController(
    private val deployService: DeployService
) {


    @RequestMapping("/api/hello")
    fun hello(): String {
        return "Hello, World!"
    }

    @RequestMapping("/api/sites")
    fun getSites(@RequestBody deployUserDto : DeployUserDto): List<SiteDto> {
        println("getSites " + deployUserDto.id)
       return deployService.getSites( deployUserDto.id);
    }

   @RequestMapping("/api/login")
   fun login (@RequestBody deployUserDto : DeployUserDto): DeployUserDto {
       return deployService.login(deployUserDto)
   }

}
package com.deployproject.deploy.controller

import com.deployproject.deploy.domain.deployUser.DeployUserDto
import com.deployproject.deploy.domain.site.SiteDto
import com.deployproject.deploy.service.DeployService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties

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

    @RequestMapping("/api/pathList")
    fun logout (@RequestBody deployUserDto : DeployUserDto): List<SiteDto >{
       return deployService.getPathList(deployUserDto.id)
    }

    @RequestMapping("/api/updatePath")
    fun updatePath(@RequestBody dto : SiteDto) {
        val prop = SiteDto::class.memberProperties
            .firstOrNull { it.name == dto.field }
            // var 로 선언된 프로퍼티만
            .takeIf { it is KMutableProperty1<*, *> }
                as? KMutableProperty1<SiteDto, Any?>
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unknown or immutable field: ${dto.field}"
            )

        // 2) setter 호출로 dto 객체에 값 대입
        prop.setter.call(dto, dto.value)

        println("dto = ${dto.homePath}")
        println("dto.id = ${dto.id}")

        deployService.updatePath(dto)
    }

    @RequestMapping("/api/savedPath")
    fun savedPath(@RequestBody siteDto : SiteDto) {

        println("siteDto = ${siteDto}")

        println("siteDto.id = ${siteDto.id}")
        println("siteDto.homePath = ${siteDto.homePath}")
        println("siteDto.localPath = ${siteDto.localPath}")

        deployService.savedPath(siteDto)

    }
}
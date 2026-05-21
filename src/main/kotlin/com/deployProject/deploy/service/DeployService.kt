package com.deployProject.deploy.service

import com.deployProject.deploy.domain.site.SiteDto
import com.deployProject.deploy.repository.DeployRepository
import org.springframework.stereotype.Service

@Service
class DeployService(
    private val deployRepository: DeployRepository
){
    fun getSites(): List<SiteDto> {
        return deployRepository.getSites()
    }

    fun getPathList(): List<SiteDto> {
        return deployRepository.getPathList()
    }

    fun updatePath(dto: SiteDto) {
       deployRepository.updatePath(dto)
    }

    fun savedPath(siteDto: SiteDto) {
       deployRepository.savedPath(siteDto)
    }
}

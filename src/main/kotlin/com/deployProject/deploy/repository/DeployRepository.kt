package com.deployProject.deploy.repository

import com.deployProject.deploy.domain.site.SiteDto

interface DeployRepository {
    fun getSites(): List<SiteDto>
    fun getPathList(): List<SiteDto>
    fun updatePath(site: SiteDto)
    fun savedPath(site: SiteDto)
}

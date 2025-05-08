package com.deployProject.deploy.repositry

import com.deployProject.deploy.domain.deployUser.DeployUser
import com.deployProject.deploy.domain.deployUser.DeployUserDto
import com.deployProject.deploy.domain.site.Site
import com.deployProject.deploy.domain.site.SiteDto

interface DeployRepository {
    fun getSites(id: Long): List<SiteDto>
    fun findByUserName(userName: String): DeployUserDto
    fun addUser(deployUser: DeployUser)
    fun getPathList(userSeq: Long): List<SiteDto>
    fun updatePath(site : Site)
    fun savedPath(site: Site)
}
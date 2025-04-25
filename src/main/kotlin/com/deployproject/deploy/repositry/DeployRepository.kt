package com.deployproject.deploy.repositry

import com.deployproject.deploy.domain.deployUser.DeployUser
import com.deployproject.deploy.domain.deployUser.DeployUserDto
import com.deployproject.deploy.domain.site.SiteDto

interface DeployRepository {
    fun getSites(id: Long): List<SiteDto>
    fun findByUserName(userName: String): DeployUserDto
    fun addUser(deployUser: DeployUser)
}
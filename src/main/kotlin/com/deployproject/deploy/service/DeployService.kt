package com.deployproject.deploy.service

import com.deployproject.deploy.domain.deployUser.DeployUser
import com.deployproject.deploy.domain.deployUser.DeployUserDto
import com.deployproject.deploy.domain.site.Site
import com.deployproject.deploy.domain.site.SiteDto
import com.deployproject.deploy.repositry.DeployRepository
import jakarta.transaction.Transactional
import org.modelmapper.ModelMapper
import org.springframework.stereotype.Service
import kotlin.jvm.java

@Service
class DeployService(
    private val deployRepository: DeployRepository,
    private val modelMapper: ModelMapper
){
    fun getSites(id: Long): List<SiteDto> {
        return deployRepository.getSites(id)
    }

    @Transactional
    fun login(deployUserDto: DeployUserDto): DeployUserDto {

        val findUser : DeployUserDto = deployRepository.findByUserName(deployUserDto.userName);

        if(findUser.userName.equals("")){
           deployRepository.addUser(modelMapper.map(deployUserDto, DeployUser::class.java))
        }

        return deployRepository.findByUserName(deployUserDto.userName)
    }

    fun getPathList(userSeq: Long): List<SiteDto> {
        return deployRepository.getPathList(userSeq)
    }

    @Transactional
    fun updatePath(dto: SiteDto) {
       return deployRepository.updatePath(
           modelMapper.map(dto, Site::class.java)
       )
    }

    @Transactional
    fun savedPath(siteDto: SiteDto) {
       deployRepository.savedPath(
              modelMapper.map(siteDto, Site::class.java)
       )
    }
}
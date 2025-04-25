package com.deployproject.deploy.service

import com.deployproject.deploy.domain.deployUser.DeployUser
import com.deployproject.deploy.domain.deployUser.DeployUserDto
import com.deployproject.deploy.domain.site.SiteDto
import com.deployproject.deploy.repositry.DeployRepository
import jakarta.transaction.Transactional
import org.modelmapper.ModelMapper
import org.springframework.stereotype.Service

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
}
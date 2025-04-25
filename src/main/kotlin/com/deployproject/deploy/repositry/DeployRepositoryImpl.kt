package com.deployproject.deploy.repositry

import com.deployproject.deploy.domain.deployUser.DeployUser
import com.deployproject.deploy.domain.deployUser.DeployUserDto
import com.deployproject.deploy.domain.site.Site
import com.deployproject.deploy.domain.site.SiteDto
import jakarta.persistence.EntityManager
import org.modelmapper.ModelMapper
import org.springframework.stereotype.Repository
import kotlin.jvm.java

@Repository
class DeployRepositoryImpl (
    private val entityManager : EntityManager,
    private val modelMapper : ModelMapper
) : DeployRepository {

    override fun getSites(id: Long): List<SiteDto> {
        val query = entityManager.createQuery("SELECT s FROM Site s where s.userSeq = :id", Site::class.java)
        query.setParameter("id", id)

        return query.resultList.map { site ->
            modelMapper.map(site, SiteDto::class.java)
        }
    }

    override fun findByUserName(userName: String): DeployUserDto {
        val query = entityManager.createQuery("SELECT u FROM DeployUser u WHERE u.userName = :userName", DeployUser::class.java)
        query.setParameter("userName", userName)

        val entity  = query.resultList.firstOrNull() ?: return DeployUserDto()

        return modelMapper.map(entity, DeployUserDto::class.java)
    }


    override fun addUser(deployUser: DeployUser) {
       entityManager.persist(deployUser)
    }
}
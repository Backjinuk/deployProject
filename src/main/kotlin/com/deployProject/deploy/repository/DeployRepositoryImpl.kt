package com.deployProject.deploy.repository

import com.deployProject.deploy.domain.deployUser.DeployUser
import com.deployProject.deploy.domain.deployUser.DeployUserDto
import com.deployProject.deploy.domain.site.Site
import com.deployProject.deploy.domain.site.SiteDto
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.modelmapper.ModelMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import kotlin.jvm.java

@Repository
class DeployRepositoryImpl(
    private val entityManager: EntityManager,
    private val modelMapper: ModelMapper
) : DeployRepository {

    private val logger = LoggerFactory.getLogger(DeployRepositoryImpl::class.java);

    override fun getSites(id: Long): List<SiteDto> {
        logger.info("getSites started")

        val query = entityManager.createQuery(
            "SELECT s " +
                    "FROM Site s " +
                    "where s.userSeq = :id " +
                    "and (useYn != 'N' or useYn IS NULL)",
            Site::class.java
        )

        query.setParameter("id", id)

        val map = query.resultList.map { site ->
            modelMapper.map(site, SiteDto::class.java)
        }

        return map
    }

    override fun findByUserName(userName: String): DeployUserDto {
        logger.info("findByUserName started")
        val query =
            entityManager.createQuery("SELECT u FROM DeployUser u WHERE u.userName = :userName", DeployUser::class.java)
        query.setParameter("userName", userName)

        val entity = query.resultList.firstOrNull() ?: return DeployUserDto()

        return modelMapper.map(entity, DeployUserDto::class.java)
    }


    override fun addUser(deployUser: DeployUser) {
        logger.info("addUser started")

        entityManager.persist(deployUser)
    }

    override fun getPathList(userSeq: Long): List<SiteDto> {
        logger.info("getPathList started")
        val query = entityManager.createQuery(
            "SELECT s " +
                    "FROM Site s " +
                    "WHERE s.userSeq = :userSeq " +
                    "and (s.useYn != 'N' or s.useYn is null) ",
            Site::class.java
        )
        query.setParameter("userSeq", userSeq)

        return query.resultList.map { site ->
            modelMapper.map(site, SiteDto::class.java)
        }
    }

    @Transactional
    override fun updatePath(site: Site) {
        logger.info("updatePath started")

        val assignments = mutableListOf<String>().apply {
            site.text?.let { add("s.text      = :text") }
            site.homePath?.let { add("s.homePath  = :homePath") }
            site.localPath?.let { add("s.localPath = :localPath") }
            site.useYn?.let { add("s.useYn = :useYn") }
        }

        // 수정할 필드가 없으면 바로 종료
        if (assignments.isEmpty()) return

        // 3) JPQL 생성
        val jpql = buildString {
            append("UPDATE Site s SET ")
            append(assignments.joinToString(", "))
            append(" WHERE s.id = :id")
        }

        // 4) Query 생성 및 파라미터 바인딩
        val query = entityManager.createQuery(jpql)
        site.id?.let { query.setParameter("id", it) }
        site.text?.let { query.setParameter("text", it) }
        site.homePath?.let { query.setParameter("homePath", it) }
        site.localPath?.let { query.setParameter("localPath", it) }
        site.useYn?.let { query.setParameter("useYn", it) }

        // 5) 쿼리 실행
        query.executeUpdate()

        // 6) updatedAt 필드 수동 갱신 (JPQL UPDATE는 이 필드를 건드리지 않으므로)
        entityManager.flush()
    }

    override fun savedPath(site: Site) {
        logger.info("savedPath started")

        entityManager.persist(site)
    }
}
package com.deployproject.deploy.repositry

import com.deployproject.deploy.domain.deployUser.DeployUser
import com.deployproject.deploy.domain.deployUser.DeployUserDto
import com.deployproject.deploy.domain.site.Site
import com.deployproject.deploy.domain.site.SiteDto
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.modelmapper.ModelMapper
import org.springframework.data.jpa.domain.AbstractPersistable_.id
import org.springframework.stereotype.Repository
import org.springframework.util.xml.SimpleTransformErrorListener
import java.time.LocalDateTime
import kotlin.jvm.java

@Repository
class DeployRepositoryImpl(
    private val entityManager: EntityManager,
    private val modelMapper: ModelMapper
) : DeployRepository {

    override fun getSites(id: Long): List<SiteDto> {
        val query = entityManager.createQuery("SELECT s FROM Site s where s.userSeq = :id and useYn != 'N' ", Site::class.java)
        query.setParameter("id", id)

        return query.resultList.map { site ->
            modelMapper.map(site, SiteDto::class.java)
        }
    }

    override fun findByUserName(userName: String): DeployUserDto {
        val query =
            entityManager.createQuery("SELECT u FROM DeployUser u WHERE u.userName = :userName", DeployUser::class.java)
        query.setParameter("userName", userName)

        val entity = query.resultList.firstOrNull() ?: return DeployUserDto()

        return modelMapper.map(entity, DeployUserDto::class.java)
    }


    override fun addUser(deployUser: DeployUser) {
        entityManager.persist(deployUser)
    }

    override fun getPathList(userSeq: Long): List<SiteDto> {
        val query = entityManager.createQuery(
            "SELECT s " +
                    "FROM Site s " +
                    "WHERE s.userSeq = :userSeq " +
                    "and s.useYn != 'N' or s.useYn is null ",
            Site::class.java)
        query.setParameter("userSeq", userSeq)

        return query.resultList.map { site ->
            modelMapper.map(site, SiteDto::class.java)
        }
    }

    @Transactional
    override fun updatePath(site: Site) {

        val assignments = mutableListOf<String>().apply {
            site.text     ?.let { add("s.text      = :text") }
            site.homePath ?.let { add("s.homePath  = :homePath") }
            site.localPath?.let { add("s.localPath = :localPath") }
            site.javaOld  ?.let { add("s.javaOld   = :javaOld") }
            site.javaNew  ?.let { add("s.javaNew   = :javaNew") }
            site.xmlOld   ?.let { add("s.xmlOld    = :xmlOld") }
            site.xmlNew   ?.let { add("s.xmlNew    = :xmlNew") }
            site.jspOld   ?.let { add("s.jspOld    = :jspOld") }
            site.jspNew   ?.let { add("s.jspNew    = :jspNew") }
            site.scriptOld?.let { add("s.scriptOld = :scriptOld") }
            site.scriptNew?.let { add("s.scriptNew = :scriptNew") }
            site.useYn    ?.let { add("s.useYn = :useYn")}
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
        site.id       ?.let { query.setParameter("id", it) }
        site.text     ?.let { query.setParameter("text",      it) }
        site.homePath ?.let { query.setParameter("homePath",  it) }
        site.localPath?.let { query.setParameter("localPath", it) }
        site.javaOld  ?.let { query.setParameter("javaOld",   it) }
        site.javaNew  ?.let { query.setParameter("javaNew",   it) }
        site.xmlOld   ?.let { query.setParameter("xmlOld",    it) }
        site.xmlNew   ?.let { query.setParameter("xmlNew",    it) }
        site.jspOld   ?.let { query.setParameter("jspOld",    it) }
        site.jspNew   ?.let { query.setParameter("jspNew",    it) }
        site.scriptOld?.let { query.setParameter("scriptOld", it) }
        site.scriptNew?.let { query.setParameter("scriptNew", it) }
        site.useYn    ?.let { query.setParameter("useYn", it) }

        // 5) 쿼리 실행
        query.executeUpdate()

        // 6) updatedAt 필드 수동 갱신 (JPQL UPDATE는 이 필드를 건드리지 않으므로)
        entityManager.flush()
    }

    override fun savedPath(site: Site) {
       entityManager.persist(site)
    }
}
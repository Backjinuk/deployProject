package com.deployProject.deploy.service

import com.deployProject.deploy.domain.deployUser.DeployUser
import com.deployProject.deploy.domain.deployUser.DeployUserDto
import com.deployProject.deploy.domain.site.Site
import com.deployProject.deploy.domain.site.SiteDto
import com.deployProject.deploy.repository.DeployRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.modelmapper.ModelMapper

class DeployServiceLoginTest {

    private val fakeRepository = FakeDeployRepository()
    private val deployService = DeployService(fakeRepository, ModelMapper())

    @Test
    fun `백진욱 user first login creates user and returns created account`() {
        val request = DeployUserDto().apply {
            userName = "백진욱"
        }
        // 수정 이유: user_id(백진욱) 최초 로그인 시 addUser 호출 후 생성된 사용자를 반환하는 흐름을 검증한다.
        fakeRepository.nextId = 1001L

        val result = deployService.login(request)

        assertEquals(1, fakeRepository.addUserCalled)
        assertEquals(1001L, result.id)
        assertEquals("백진욱", result.userName)
    }

    private class FakeDeployRepository : DeployRepository {
        private val users = linkedMapOf<String, DeployUserDto>()
        var addUserCalled: Int = 0
        var nextId: Long = 1L

        override fun getSites(id: Long): List<SiteDto> = emptyList()

        override fun findByUserName(userName: String): DeployUserDto {
            return users[userName] ?: DeployUserDto()
        }

        override fun addUser(deployUser: DeployUser) {
            addUserCalled += 1
            users[deployUser.userName] = DeployUserDto().apply {
                id = nextId
                userName = deployUser.userName
            }
        }

        override fun getPathList(userSeq: Long): List<SiteDto> = emptyList()

        override fun updatePath(site: Site) = Unit

        override fun savedPath(site: Site) = Unit
    }
}

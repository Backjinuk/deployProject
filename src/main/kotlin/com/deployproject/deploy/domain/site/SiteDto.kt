package com.deployproject.deploy.domain.site

import jakarta.persistence.Column
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import lombok.Data
import java.time.LocalDateTime

@Data
open class SiteDto {

    var id: Long? = null

    var text: String? = null

    var userSeq: Long? = null

    /** 운영 서버 홈 디렉토리 */
    var homePath: String? = null


    /** 사용자의 로컬 디렉토리 경로 */
    var localPath: String? = null

    var field : String? = null

    var value: String? = null

    var createdAt: LocalDateTime? = LocalDateTime.now()

    var updatedAt: LocalDateTime? = null

    var useYn : String? = null
}
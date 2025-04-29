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

    /** 로컬 프로젝트 내 원본 Java 소스 디렉토리 */
    var javaOld: String? = null

    /** 운영 서버에 복사될 클래스 파일 경로 */
    var javaNew: String? = null

    /** 원본 XML 리소스 디렉토리 */
    var xmlOld: String? = null

    /** 운영 서버 XML 배치 경로 */
    var xmlNew: String? = null

    /** 원본 JSP 디렉토리 */
    var jspOld: String? = null

    /** 운영 서버 JSP 디렉토리 */
    var jspNew: String? = null

    /** 원본 스크립트(HTML/JS) 디렉토리 */
    var scriptOld: String? = null

    /** 운영 서버 스크립트 배치 디렉토리 */
    var scriptNew: String? = null

    /** 사용자의 로컬 디렉토리 경로 */
    var localPath: String? = null

    var field : String? = null

    var value: String? = null

    var createdAt: LocalDateTime? = LocalDateTime.now()

    var updatedAt: LocalDateTime? = null

    var useYn : String? = null
}
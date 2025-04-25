package com.deployproject.deploy.domain.site

import jakarta.persistence.*
import lombok.*
import java.time.LocalDateTime

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** 사용자 key값*/
    @Column(name = "user_seq", nullable = false)
    var userSeq: Long? = null

    /** 사이트 이름 (예: "대표홈페이지") */
    @Column(nullable = false, length = 100)
    var text: String? = null

    /** 운영 서버 홈 디렉토리 */
    @Column(name = "home_path", nullable = false, length = 255)
    var homePath: String? = null

    /** 로컬 프로젝트 내 원본 Java 소스 디렉토리 */
    @Column(name = "java_old", length = 255)
    var javaOld: String? = null

    /** 운영 서버에 복사될 클래스 파일 경로 */
    @Column(name = "java_new", length = 255)
    var javaNew: String? = null

    /** 원본 XML 리소스 디렉토리 */
    @Column(name = "xml_old", length = 255)
    var xmlOld: String? = null

    /** 운영 서버 XML 배치 경로 */
    @Column(name = "xml_new", length = 255)
    var xmlNew: String? = null

    /** 원본 JSP 디렉토리 */
    @Column(name = "jsp_old", length = 255)
    var jspOld: String? = null

    /** 운영 서버 JSP 디렉토리 */
    @Column(name = "jsp_new", length = 255)
    var jspNew: String? = null

    /** 원본 스크립트(HTML/JS) 디렉토리 */
    @Column(name = "script_old", length = 255)
    var scriptOld: String? = null

    /** 운영 서버 스크립트 배치 디렉토리 */
    @Column(name = "script_new", length = 255)
    var scriptNew: String? = null

    /** 사용자의 로컬 디렉토리 경로 */
    @Column(name = "local_path",  length = 255)
    var localPath: String? = null

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = LocalDateTime.now()

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null

}

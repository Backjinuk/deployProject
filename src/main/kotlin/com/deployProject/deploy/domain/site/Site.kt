package com.deployProject.deploy.domain.site

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

    /** 사용자의 로컬 디렉토리 경로 */
    @Column(name = "local_path",  length = 255)
    var localPath: String? = null

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = LocalDateTime.now()

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null

    @Column(name =  "use_yn", length = 1)
    var useYn: String? = null
}

package com.deployProject.deploy.domain.deployUser

import jakarta.persistence.*
import lombok.*
import java.time.LocalDateTime

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class DeployUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id : Long = 0

    @Column(nullable = false, length = 50)
    var userName : String = "";

    @Column(name = "created_at", updatable = false)
    var createdAt : LocalDateTime = LocalDateTime.now()

}
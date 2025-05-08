package com.deployProject.deploy.domain.deployUser

import lombok.AllArgsConstructor
import lombok.Builder
import lombok.Getter
import lombok.NoArgsConstructor
import lombok.Setter

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class DeployUserDto {

    var id: Long = 0
    var userName: String = ""
    var createdAt: String = ""

}
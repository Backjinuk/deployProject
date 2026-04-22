package com.deployProject.deploy.domain.extraction

import lombok.Data

@Data
class ExtractionDto {

    var siteId : Long? = null

    var since : String? = null

    var until : String? = null

    var fileStatusType: String ?= null

    // 수정 이유: 날짜 조건만으로는 배포 범위가 넓어질 수 있어 저장소 버전 범위를 함께 받는다.
    var sinceVersion: String? = null

    var untilVersion: String? = null

    var localPath : String ?= null

    var homePath : String  ?= null

    var targetOs : TargetOsStatus ?= null
}

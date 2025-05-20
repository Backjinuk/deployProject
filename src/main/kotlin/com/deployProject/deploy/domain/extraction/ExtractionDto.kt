package com.deployProject.deploy.domain.extraction

import lombok.Data

@Data
class ExtractionDto {

    var siteId : Long? = null

    var since : String? = null

    var until : String? = null

    var fileStatusType: String ?= null

    var localPath : String ?= null

    var homePath : String  ?= null

    var targetOs : TargetOsStatus ?= null
}
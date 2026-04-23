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

    // 수정 이유: 버전 드롭다운 범위가 아닌 다중 선택 체크 방식으로 변경되어 선택 버전 목록을 직접 전달한다.
    var selectedVersions: List<String>? = null

    // 수정 이유: 선택한 버전의 변경 파일 목록 중 사용자가 체크한 파일만 추출하기 위해 전달한다.
    var selectedFiles: List<String>? = null

    // 수정 이유: 동일 파일이 여러 버전에 걸쳐 변경된 경우 파일별 기준 버전을 함께 전달한다.
    var duplicateFileVersionMap: Map<String, String>? = null

    var localPath : String ?= null

    var homePath : String  ?= null

    var targetOs : TargetOsStatus ?= null
}

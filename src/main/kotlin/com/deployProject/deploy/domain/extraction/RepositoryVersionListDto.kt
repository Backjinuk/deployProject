package com.deployProject.deploy.domain.extraction

data class RepositoryVersionListDto(
    val vcsType: String,
    val versions: List<RepositoryVersionOptionDto>
)

package com.deployProject.deploy.domain.extraction

data class RepositoryDuplicateFileDto(
    val path: String,
    val versions: List<RepositoryVersionOptionDto>
)

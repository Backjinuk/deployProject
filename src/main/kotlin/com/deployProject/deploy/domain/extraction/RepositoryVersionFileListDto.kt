package com.deployProject.deploy.domain.extraction

data class RepositoryVersionFileListDto(
    val vcsType: String,
    val files: List<String>,
    val duplicateFiles: List<RepositoryDuplicateFileDto> = emptyList()
)

package com.deployProject.deploy.domain.site

data class DirectoryPickerRequestDto(
    val currentPath: String? = null,
    val title: String? = null
)

data class DirectoryPickerResponseDto(
    val path: String? = null
)

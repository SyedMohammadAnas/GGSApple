package com.cgsapple.remotear.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelItem(
    val id: String,
    val name: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val fileSizeBytes: Long? = null,
    val description: String? = null,
)

@Serializable
data class ModelsListResponse(
    val models: List<ModelItem> = emptyList(),
)

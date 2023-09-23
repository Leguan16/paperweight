package io.papermc.paperweight.util.data.mache

import kotlinx.serialization.Serializable

@Serializable
data class MacheRepository(
    val url: String,
    val name: String,
    val groups: List<String>? = null,
)

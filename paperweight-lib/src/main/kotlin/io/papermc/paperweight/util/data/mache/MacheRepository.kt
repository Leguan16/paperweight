package io.papermc.paperweight.util.data.mache

data class MacheRepository(
    val url: String,
    val name: String,
    val groups: List<String>? = null,
)

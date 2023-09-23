package io.papermc.paperweight.util.data.mache

import kotlinx.serialization.Serializable

@Serializable
data class MavenArtifact(
    val group: String,
    val name: String,
    val version: String,
    val classifier: String? = null,
    val extension: String? = null,
)

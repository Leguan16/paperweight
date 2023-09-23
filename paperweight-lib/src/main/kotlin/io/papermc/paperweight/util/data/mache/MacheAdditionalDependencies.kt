package io.papermc.paperweight.util.data.mache

import kotlinx.serialization.Serializable

@Serializable
data class MacheAdditionalDependencies(
    val compileOnly: List<MavenArtifact>? = null,
    val implementation: List<MavenArtifact>? = null,
)

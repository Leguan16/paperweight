package io.papermc.paperweight.util.data.mache

data class MacheAdditionalDependencies(
    val compileOnly: List<MavenArtifact>? = null,
    val implementation: List<MavenArtifact>? = null,
)

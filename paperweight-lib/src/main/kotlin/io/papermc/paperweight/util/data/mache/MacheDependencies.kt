package io.papermc.paperweight.util.data.mache

import kotlinx.serialization.Serializable

@Serializable
data class MacheDependencies(
    val codebook: List<MavenArtifact>,
    val paramMappings: List<MavenArtifact>,
    val constants: List<MavenArtifact>,
    val remapper: List<MavenArtifact>,
    val decompiler: List<MavenArtifact>,
)

package io.papermc.paperweight.util.data.mache

import kotlinx.serialization.Serializable

@Serializable
data class MacheMeta(
    val version: String,
    val dependencies: MacheDependencies,
    val repositories: List<MacheRepository>,
    val decompilerArgs: List<String>,
    val additionalCompileDependencies: MacheAdditionalDependencies? = null,
)

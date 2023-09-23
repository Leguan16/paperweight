package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.tasks.mache.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import io.papermc.paperweight.util.data.mache.*
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.gradle.api.Task
import org.gradle.api.tasks.Sync

open class SoftSpoonTasks(
    val project: Project,
    tasks: TaskContainer = project.tasks
) {

    lateinit var mache: MacheMeta

    val macheCodebook by project.configurations.registering
    val macheRemapper by project.configurations.registering
    val macheDecompiler by project.configurations.registering
    val macheParamMappings by project.configurations.registering
    val macheMinecraft by project.configurations.registering

    val macheRemapJar by tasks.registering(RemapJar::class) {
        group = "softspoon"
        serverJar.set(layout.cache.resolve(SERVER_JAR_PATH))
        serverMappings.set(layout.cache.resolve(SERVER_MAPPINGS))

        codebookClasspath.from(macheCodebook)
        minecraftClasspath.from(macheMinecraft)
        remapperClasspath.from(macheRemapper)
        paramMappings.from(macheParamMappings)

        outputJar.set(layout.cache.resolve(FINAL_REMAPPED_JAR))
    }

    val macheDecompileJar by tasks.registering(DecompileJar::class) {
        group = "softspoon"
        inputJar.set(macheRemapJar.flatMap { it.outputJar })
        decompilerArgs.set(mache.decompilerArgs)

        minecraftClasspath.from(macheMinecraft)
        decompiler.from(macheDecompiler)

        outputJar.set(layout.cache.resolve(FINAL_DECOMPILE_JAR))
    }

    val macheSetupSources by tasks.registering(SetupSources::class) {
        group = "softspoon"
        decompJar.set(macheDecompileJar.flatMap { it.outputJar })
        // Don't use the output of applyPatches directly with a flatMap
        // That would tell Gradle that this task dependsOn applyPatches, so it
        // would no longer work as a finalizer task if applyPatches fails
        patchedJar.set(layout.cache.resolve(PATCHED_JAR))
        failedPatchJar.set(layout.cache.resolve(FAILED_PATCH_JAR))

        sourceDir.set(layout.projectDirectory.dir("Paper-Server/src/vanilla/java"))
    }

    val macheApplyPatches by tasks.registering(ApplyPatches::class) {
        group = "softspoon"
        description = "Apply decompilation patches to the source."

        mache.from(project.configurations.named(MACHE_CONFIG))

        useNativeDiff.set(project.providers.gradleProperty("useNativeDiff").map { it.toBoolean() }.orElse(false))
        project.providers.gradleProperty("patchExecutable").let { ex ->
            if (ex.isPresent) {
                patchExecutable.set(ex)
            }
        }

        inputFile.set(macheDecompileJar.flatMap { it.outputJar })
        outputJar.set(layout.cache.resolve(PATCHED_JAR))
        failedPatchesJar.set(layout.cache.resolve(FAILED_PATCH_JAR))

        finalizedBy(macheSetupSources)
    }

    val macheCopyResources by tasks.registering(Sync::class) {
        group = "softspoon"
        into(project.layout.projectDirectory.dir("Paper-Server/src/vanilla/resources"))
        from(project.zipTree(project.layout.cache.resolve(SERVER_JAR_PATH))) {
            exclude("**/*.class", "META-INF/**")
        }
        includeEmptyDirs = false
    }

    val setup by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Set up the full project included patched sources and resources."
        dependsOn(macheApplyPatches, macheCopyResources)
    }

    fun afterEvaluate() {
        val download = project.download.get()

        mache = this.project.configurations.named(MACHE_CONFIG).get().singleFile.toPath().openZip().use { zip ->
            return@use gson.fromJson<MacheMeta>(zip.getPath("/mache.json").readLines().joinToString("\n"))
        }
        println("Loading mache ${mache.version}")

        // download manifests
        val mcManifestFile = project.layout.cache.resolve(MC_MANIFEST)
        download.download(MC_MANIFEST_URL, mcManifestFile)
        val mcManifest = gson.fromJson<MinecraftManifest>(mcManifestFile)

        val mcVersionManifestFile = project.layout.cache.resolve(VERSION_JSON)
        val mcVersion = mcManifest.versions.firstOrNull { it.id == mache.version }
            ?: throw RuntimeException("Unknown Minecraft version ${mache.version}")
        download.download(mcVersion.url, mcVersionManifestFile, Hash(mcVersion.sha1, HashingAlgorithm.SHA1))
        val mcVersionManifest = gson.fromJson<MinecraftVersionManifest>(mcVersionManifestFile)

        val bundleJar = project.layout.cache.resolve(BUNDLE_JAR_PATH)
        val serverJar = project.layout.cache.resolve(SERVER_JAR_PATH)
        // download bundle and mappings
        runBlocking {
            awaitAll(
                download.downloadAsync(
                    mcVersionManifest.downloads["server"]!!.url,
                    bundleJar,
                    Hash(mcVersionManifest.downloads["server"]!!.sha1, HashingAlgorithm.SHA1),
                ),
                download.downloadAsync(
                    mcVersionManifest.downloads["server_mappings"]!!.url,
                    project.layout.cache.resolve(SERVER_MAPPINGS),
                    Hash(mcVersionManifest.downloads["server_mappings"]!!.sha1, HashingAlgorithm.SHA1),
                ),
            )
        }

        // extract bundle
        val libs = bundleJar.openZip().use { zip ->
            val versions = zip.getPath("META-INF", "versions.list").readLines()
                .map { it.split('\t') }
                .associate { it[1] to it[2] }
            val serverJarPath = zip.getPath("META-INF", "versions", versions[mache.version])
            serverJarPath.copyTo(serverJar, true)

            val librariesList = zip.getPath("META-INF", "libraries.list")

            return@use librariesList.useLines { lines ->
                return@useLines lines.map { line ->
                    val parts = line.split(whitespace)
                    if (parts.size != 3) {
                        throw Exception("libraries.list file is invalid")
                    }
                    return@map parts[1]
                }.toList()
            }
        }

        // setup repos
        this.project.repositories {
            for (repository in mache.repositories) {
                maven(repository.url) {
                    name = repository.name
                    mavenContent {
                        for (group in repository.groups ?: listOf()) {
                            includeGroupAndSubgroups(group)
                        }
                    }
                }
            }

            maven("https://libraries.minecraft.net/") {
                name = "Minecraft"
            }
            mavenCentral()
        }

        this.project.dependencies {
            // setup mc deps
            libs.forEach {
                "macheMinecraft"(it)
            }

            // setup mache deps
            mache.dependencies.codebook.forEach {
                "macheCodebook"("${it.group}:${it.name}:${it.version}")
            }
            mache.dependencies.paramMappings.forEach {
                "macheParamMappings"("${it.group}:${it.name}:${it.version}")
            }
            mache.dependencies.remapper.forEach {
                "macheRemapper"("${it.group}:${it.name}:${it.version}")
            }
            mache.dependencies.decompiler.forEach {
                "macheDecompiler"("${it.group}:${it.name}:${it.version}")
            }
        }
    }
}
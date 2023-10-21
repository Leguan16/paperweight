package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.ext
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mache.*
import io.papermc.paperweight.tasks.mache.RemapJar
import io.papermc.paperweight.tasks.patchremapv2.GeneratePatchRemapMappings
import io.papermc.paperweight.tasks.patchremapv2.RemapCBPatches
import io.papermc.paperweight.tasks.softspoon.ApplyPatches
import io.papermc.paperweight.tasks.softspoon.ApplyPatchesFuzzy
import io.papermc.paperweight.tasks.softspoon.RebuildPatches
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.*
import io.papermc.paperweight.util.data.mache.*
import java.nio.file.Files
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet

open class SoftSpoonTasks(
    val project: Project,
    val allTasks: AllTasks,
    tasks: TaskContainer = project.tasks
) {

    lateinit var mache: MacheMeta

    val macheCodebook by project.configurations.registering
    val macheRemapper by project.configurations.registering
    val macheDecompiler by project.configurations.registering
    val macheParamMappings by project.configurations.registering
    val macheMinecraft by project.configurations.registering

    val macheRemapJar by tasks.registering(RemapJar::class) {
        group = "mache"
        serverJar.set(layout.cache.resolve(SERVER_JAR_PATH))
        serverMappings.set(allTasks.downloadMappings.flatMap { it.outputFile })

        codebookClasspath.from(macheCodebook)
        minecraftClasspath.from(macheMinecraft)
        remapperClasspath.from(macheRemapper)
        paramMappings.from(macheParamMappings)

        outputJar.set(layout.cache.resolve(FINAL_REMAPPED_CODEBOOK_JAR))
    }

    val macheDecompileJar by tasks.registering(DecompileJar::class) {
        group = "mache"
        inputJar.set(macheRemapJar.flatMap { it.outputJar })
        decompilerArgs.set(mache.decompilerArgs)

        minecraftClasspath.from(macheMinecraft)
        decompiler.from(macheDecompiler)

        outputJar.set(layout.cache.resolve(FINAL_DECOMPILE_JAR))
    }

    val setupMacheSources by tasks.registering(SetupVanilla::class) {
        group = "mache"
        description = "Setup vanilla source dir."

        inputFile.set(macheDecompileJar.flatMap { it.outputJar })
        predicate.set { Files.isRegularFile(it) && it.toString().endsWith(".java")}
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))
    }

    val setupMacheResources by tasks.registering(SetupVanilla::class) {
        group = "mache"
        description = "Setup vanilla resources dir"

        inputFile.set(macheDecompileJar.flatMap { it.outputJar })
        predicate.set { Files.isRegularFile(it) && !it.toString().endsWith(".java")}
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("resources"))
    }

    val applyMachePatches by tasks.registering(ApplyMachePatches::class) {
        group = "mache"
        description = "Applies patches to the vanilla sources"

        mache.from(project.configurations.named(MACHE_CONFIG))

        input.set(setupMacheSources.flatMap { it.outputDir })
        output.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))
        patches.set(layout.cache.resolve(PATCHES_FOLDER))
    }

    val applySourcePatches by tasks.registering(ApplyPatches::class) {
        group = "softspoon"
        description = "Applies patches to the vanilla sources"

        input.set(applyMachePatches.flatMap { it.output })
        output.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/java") })
        patches.set(project.layout.projectDirectory.dir("patches/sources"))
    }

    val applySourcePatchesFuzzy by tasks.registering(ApplyPatchesFuzzy::class) {
        group = "softspoon"
        description = "Applies patches to the vanilla sources"

        input.set(applyMachePatches.flatMap { it.output })
        output.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/java") })
        patches.set(project.layout.projectDirectory.dir("patches/sources"))
    }

    val applyResourcePatches by tasks.registering(ApplyPatches::class) {
        group = "softspoon"
        description = "Applies patches to the vanilla resources"

        input.set(setupMacheResources.flatMap { it.outputDir })
        output.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/resources") })
        patches.set(project.layout.projectDirectory.dir("patches/resources"))
    }

    val applyPatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Applies all patches"
        dependsOn(applySourcePatches, applyResourcePatches)
    }

    val rebuildSourcePatches by tasks.registering(RebuildPatches::class) {
        group = "softspoon"
        description = "Rebuilds patches to the vanilla sources"

        base.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))
        input.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/java") })
        patches.set(project.layout.projectDirectory.dir("patches/sources"))
    }

    val rebuildResourcePatches by tasks.registering(RebuildPatches::class) {
        group = "softspoon"
        description = "Rebuilds patches to the vanilla resources"

        base.set(layout.cache.resolve(BASE_PROJECT).resolve("resources"))
        input.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/resources") })
        patches.set(project.layout.projectDirectory.dir("patches/resources"))
    }

    val rebuildPatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Rebuilds all patches"
        dependsOn(rebuildSourcePatches, rebuildResourcePatches)
    }

    // patch remap stuff
    val macheSpigotDecompileJar by tasks.registering<SpigotDecompileJar> {
        group = "patchremap"
        inputJar.set(macheRemapJar.flatMap { it.outputJar })
        fernFlowerJar.set(project.ext.craftBukkit.fernFlowerJar)
        decompileCommand.set(allTasks.buildDataInfo.map { it.decompileCommand })
    }

    val generatePatchRemapMappings by tasks.registering(GeneratePatchRemapMappings::class) {
        group = "patchremap"

        minecraftClasspath.from(macheMinecraft)
        serverJar.set(macheRemapJar.flatMap { it.outputJar })
        paramMappings.from(macheParamMappings)
        vanillaMappings.set(allTasks.downloadMappings.flatMap { it.outputFile })
        spigotMappings.set(allTasks.generateSpigotMappings.flatMap { it.notchToSpigotMappings })

        patchRemapMappings.set(layout.cache.resolve(SPIGOT_MOJANG_PARCHMENT_MAPPINGS))
    }

    val remapCBPatches by tasks.registering(RemapCBPatches::class) {
        group = "patchremap"
        base.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))
        //craftBukkit.set(allTasks.patchCraftBukkit.flatMap { it.outputDir })
        craftBukkit.set(project.layout.cache.resolve("paperweight/taskCache/patchCraftBukkit.repo"))
        outputPatchDir.set(project.layout.projectDirectory.dir("patches/remapped-cb"))
        //mappingsFile.set(allTasks.patchMappings.flatMap { it.outputMappings })
        mappingsFile.set(layout.cache.resolve(SPIGOT_MOJANG_PARCHMENT_MAPPINGS))
    }

    fun afterEvaluate() {
        val download = project.download.get()

        // todo all this should be a dependency resolution hook like
        // https://github.com/PaperMC/paperweight/blob/88edb5ce16a794cd72d33c0a750fd9f334e5677f/paperweight-userdev/src/main/kotlin/io/papermc/paperweight/userdev/PaperweightUser.kt#L237-L246
        // https://discord.com/channels/289587909051416579/776169605760286730/1155170101599940698
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
                            includeGroupByRegex(group + ".*")
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

        this.project.ext.serverProject.get().setupServerProject(mache, libs);
    }

    private fun Project.setupServerProject(mache: MacheMeta, libs: List<String>) {
        if (!projectDir.exists()) {
            return
        }

        // minecraft deps
        val macheMinecraft by configurations.creating {
            withDependencies {
                dependencies {
                    // setup mc deps
                    libs.forEach {
                        "macheMinecraft"(it)
                    }
                }
            }
        }

        // impl extends minecraft
        configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME) {
            extendsFrom(macheMinecraft)
        }

        // add vanilla source set
        the<JavaPluginExtension>().sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
            java {
                srcDirs(projectDir.resolve("src/vanilla/java"))
            }
            resources {
                srcDirs(projectDir.resolve("src/vanilla/resources"))
            }
        }
    }
}

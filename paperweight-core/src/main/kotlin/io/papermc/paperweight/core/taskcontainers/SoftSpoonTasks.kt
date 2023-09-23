package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.tasks.mache.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

open class SoftSpoonTasks(
    val project: Project,
    tasks: TaskContainer = project.tasks,
    val allTasks: AllTasks,
) {

    val macheCodebook by project.configurations.registering
    val macheRemapper by project.configurations.registering
    val macheDecompiler by project.configurations.registering
    val macheParamMappings by project.configurations.registering
    val macheMinecraft by project.configurations.registering

    val macheRemapJar by tasks.registering(RemapJar::class) {
        group = "softspoon"
        serverJar.set(allTasks.extractFromBundler.flatMap { it.serverJar })
        serverMappings.set(allTasks.downloadMappings.flatMap { it.outputFile })

        codebookClasspath.from(macheCodebook)
        minecraftClasspath.from(macheMinecraft)
        remapperClasspath.from(macheRemapper)
        paramMappings.from(macheParamMappings)

        outputJar.set(layout.buildDirectory.file("out.jar"))
    }

    fun afterEvaluate() {
        val mache = this.project.configurations.named(MACHE_CONFIG).get().singleFile.toPath().openZip().use { zip ->
            return@use gson.fromJson<MacheMeta>(zip.getPath("/mache.json").readLines().joinToString("\n"))
        }
        println("Loaded mache $mache")

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
            // setup mc deps // TODO how do we load these libs?
            //val libs = allTasks.extractFromBundler.map { it.serverLibrariesTxt }.get().get().asFile.readLines()
            //println("libs $libs")

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

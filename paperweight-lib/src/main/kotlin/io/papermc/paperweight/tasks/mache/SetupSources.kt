package io.papermc.paperweight.tasks.mache

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class SetupSources : DefaultTask() {

    @get:InputFile
    abstract val decompJar: RegularFileProperty

    @get:InputFile
    abstract val patchedJar: RegularFileProperty

    @get:InputFile
    abstract val failedPatchJar: RegularFileProperty

    @get:OutputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:Inject
    abstract val layout: ProjectLayout

    @get:Inject
    abstract val files: FileOperations

    @TaskAction
    fun run() {
        files.sync {
            from(files.zipTree(decompJar))
            into(sourceDir)
            // ignore resources here, we gonna handle them separately
            exclude { !it.name.endsWith(".java") }
            includeEmptyDirs = false
        }

        files.sync {
            from(files.zipTree(patchedJar))
            into(sourceDir)
            includeEmptyDirs = false
        }

        files.copy {
            from(files.zipTree(failedPatchJar))
            into(sourceDir)
            includeEmptyDirs = false
        }
    }
}

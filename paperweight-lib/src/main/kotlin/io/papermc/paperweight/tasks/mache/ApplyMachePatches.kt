package io.papermc.paperweight.tasks.mache

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.patches.*
import io.papermc.paperweight.util.patches.NativePatcher
import javax.inject.Inject
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.relativeTo
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

@UntrackedTask(because = "Always apply patches")
abstract class ApplyMachePatches : DefaultTask() {

    @get:Classpath
    abstract val mache: ConfigurableFileCollection

    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:Internal
    abstract val useNativeDiff: Property<Boolean>

    @get:Internal
    abstract val patchExecutable: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:OutputFile
    abstract val failedPatchesJar: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val files: FileOperations

    @get:Inject
    abstract val layout: ProjectLayout

    init {
        run {
            useNativeDiff.convention(false)
            patchExecutable.convention("patch")
        }
    }

    @TaskAction
    fun run() {
        val patchesFolder = layout.cache.resolve(PATCHES_FOLDER).ensureClean()

        mache.singleFile.toPath().openZip().use { zip ->
            zip.getPath("patches").copyRecursivelyTo(patchesFolder)
        }

        val out = outputJar.convertToPath().ensureClean()
        val failed = failedPatchesJar.convertToPath().ensureClean()

        val tempInDir = out.resolveSibling(".tmp_applyPatches_input").ensureClean()
        tempInDir.createDirectory()
        val tempOutDir = out.resolveSibling(".tmp_applyPatches_output").ensureClean()
        tempOutDir.createDirectory()
        val tempFailedPatchDir = out.resolveSibling(".tmp_applyPatches_failed").ensureClean()
        tempFailedPatchDir.createDirectory()

        try {
            files.sync {
                from(files.zipTree(inputFile))
                into(tempInDir)
            }

            val result = createPatcher().applyPatches(tempInDir, patchesFolder, tempOutDir, tempFailedPatchDir)

            out.writeZipStream { zos ->
                failed.writeZipStream { failedZos ->
                    inputFile.convertToPath().readZipStream { zis, zipEntry ->
                        if (zipEntry.name.endsWith(".java")) {
                            val patchedFile = tempOutDir.resolve(zipEntry.name)
                            if (patchedFile.exists()) {
                                patchedFile.inputStream().buffered().use { input ->
                                    copyEntry(input, zos, zipEntry)
                                }
                            }
                            val failedPatch = tempFailedPatchDir.resolve(zipEntry.name)
                            if (failedPatch.exists()) {
                                failedPatch.inputStream().buffered().use { input ->
                                    copyEntry(input, failedZos, zipEntry)
                                }
                            }
                        }
                    }
                }
            }

            if (result is PatchFailure) {
                result.failures
                    .map { "Patch failed: ${it.patch.relativeTo(patchesFolder)}: ${it.details}" }
                    .forEach { logger.error(it) }
                throw Exception("Failed to apply ${result.failures.size} patches")
            }
        } finally {
            tempInDir.deleteRecursively()
            tempOutDir.deleteRecursively()
            tempFailedPatchDir.deleteRecursively()
        }
    }

    internal open fun createPatcher(): Patcher {
        return if (useNativeDiff.get()) {
            NativePatcher(exec, patchExecutable.get())
        } else {
            JavaPatcher()
        }
    }
}

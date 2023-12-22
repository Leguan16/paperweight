package io.papermc.paperweight.tasks.softspoon

import atFromString
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CollectATsFromPatches: BaseTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    abstract val patchDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    override fun init() {
        outputFile.convention(defaultOutput("at"))
    }

    @TaskAction
    fun run() {
        val output = outputFile.convertToPath().ensureClean()

        val ats = AccessTransformSet.create()

        println("reading ${patchDir.convertToPath()}")

        // TODO recursive I guess
        patchDir.convertToPath().listDirectoryEntries("*.patch").forEach { patch ->
            println("check $patch")
            patch.useLines {
                var atClass: AccessTransformSet.Class? = null
                for (line in it) {
                    if (line.startsWith("AT: ")) {
                        val at = atFromString(line.replaceFirst("AT: ", ""))
                        if (atClass == null) {
                            atClass = ats.getOrCreateClass(patch.nameWithoutExtension) // TODO proper class name
                        }
                        println("found at $at in $patch ($atClass)")
                        atClass?.merge(at)
                    } else if (line.startsWith("====")) {
                        break
                    }
                }
            }
        }

        println("ATS: $ats")

        AccessTransformFormats.FML.write(output, ats)
    }
}

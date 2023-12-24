package io.papermc.paperweight.tasks.softspoon

import atFromString
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.signature.MethodSignature
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
@OptIn(ExperimentalPathApi::class)
abstract class CollectATsFromPatches : BaseTask() {

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

        patchDir.convertToPath()
            .walk(PathWalkOption.INCLUDE_DIRECTORIES)
            .filter { p -> p.toString().endsWith(".patch") }
            .forEach { patch ->
                patch.useLines {
                    var atClass: AccessTransformSet.Class? = null
                    for (line in it) {
                        if (line.startsWith("AT: ")) {
                            var atLine = line.replaceFirst("AT: ", "")
                            val segments = atLine.split(" ")
                            val parsedAt = atFromString(segments.first())
                            if (atClass == null) {
                                // TODO this doesn't account for inner classes :/
                                atClass = ats.getOrCreateClass(patch.relativeTo(patchDir.convertToPath()).toString().replace(".java.patch", "").replace("/", ".").replace("\\", "."))
                            }

                            if (segments.size < 2) {
                                println("invalid at line: $line")
                                continue
                            }

                            if (segments[1].contains("(")) {
                                var methodName = segments[1].substringBefore("(")
                                var methodDescriptor = "(" + segments[1].substringAfter("(")
                                atClass?.replaceMethod(MethodSignature(methodName, MethodDescriptor.of(methodDescriptor)), parsedAt)
                            } else {
                                atClass?.replaceField(segments[1], parsedAt)
                            }
                        } else if (line.startsWith("====")) {
                            break
                        }
                    }
                }
            }

        AccessTransformFormats.FML.write(output, ats)
    }
}

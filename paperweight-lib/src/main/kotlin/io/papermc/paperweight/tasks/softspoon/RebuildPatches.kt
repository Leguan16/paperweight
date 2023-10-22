package io.papermc.paperweight.tasks.softspoon

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import io.papermc.paperweight.util.*
import io.papermc.restamp.Restamp
import io.papermc.restamp.RestampContextConfiguration
import io.papermc.restamp.RestampInput
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.ModifierChange
import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.Type
import org.cadixdev.bombe.type.signature.MethodSignature
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.openrewrite.InMemoryExecutionContext

@UntrackedTask(because = "Always rebuild patches")
abstract class RebuildPatches : DefaultTask() {

    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:InputDirectory
    abstract val base: DirectoryProperty

    @get:InputDirectory
    abstract val patches: DirectoryProperty

    @get:Optional
    @get:CompileClasspath
    abstract val minecraftClasspath: ConfigurableFileCollection

    @get:Input
    abstract val contextLines: Property<Int>

    @get:Inject
    abstract val layout: ProjectLayout

    init {
        run {
            contextLines.convention(3)
        }
    }

    @TaskAction
    fun run() {
        val patchDir = patches.convertToPath().ensureClean()
        patchDir.createDirectory()
        val inputDir = input.convertToPath()
        val baseDir = base.convertToPath()

        val patchesCreated = baseDir.walk()
            .map { it.relativeTo(baseDir).toString().replace("\\", "/") }
            .filter {
                !it.startsWith(".git") && !it.endsWith(".nbt") && !it.endsWith(".mcassetsroot")
            }
            .sumOf {
                diffFile(inputDir, baseDir, it, patchDir)
            }

        logger.lifecycle("Rebuilt $patchesCreated patches")
    }

    private fun diffFile(sourceRoot: Path, decompRoot: Path, relativePath: String, patchDir: Path): Int {
        val source = sourceRoot.resolve(relativePath)
        val decomp = decompRoot.resolve(relativePath)

        val className = relativePath.toString().replace(".java", "")

        var sourceLines = source.readLines(Charsets.UTF_8)
        var decompLines = decomp.readLines(Charsets.UTF_8)

        val newAts = AccessTransformSet.create()

        sourceLines = handleATInSource(sourceLines, newAts, className, source)
        decompLines = handleATInBase(decompLines, newAts, decompRoot, decomp)


        val patch = DiffUtils.diff(decompLines, sourceLines)
        if (patch.deltas.isEmpty()) {
            return 0
        }

        val unifiedPatch = UnifiedDiffUtils.generateUnifiedDiff(
            "a/$relativePath",
            "b/$relativePath",
            decompLines,
            patch,
            contextLines.get(),
        )

        val atHeader = mutableListOf<String>()

        // TODO load existing ATs for this file into the list

        if (newAts.classes.isNotEmpty()) {
            val atClass = newAts.getClass(className).get()
            atClass.methods.forEach {
                atHeader.add("AT: ${atToString(it.value)} ${it.key.name}${it.key.descriptor}")
            }
            atClass.fields.forEach {
                atHeader.add("AT: ${atToString(it.value)} ${it.key}")
            }
            atHeader.add("===================================================================\n")
        }

        val patchFile = patchDir.resolve("$relativePath.patch")
        patchFile.parent.createDirectories()
        patchFile.writeText(atHeader.joinToString("\n") + unifiedPatch.joinToString("\n", postfix = "\n"), Charsets.UTF_8)

        return 1
    }

    private fun handleATInBase(decompLines: List<String>, newAts: AccessTransformSet, decompRoot: Path, decomp: Path): List<String> {
        if (newAts.classes.isEmpty()) {
            return decompLines
        }

        val configuration = RestampContextConfiguration.builder()
            .accessTransformSet(newAts)
            .sourceRoot(decompRoot)
            .sourceFiles(listOf(decomp))
            .classpath(minecraftClasspath.files.map { it.toPath() })
            .executionContext(InMemoryExecutionContext { it.printStackTrace() })
            .build()

        val parsedInput = RestampInput.parseFrom(configuration)
        val results = Restamp.run(parsedInput).allResults

        if (results.size != 1) {
            throw Exception("Strange resultset?! " + results)
        }

        val result = results[0].after?.printAll()
        if (result != null) {
            decomp.writeText(result, Charsets.UTF_8)
        }
        return decomp.readLines(Charsets.UTF_8)
    }

    private fun handleATInSource(sourceLines: List<String>, newAts: AccessTransformSet, className: String, source: Path): ArrayList<String> {
        val fixedLines = ArrayList<String>(sourceLines.size)
        sourceLines.forEach { line ->
            if (!line.contains("// Paper-AT: ")) {
                fixedLines.add(line)
                return@forEach
            }

            val split = line.split("// Paper-AT: ")
            val at = split[1]
            val atClass = newAts.getOrCreateClass(className)
            val parts = at.split(" ")
            val accessTransform = atFromString(parts[0])
            val name = parts[1]
            val index = name.indexOf('(')
            if (index == -1) {
                atClass.mergeField(name, accessTransform)
            } else {
                atClass.mergeMethod(MethodSignature.of(name.substring(0, index), name.substring(index)), accessTransform)
            }

            fixedLines.add(split[0])
        }

        // TODO source.writeLines(fixedLines, Charsets.UTF_8)

        return fixedLines
    }

    private fun atFromString(input: String): AccessTransform {
        var last = input.length - 1

        val final: ModifierChange
        if (input[last] == 'f') {
            final = if (input[--last] == '-') ModifierChange.REMOVE else ModifierChange.ADD
        } else {
            final = ModifierChange.NONE
        }

        val access = when (input.substring(0, last)) {
            "public" -> AccessChange.PUBLIC
            "protected" -> AccessChange.PROTECTED
            "private" -> AccessChange.PRIVATE
            else -> AccessChange.NONE
        }

        return AccessTransform.of(access, final)
    }

    private fun atToString(at: AccessTransform): String {
        val access = when (at.access) {
            AccessChange.PRIVATE -> "private"
            AccessChange.PROTECTED -> "protected"
            AccessChange.PUBLIC -> "public"
            else -> ""
        }
        val final = when (at.final) {
            ModifierChange.REMOVE -> "-f"
            ModifierChange.ADD -> "+f"
            else -> ""
        }
        return access + final
    }
}

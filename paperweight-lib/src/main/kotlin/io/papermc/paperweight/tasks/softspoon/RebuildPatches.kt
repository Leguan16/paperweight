package io.papermc.paperweight.tasks.softspoon

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import io.papermc.paperweight.util.*
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@UntrackedTask(because = "Always rebuild patches")
abstract class RebuildPatches : DefaultTask() {

    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:InputDirectory
    abstract val base: DirectoryProperty

    @get:InputDirectory
    abstract val patches: DirectoryProperty

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

        val sourceLines = source.readLines(Charsets.UTF_8)
        val decompLines = decomp.readLines(Charsets.UTF_8)

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

        val patchFile = patchDir.resolve("$relativePath.patch")
        patchFile.parent.createDirectories()
        patchFile.writeText(unifiedPatch.joinToString("\n", postfix = "\n"), Charsets.UTF_8)

        return 1
    }
}

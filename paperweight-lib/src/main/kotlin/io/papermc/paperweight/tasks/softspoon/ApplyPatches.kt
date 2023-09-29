package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.patches.*
import io.papermc.paperweight.util.patches.JavaPatcher
import io.papermc.paperweight.util.patches.NativePatcher
import io.papermc.paperweight.util.patches.Patcher
import javax.inject.Inject
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

@UntrackedTask(because = "Always apply patches")
abstract class ApplyPatches: DefaultTask() {

    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:InputDirectory
    abstract val patches: DirectoryProperty

    @get:Internal
    abstract val useNativeDiff: Property<Boolean>

    @get:Internal
    abstract val patchExecutable: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    init {
        run {
            useNativeDiff.convention(false)
            patchExecutable.convention("patch")
        }
    }

    @TaskAction
    fun run() {
        val git = Git.open(input.convertToPath().toFile());
        val tags = git.tagList().call()
        val initialTag = tags.firstOrNull { it.name == "refs/tags/$PATCHED_TAG" }
        if (initialTag != null) {
            git.tagDelete()
                .setTags(initialTag.name)
                .call()

            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef("mache")
                .call()
        }

        val result = createPatcher().applyPatches(input.get().path, patches.get().path, input.get().path, input.get().path)

        if (result is PatchFailure) {
            result.failures
                .map { "Patch failed: ${it.patch.relativeTo(patches.get().path)}: ${it.details}" }
                .forEach { logger.error(it) }
            throw Exception("Failed to apply ${result.failures.size} patches")
        }

        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage("Patched")
            .setAuthor(macheIdent)
            .setSign(false)
            .call()
        git.tag().setName(PATCHED_TAG).setTagger(macheIdent).setSigned(false).call()
    }

    internal open fun createPatcher(): Patcher {
        return if (useNativeDiff.get()) {
            NativePatcher(exec, patchExecutable.get())
        } else {
            JavaPatcher()
        }
    }

    companion object {
        private const val PATCHED_TAG = "patched"

        private val macheIdent = PersonIdent("Papier-mâché", "paper@mache.gradle")
    }
}

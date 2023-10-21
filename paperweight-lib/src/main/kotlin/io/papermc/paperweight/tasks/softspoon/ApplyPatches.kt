package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.patches.*
import javax.inject.Inject
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

@UntrackedTask(because = "Always apply patches")
abstract class ApplyPatches : DefaultTask() {

    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:InputDirectory
    abstract val patches: DirectoryProperty

    @get:Internal
    abstract val useNativeDiff: Property<Boolean>

    @get:Internal
    abstract val patchExecutable: Property<String>

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val layout: ProjectLayout

    init {
        run {
            useNativeDiff.convention(false)
            patchExecutable.convention("patch")
        }
    }

    @TaskAction
    open fun run() {
        setup()

        val result = createPatcher().applyPatches(output.convertToPath(), patches.convertToPath(), output.convertToPath(), output.convertToPath())

        commit()

        if (result is PatchFailure) {
            result.failures
                .map { "Patch failed: ${it.patch.relativeTo(patches.get().path)}: ${it.details}" }
                .forEach { logger.error(it) }
            throw Exception("Failed to apply ${result.failures.size} patches")
        }
    }

    open fun setup() {
        output.convertToPath().ensureClean()
        Git.cloneRepository().setBranch("main").setURI("file://" + input.convertToPath().toString()).setDirectory(output.convertToPath().toFile()).call()
    }

    open fun commit() {
        val ident = PersonIdent("File", "filepatches@automated.papermc.io")
        val git = Git.open(output.convertToPath().toFile())
        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage("File Patches")
            .setAuthor(ident)
            .setSign(false)
            .call()
        git.tag().setName("file").setTagger(ident).setSigned(false).call()
    }

    internal open fun createPatcher(): Patcher {
        return if (useNativeDiff.get()) {
            NativePatcher(exec, patchExecutable.get())
        } else {
            JavaPatcher()
        }
    }
}

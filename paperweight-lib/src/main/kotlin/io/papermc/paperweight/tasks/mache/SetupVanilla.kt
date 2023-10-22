package io.papermc.paperweight.tasks.mache

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.patches.*
import java.nio.file.Path
import java.util.function.Predicate
import javax.inject.Inject
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

@CacheableTask
abstract class SetupVanilla : BaseTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:Input
    abstract val predicate: Property<Predicate<Path>>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val patches: DirectoryProperty

    @get:Optional
    @get:Classpath
    abstract val mache: ConfigurableFileCollection

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
        val path = outputDir.convertToPath().ensureClean()

        // copy initial sources
        inputFile.convertToPath().openZip().walk()
            .filter(predicate.get())
            .forEach {
                val target = path.resolve(it.toString().substring(1))
                target.parent.createDirectories()
                it.copyTo(target, true)
            }

        // setup git repo
        val vanillaIdent = PersonIdent("Vanilla", "vanilla@papermc.io")

        val git = Git.init()
            .setDirectory(path.toFile())
            .setInitialBranch("main")
            .call()
        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage("Vanilla")
            .setAuthor(vanillaIdent)
            .setSign(false)
            .call()
        git.tag().setName("vanilla").setTagger(vanillaIdent).setSigned(false).call()

        if (patches.isPresent()) {
            // prepare for patches for patching
            val patchesFolder = patches.convertToPath().ensureClean()

            mache.singleFile.toPath().openZip().use { zip ->
                zip.getPath("patches").copyRecursivelyTo(patchesFolder)
            }

            // patch
            val result = createPatcher().applyPatches(path, patches.convertToPath(), path, path)

            val macheIdent = PersonIdent("Mache", "mache@automated.papermc.io")
            git.add().addFilepattern(".").call()
            git.tag().setName("mache").setTagger(macheIdent).setSigned(false).call()
            git.commit()
                .setMessage("Mache")
                .setAuthor(macheIdent)
                .setSign(false)
                .call()

            if (result is PatchFailure) {
                result.failures
                    .map { "Patch failed: ${it.patch.relativeTo(patches.get().path)}: ${it.details}" }
                    .forEach { logger.error(it) }
                git.close()
                throw Exception("Failed to apply ${result.failures.size} patches")
            }
        }

        git.close()
    }

    internal open fun createPatcher(): Patcher {
        return if (useNativeDiff.get()) {
            NativePatcher(exec, patchExecutable.get())
        } else {
            JavaPatcher()
        }
    }
}

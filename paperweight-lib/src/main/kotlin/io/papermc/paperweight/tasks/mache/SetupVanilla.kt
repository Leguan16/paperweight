package io.papermc.paperweight.tasks.mache

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import java.util.function.Predicate
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

@CacheableTask
abstract class SetupVanilla : BaseTask() {

    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:Internal
    abstract val predicate: Property<Predicate<Path>>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

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
    }
}

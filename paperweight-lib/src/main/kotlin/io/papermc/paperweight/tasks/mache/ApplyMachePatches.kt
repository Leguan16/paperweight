package io.papermc.paperweight.tasks.mache

import io.papermc.paperweight.tasks.softspoon.ApplyPatches
import io.papermc.paperweight.util.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.UntrackedTask

@CacheableTask
abstract class ApplyMachePatches : ApplyPatches() {

    @get:Internal
    abstract override val patches: DirectoryProperty

    @get:Classpath
    abstract val mache: ConfigurableFileCollection

    override fun setup() {
        // prepare for patches for patching
        val patchesFolder = patches.convertToPath().ensureClean()

        mache.singleFile.toPath().openZip().use { zip ->
            zip.getPath("patches").copyRecursivelyTo(patchesFolder)
        }
    }

    override fun commit() {
        val macheIdent = PersonIdent("Mache", "mache@automated.papermc.io")
        val git = Git.open(output.convertToPath().toFile())
        git.add().addFilepattern(".").call()
        git.tag().setName("mache").setTagger(macheIdent).setSigned(false).call()
        git.commit()
            .setMessage("Mache")
            .setAuthor(macheIdent)
            .setSign(false)
            .call()
    }
}

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.data.mache.*
import kotlin.io.path.*
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction

abstract class MacheTask : DefaultTask() {

    @get:Classpath
    abstract val mache: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        val metaJson = mutableListOf("");
        mache.singleFile.toPath().openZip().use { zip ->
            metaJson.addAll(zip.getPath("mache.json").readLines())
        }

        val meta = json.decodeFromString<MacheMeta>(metaJson.joinToString { "\n" })
        println("loaded meta: $meta");
    }
}



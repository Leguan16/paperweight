package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import org.gradle.api.Project
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

open class SoftSpoonTasks(
    project: Project,
    tasks: TaskContainer = project.tasks,
) {

    val mache by tasks.registering<MacheTask> {
        group = "softspoon"
        mache.from(project.configurations.named(MACHE_CONFIG))
    }
}

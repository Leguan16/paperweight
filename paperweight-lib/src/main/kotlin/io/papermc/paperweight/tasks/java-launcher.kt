/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.tasks

import io.papermc.paperweight.util.*
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested
import org.gradle.jvm.toolchain.JavaLauncher

interface JavaLauncherTaskBase {
    @get:Nested
    val launcher: Property<JavaLauncher>
}

abstract class JavaLauncherTask : BaseTask(), JavaLauncherTaskBase {

    override fun init() {
        super.init()

        launcher.convention(project.defaultJavaLauncher())
    }
}

abstract class JavaLauncherZippedTask : ZippedTask(), JavaLauncherTaskBase {

    override fun init() {
        super.init()

        launcher.convention(project.defaultJavaLauncher())
    }
}

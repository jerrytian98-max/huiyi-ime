// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.dsl.ApplicationExtension
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.task
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.jetbrains.kotlin.com.google.common.hash.Hashing
import org.jetbrains.kotlin.com.google.common.io.ByteSource
import java.io.File
import java.nio.charset.Charset
import kotlin.collections.set

/**
 * Add task generateDataChecksums
 */
class DataChecksumsPlugin : Plugin<Project> {
    companion object {
        const val TASK = "generateDataChecksums"
        const val CLEAN_TASK = "cleanDatacheksums"
        const val FILE_NAME = "checksums.json"
        const val PREPARE_TASK = "prepareRimeAssets"
    }

    override fun apply(target: Project) {
        // Git on Windows may check out symlinks as small text files. Resolve them
        // into a build directory so dictionaries, rather than link paths, enter the APK.
        val packagedAssets = target.layout.buildDirectory.dir("generated/rimeAssets")
        val prepare = target.tasks.register<Sync>(PREPARE_TASK) {
            from(target.assetsDir) { exclude(FILE_NAME) }
            into(packagedAssets)
            inputs.dir(target.file("data/rime"))
            doLast {
                target.assetsDir.resolve("shared").listFiles()?.forEach { source ->
                    if (source.isFile && source.length() < 256) {
                        val text = source.readText().trim()
                        if (text.startsWith("../../../../data/rime/")) {
                            val linked = source.parentFile.resolve(text).canonicalFile
                            check(linked.isFile) { "Missing Rime dictionary: $linked; initialize submodules" }
                            linked.copyTo(packagedAssets.get().file("shared/${source.name}").asFile, overwrite = true)
                        }
                    }
                }
            }
        }
        target.extensions.configure<ApplicationExtension> {
            sourceSets.getByName("main").assets.setSrcDirs(listOf(packagedAssets))
        }
        target.tasks.register<DataChecksumsTask>(TASK) {
            dependsOn(prepare)
            inputDir.set(packagedAssets)
            outputFile.set(packagedAssets.map { it.file(FILE_NAME) })
        }
        target.tasks.register<Delete>(CLEAN_TASK) {
            delete(target.assetsDir.resolve(FILE_NAME))
        }.also {
            target.tasks.findByName("clean")?.dependsOn(it)
        }
    }

    abstract class DataChecksumsTask : DefaultTask() {
        @Serializable
        data class DataChecksums(
            val sha256: String,
            val files: Map<String, String>,
        )

        @get:Incremental
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        @get:InputDirectory
        abstract val inputDir: DirectoryProperty

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        private val file by lazy { outputFile.get().asFile }

        private fun serialize(files: Map<String, String>) {
            val checksums =
                DataChecksums(
                    Hashing
                        .sha256()
                        .hashString(
                            files.entries.joinToString { it.key + it.value },
                            Charset.defaultCharset(),
                        ).toString(),
                    files,
                )
            file.writeText(json.encodeToString(checksums))
        }

        private fun deserialize(): Map<String, String> = json.decodeFromString<DataChecksums>(file.readText()).files

        companion object {
            fun sha256(file: File): String = ByteSource.wrap(file.readBytes()).hash(Hashing.sha256()).toString()
        }

        @TaskAction
        fun execute(inputChanges: InputChanges) {
            val map =
                file
                    .exists()
                    .takeIf { it }
                    ?.runCatching {
                        deserialize()
                            // remove all old dirs
                            .filterValues { it.isNotBlank() }
                            .toMutableMap()
                    }?.getOrNull()
                    ?: mutableMapOf()

            fun File.allParents(): List<File> = if (parentFile == null || parentFile.invariantSeparatorsPath in map) {
                listOf()
            } else {
                listOf(parentFile) + parentFile.allParents()
            }
            inputChanges.getFileChanges(inputDir).forEach { change ->
                if (change.file.name == file.name) {
                    return@forEach
                }
                logger.log(LogLevel.DEBUG, "${change.changeType}: ${change.normalizedPath}")
                val relativeFile = change.file.relativeTo(file.parentFile)
                val key = relativeFile.invariantSeparatorsPath
                if (change.changeType == ChangeType.REMOVED) {
                    map.remove(key)
                } else {
                    map[key] = sha256(change.file)
                }
            }
            // calculate dirs
            inputDir.asFileTree.forEach {
                it.relativeTo(file.parentFile).allParents().forEach { p ->
                    map[p.invariantSeparatorsPath] = ""
                }
            }
            serialize(map.toSortedMap())
        }
    }
}

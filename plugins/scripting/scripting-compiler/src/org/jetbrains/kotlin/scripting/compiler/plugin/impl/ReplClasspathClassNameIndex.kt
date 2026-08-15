/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin.impl

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.io.File
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

interface ReplClasspathClassNameIndex {
    fun classIdsByPrefix(prefix: String): Sequence<ClassId>
}

fun interface ReplClasspathClassNameIndexProvider {
    fun getIndex(classpath: List<File>): ReplClasspathClassNameIndex
}

class DefaultReplClasspathClassNameIndexProvider : ReplClasspathClassNameIndexProvider {

    private class CachedEntry(val stamp: Long, val byShortName: TreeMap<String, List<ClassId>>)

    private val perEntry = ConcurrentHashMap<String, CachedEntry>()

    override fun getIndex(classpath: List<File>): ReplClasspathClassNameIndex {
        val entries = classpath.mapNotNull { file ->
            if (!file.exists()) return@mapNotNull null
            val key = file.absolutePath
            val stamp = file.lastModified()
            perEntry.compute(key) { _, existing ->
                if (existing != null && existing.stamp == stamp) existing
                else CachedEntry(stamp, buildEntryIndex(file))
            }!!.byShortName
        }
        return CompositeIndex(entries)
    }

    private class CompositeIndex(private val entries: List<TreeMap<String, List<ClassId>>>) : ReplClasspathClassNameIndex {
        override fun classIdsByPrefix(prefix: String): Sequence<ClassId> {
            if (prefix.isEmpty()) return emptySequence()
            return entries.asSequence().flatMap { byShortName ->
                byShortName.tailMap(prefix).entries.asSequence()
                    .takeWhile { it.key.startsWith(prefix) }
                    .flatMap { it.value.asSequence() }
            }
        }
    }

    private fun buildEntryIndex(file: File): TreeMap<String, List<ClassId>> {
        val byShortName = HashMap<String, MutableList<ClassId>>()
        fun add(relativeClassFilePath: String) {
            val classId = classIdFromClassFilePath(relativeClassFilePath) ?: return
            byShortName.getOrPut(classId.shortClassName.identifier) { mutableListOf() }.add(classId)
        }
        when {
            file.isDirectory -> file.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { add(it.relativeTo(file).invariantSeparatorsPath) }
            file.extension.equals("jar", ignoreCase = true) || file.extension.equals("zip", ignoreCase = true) ->
                runCatching {
                    ZipFile(file).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (!entry.isDirectory && entry.name.endsWith(".class")) add(entry.name)
                        }
                    }
                }
        }
        return TreeMap<String, List<ClassId>>(byShortName)
    }

    private fun classIdFromClassFilePath(relativeClassFilePath: String): ClassId? {
        val withoutExtension = relativeClassFilePath.removeSuffix(".class")
        val lastSlash = withoutExtension.lastIndexOf('/')
        val simpleName = withoutExtension.substring(lastSlash + 1)
        if (simpleName.isEmpty() || '$' in simpleName || '-' in simpleName) return null
        val packageName = if (lastSlash < 0) "" else withoutExtension.substring(0, lastSlash).replace('/', '.')
        return ClassId(FqName(packageName), Name.identifier(simpleName))
    }
}

package org.example.ykplugin.generator

// Converts a Java source folder/file path into a package or fully qualified class name.
object JavaPackagePathResolver {

    private val SRC_MAIN_JAVA = listOf("src", "main", "java")
    private val SRC = listOf("src")

    fun fromSourcePath(path: String): String? {
        val rawSegments = path
            .trim()
            .trim('"', '\'')
            .replace('\\', '/')
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }

        val segments = rawSegments.mapIndexed { index, segment ->
            if (index == rawSegments.lastIndex && segment.endsWith(".java", ignoreCase = true)) {
                segment.dropLast(".java".length)
            } else {
                segment
            }
        }

        if (segments.isEmpty()) return null

        val srcMainJavaSegments = segmentsAfterMarker(segments, SRC_MAIN_JAVA)
        if (srcMainJavaSegments != null) return toPackageName(srcMainJavaSegments)

        val packageSegments = segmentsAfterMarker(segments, SRC) ?: return null
        return toPackageName(packageSegments)
    }

    private fun toPackageName(packageSegments: List<String>): String? {
        if (packageSegments.any { !JAVA_IDENTIFIER.matches(it) }) return null

        return packageSegments
            .joinToString(".")
            .takeIf { it.isNotBlank() }
    }

    private fun segmentsAfterMarker(segments: List<String>, marker: List<String>): List<String>? {
        if (segments.size < marker.size) return null

        val lastStart = segments.size - marker.size
        var matchStart: Int? = null
        for (start in 0..lastStart) {
            val matches = marker.indices.all { offset ->
                segments[start + offset].equals(marker[offset], ignoreCase = true)
            }
            if (matches) matchStart = start
        }
        return matchStart?.let { segments.drop(it + marker.size) }
    }

    private val JAVA_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
}

package org.example.ykplugin.generator

// Helper functions for name conversions (PascalCase / camelCase etc.).
object NameUtils {

    fun capitalize(s: String): String = s.replaceFirstChar { it.uppercaseChar() }

    fun decapitalize(s: String): String = s.replaceFirstChar { it.lowercaseChar() }

    // "first_name", "first-name", "first name" -> "FirstName"
    fun toPascalCase(s: String): String =
        s.split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { capitalize(it) }

    // "first_name" -> "firstName"
    fun toCamelCase(s: String): String = decapitalize(toPascalCase(s))
}


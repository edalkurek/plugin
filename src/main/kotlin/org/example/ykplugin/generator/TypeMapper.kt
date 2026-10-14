package org.example.ykplugin.generator

// OpenAPI -> Java type mapping matrix aligned with banking/finance standards.
//
// - string + date-time -> java.time.LocalDateTime
// - string (other)      -> java.lang.String
// - number (any format) -> java.math.BigDecimal  (never Double/Float, for cent precision)
// - integer (no format) -> java.lang.Long         (default Long to avoid overflow)
// - integer + int32     -> java.lang.Integer
// - boolean             -> java.lang.Boolean       (wrapper for null safety)
object TypeMapper {

    // Returns the fully qualified (FQN) Java type for a primitive schema.
    fun mapPrimitive(type: String?, format: String?): String = when (type) {
        "string" -> if (format == "date-time") "java.time.LocalDateTime" else "java.lang.String"
        "number" -> "java.math.BigDecimal"
        "integer" -> if (format == "int32") "java.lang.Integer" else "java.lang.Long"
        "boolean" -> "java.lang.Boolean"
        else -> "java.lang.String"
    }
}


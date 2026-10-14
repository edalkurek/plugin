package org.example.ykplugin.diagnostics

import org.example.ykplugin.model.SchemaInfo
import java.util.UUID

object DiagnosticLog {
    const val PREFIX = "[YKB-SWAGGER]"

    fun newTraceId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun message(traceId: String, stage: String, message: String): String =
        "$PREFIX [$traceId] [$stage] $message"

    fun safeUrl(url: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        return withoutQuery.replace(Regex("://[^/@]+@"), "://***@")
    }

    fun elapsedMillis(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000

    fun schemaSummary(schema: SchemaInfo?): String = when {
        schema == null -> "none"
        schema.ref != null -> "ref=${schema.ref}"
        schema.type == "array" -> "type=array, items={${schemaSummary(schema.items)}}" +
            "}" + enumSummary(schema)
        schema.type == "object" ->
            "type=object, properties=${schema.properties.size}, required=${schema.required.size}" +
                additionalPropertiesSummary(schema) +
                enumSummary(schema)
        else -> "type=${schema.type ?: "unspecified"}, format=${schema.format ?: "none"}" + enumSummary(schema)
    }

    private fun additionalPropertiesSummary(schema: SchemaInfo): String =
        schema.additionalProperties?.let { ", additionalProperties={${schemaSummary(it)}}" }.orEmpty()

    private fun enumSummary(schema: SchemaInfo): String =
        if (schema.enumValues.isEmpty()) "" else ", enumValues=${schema.enumValues.size}"
}

package org.example.ykplugin.parser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import org.example.ykplugin.diagnostics.DiagnosticLog
import org.example.ykplugin.generator.NameUtils
import org.example.ykplugin.model.EndpointInfo
import org.example.ykplugin.model.OpenApiModel
import org.example.ykplugin.model.SchemaInfo
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

// Parses Swagger/OpenAPI JSON documents. YAML is not supported.
class SwaggerParser {

    private val mapper = ObjectMapper()

    private companion object {
        val LOG = Logger.getInstance(SwaggerParser::class.java)
        val HTTP_URL = Regex("^https?://", RegexOption.IGNORE_CASE)
        val HTTP_METHODS = setOf("get", "post", "put", "delete", "patch", "head", "options")
        val PREFERRED_SUCCESS_RESPONSE_CODES = listOf("200", "201", "202")
        val UNSUPPORTED_SCHEMA_KEYWORDS = listOf("oneOf", "anyOf", "not")
        const val HTTP_TIMEOUT_MS = 15_000
    }

    fun parseSource(
        source: String,
        traceId: String = DiagnosticLog.newTraceId()
    ): OpenApiModel {
        val trimmed = source.trim()
        return if (HTTP_URL.containsMatchIn(trimmed)) {
            LOG.info(DiagnosticLog.message(traceId, "SOURCE", "Source classified as HTTP URL."))
            parseFromUrl(trimmed, traceId)
        } else {
            LOG.info(DiagnosticLog.message(traceId, "SOURCE", "Source classified as local JSON file."))
            parseFromFile(trimmed, traceId)
        }
    }

    // Downloads and parses. Performs a network call; must run on a background thread.
    fun parseFromUrl(
        url: String,
        traceId: String = DiagnosticLog.newTraceId()
    ): OpenApiModel {
        val safeUrl = DiagnosticLog.safeUrl(url)
        val requestStart = System.nanoTime()
        LOG.info(DiagnosticLog.message(traceId, "HTTP", "Swagger load started. url=$safeUrl"))

        val json = try {
            HttpRequests.request(url)
                .connectTimeout(HTTP_TIMEOUT_MS)
                .readTimeout(HTTP_TIMEOUT_MS)
                .connect { request ->
                    val connection = request.connection
                    connection.setRequestProperty("Accept", "application/json, application/*+json, */*")
                    connection.setRequestProperty("Cache-Control", "no-cache")

                    val proxyInUse = if (connection is HttpURLConnection) connection.usingProxy() else false
                    val requestMethod = if (connection is HttpURLConnection) connection.requestMethod else "n/a"
                    LOG.info(
                        DiagnosticLog.message(
                            traceId,
                            "HTTP",
                            "Connection configured. connectionType=${connection.javaClass.name}, " +
                                "requestMethod=$requestMethod, usingProxy=$proxyInUse, " +
                                "connectTimeoutMs=${connection.connectTimeout}, " +
                                "readTimeoutMs=${connection.readTimeout}, " +
                                "accept=application/json, authenticationHeader=none"
                        )
                    )

                    val status = if (connection is HttpURLConnection) connection.responseCode.toString() else "n/a"
                    LOG.info(
                        DiagnosticLog.message(
                            traceId,
                            "HTTP",
                            "Response headers received. status=$status, " +
                                "contentType=${connection.contentType ?: "unknown"}, " +
                                "contentEncoding=${connection.contentEncoding ?: "none"}, " +
                                "contentLength=${connection.contentLengthLong}"
                        )
                    )

                    val readStart = System.nanoTime()
                    val body = request.readString()
                    LOG.info(
                        DiagnosticLog.message(
                            traceId,
                            "HTTP",
                            "Response body read successfully. chars=${body.length}, " +
                                "readMs=${DiagnosticLog.elapsedMillis(readStart)}, " +
                                "totalHttpMs=${DiagnosticLog.elapsedMillis(requestStart)}"
                        )
                    )
                    body
                }
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "HTTP",
                    "Swagger HTTP read failed after ${DiagnosticLog.elapsedMillis(requestStart)} ms. " +
                        "url=$safeUrl, exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }

        return try {
            parse(json, url, traceId)
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "PARSER",
                    "Downloaded document could not be parsed. chars=${json.length}, " +
                        "exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }
    }

    fun parseFromFile(
        filePath: String,
        traceId: String = DiagnosticLog.newTraceId()
    ): OpenApiModel {
        val readStart = System.nanoTime()
        val path = try {
            toLocalPath(filePath)
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "FILE",
                    "Invalid local file path. source=$filePath, exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }

        LOG.info(
            DiagnosticLog.message(
                traceId,
                "FILE",
                "Local Swagger JSON read started. path=$path"
            )
        )

        val json = try {
            require(Files.exists(path)) { "JSON dosyasi bulunamadi: $path" }
            require(Files.isRegularFile(path)) { "Secilen kaynak bir dosya degil: $path" }
            require(path.fileName.toString().endsWith(".json", ignoreCase = true)) {
                "Yalnizca .json Swagger/OpenAPI dosyalari destekleniyor: $path"
            }

            val size = Files.size(path)
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "FILE",
                    "Local file validated. path=$path, bytes=$size, charset=UTF-8"
                )
            )
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "FILE",
                    "Local Swagger JSON read failed after ${DiagnosticLog.elapsedMillis(readStart)} ms. " +
                        "path=$path, exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }

        LOG.info(
            DiagnosticLog.message(
                traceId,
                "FILE",
                "Local Swagger JSON read successfully. path=$path, chars=${json.length}, " +
                    "elapsedMs=${DiagnosticLog.elapsedMillis(readStart)}"
            )
        )
        return try {
            parse(json, sourceUrl = "", traceId = traceId, documentSource = path.toString())
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "PARSER",
                    "Local Swagger JSON could not be parsed. path=$path, chars=${json.length}, " +
                        "exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }
    }

    fun parse(
        json: String,
        sourceUrl: String = "",
        traceId: String = DiagnosticLog.newTraceId(),
        documentSource: String = sourceUrl
    ): OpenApiModel {
        val parseStart = System.nanoTime()
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "PARSER",
                "JSON parsing started. source=${DiagnosticLog.safeUrl(documentSource)}, chars=${json.length}"
            )
        )

        val root = try {
            mapper.readTree(json)
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "PARSER",
                    "Jackson readTree failed. firstNonWhitespace=${json.firstOrNull { !it.isWhitespace() }}, " +
                        "exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }

        val openApiVersion = root.str("openapi")
        val swaggerVersion = root.str("swagger")
        val detectedVersion = openApiVersion ?: swaggerVersion ?: "unknown"
        val title = root.path("info").str("title") ?: "unknown"
        val rawPathCount = root.path("paths").size()
        val componentSchemaCount = root.path("components").path("schemas").size()
        val definitionCount = root.path("definitions").size()
        val documentRuntimeUrl = deriveDocumentRuntimeUrl(root)
        val effectiveSwaggerUrl = sourceUrl.ifBlank { documentRuntimeUrl.orEmpty() }
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "PARSER",
                "Document detected. version=$detectedVersion, openapi=${openApiVersion ?: "none"}, " +
                    "swagger=${swaggerVersion ?: "none"}, title=$title, rawPaths=$rawPathCount, " +
                    "componentSchemas=$componentSchemaCount, definitions=$definitionCount, " +
                    "documentRuntimeUrl=${documentRuntimeUrl ?: "none"}, " +
                    "effectiveRuntimeUrl=${effectiveSwaggerUrl.ifBlank { "none" }}"
            )
        )
        if (sourceUrl.isBlank() && documentRuntimeUrl.isNullOrBlank()) {
            LOG.warn(
                DiagnosticLog.message(
                    traceId,
                    "PARSER",
                    "Local document has no usable servers[0].url or Swagger 2 host/basePath. " +
                        "DTO generation can continue, but generated SQL endpoint URLs may be incomplete."
                )
            )
        }
        logUnsupportedFeatures(root, traceId)

        val schemas = LinkedHashMap<String, SchemaInfo>()
        val rawSchemaNodes = LinkedHashMap<String, JsonNode>()

        root.path("components").path("schemas").objectFields().forEach { (name, node) ->
            try {
                rawSchemaNodes[name] = node
                schemas[name] = toSchemaInfo(node)
                LOG.info(
                    DiagnosticLog.message(
                        traceId,
                        "PARSER",
                        "Schema parsed. source=components.schemas, name=$name, " +
                            "result={${DiagnosticLog.schemaSummary(schemas[name])}}"
                    )
                )
            } catch (e: Exception) {
                LOG.error(
                    DiagnosticLog.message(
                        traceId,
                        "PARSER",
                        "Schema parse failed. source=components.schemas, name=$name, " +
                            "exception=${e.javaClass.name}, message=${e.message}"
                    ),
                    e
                )
                throw e
            }
        }

        root.path("definitions").objectFields().forEach { (name, node) ->
            try {
                rawSchemaNodes.putIfAbsent(name, node)
                schemas.putIfAbsent(name, toSchemaInfo(node))
                LOG.info(
                    DiagnosticLog.message(
                        traceId,
                        "PARSER",
                        "Schema parsed. source=definitions, name=$name, " +
                            "result={${DiagnosticLog.schemaSummary(schemas[name])}}"
                    )
                )
            } catch (e: Exception) {
                LOG.error(
                    DiagnosticLog.message(
                        traceId,
                        "PARSER",
                        "Schema parse failed. source=definitions, name=$name, " +
                            "exception=${e.javaClass.name}, message=${e.message}"
                    ),
                    e
                )
                throw e
            }
        }
        LOG.info(DiagnosticLog.message(traceId, "PARSER", "Reusable schema pass completed. schemas=${schemas.size}"))

        // Resolve simple allOf inheritance in two passes, matching the previous behavior.
        repeat(2) { pass ->
            for ((name, node) in rawSchemaNodes) {
                val allOfNode = node.path("allOf")
                if (allOfNode.isMissingNode || !allOfNode.isArray) continue
                val currentSchema = schemas[name] ?: continue
                val inherited = LinkedHashMap<String, SchemaInfo>()
                val referencedSchemas = mutableListOf<String>()
                for (subSchema in allOfNode) {
                    val refName = subSchema.str("\$ref")?.substringAfterLast('/') ?: continue
                    referencedSchemas.add(refName)
                    schemas[refName]?.properties?.let { inherited.putAll(it) }
                }
                if (inherited.isEmpty()) {
                    LOG.info(
                        DiagnosticLog.message(
                            traceId,
                            "PARSER",
                            "allOf merge skipped. pass=${pass + 1}, schema=$name, refs=$referencedSchemas, " +
                                "reason=no inherited properties resolved"
                        )
                    )
                    continue
                }
                inherited.putAll(currentSchema.properties)
                schemas[name] = currentSchema.copy(
                    type = currentSchema.type ?: "object",
                    properties = inherited
                )
                LOG.info(
                    DiagnosticLog.message(
                        traceId,
                        "PARSER",
                        "allOf merged. pass=${pass + 1}, schema=$name, refs=$referencedSchemas, " +
                            "properties=${inherited.size}"
                    )
                )
            }
        }

        val endpoints = ArrayList<EndpointInfo>()
        root.path("paths").objectFields().forEach { (path, pathItem) ->
            pathItem.objectFields().forEach { (method, operation) ->
                if (method.lowercase() in HTTP_METHODS) {
                    try {
                        endpoints.add(toEndpoint(path, method, operation, schemas, traceId))
                    } catch (e: Exception) {
                        LOG.error(
                            DiagnosticLog.message(
                                traceId,
                                "PARSER",
                                "Endpoint parse failed. method=${method.uppercase()}, path=$path, " +
                                    "exception=${e.javaClass.name}, message=${e.message}"
                            ),
                            e
                        )
                        throw e
                    }
                }
            }
        }

        val serviceName = deriveDocumentServiceName(root)
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "PARSER",
                "Parsing completed. version=$detectedVersion, paths=$rawPathCount, " +
                    "operations=${endpoints.size}, schemas=${schemas.size}, serviceName=$serviceName, " +
                    "elapsedMs=${DiagnosticLog.elapsedMillis(parseStart)}"
            )
        )
        return OpenApiModel(endpoints, schemas, effectiveSwaggerUrl, serviceName, traceId)
    }

    private fun deriveDocumentServiceName(root: JsonNode): String {
        val title = root.path("info").str("title")?.ifBlank { null }
        return title?.let { NameUtils.toPascalCase(it) } ?: "RestService"
    }

    private fun deriveDocumentRuntimeUrl(root: JsonNode): String? {
        val openApiServer = root.path("servers")
            .firstOrNull()
            ?.str("url")
            ?.trim()
            ?.ifBlank { null }
        if (openApiServer != null) return openApiServer

        val host = root.str("host")?.trim()?.ifBlank { null } ?: return null
        val scheme = root.path("schemes").firstOrNull()?.asText()?.ifBlank { null } ?: "https"
        val basePath = root.str("basePath")?.trim()?.ifBlank { null }.orEmpty()
        val normalizedBasePath = when {
            basePath.isBlank() -> ""
            basePath.startsWith("/") -> basePath
            else -> "/$basePath"
        }
        return "$scheme://$host$normalizedBasePath"
    }

    private fun toEndpoint(
        path: String,
        method: String,
        op: JsonNode,
        schemas: Map<String, SchemaInfo>,
        traceId: String
    ): EndpointInfo {
        val operationId = op.str("operationId")?.ifBlank { null }
        val tag = op.path("tags").firstOrNull()?.asText()
        val serviceName = operationId?.let { NameUtils.toPascalCase(it) }
            ?: deriveServiceName(path, method)
        val request = findRequestSchema(op, schemas)
        val response = findResponseSchema(op, schemas)

        logReferenceResolution("request", request, schemas, path, method, traceId)
        logReferenceResolution("response", response, schemas, path, method, traceId)
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "PARSER",
                "Endpoint resolved. method=${method.uppercase()}, path=$path, " +
                    "operationId=${operationId ?: "none"}, tag=${tag ?: "default"}, serviceName=$serviceName, " +
                    "requestSource=${request?.source ?: "none"}, " +
                    "request={${DiagnosticLog.schemaSummary(request?.schema)}}, " +
                    "responseSource=${response?.source ?: "none"}, " +
                    "response={${DiagnosticLog.schemaSummary(response?.schema)}}"
            )
        )

        return EndpointInfo(
            path = path,
            httpMethod = method.uppercase(),
            operationId = operationId,
            tag = tag,
            serviceName = serviceName,
            requestSchema = request?.schema,
            responseSchema = response?.schema
        )
    }

    private fun deriveServiceName(path: String, method: String): String {
        val segments = path.split('/')
            .filter { it.isNotBlank() }
            .joinToString("") { NameUtils.toPascalCase(it) }
        return NameUtils.capitalize(method.lowercase()) + segments
    }

    private fun findRequestSchema(op: JsonNode, schemas: Map<String, SchemaInfo>): ResolvedSchema? {
        schemaFromContent(op.path("requestBody").path("content"), "requestBody", schemas)?.let { return it }

        val bodyParam = op.path("parameters").firstOrNull { it.str("in") == "body" }
        return bodyParam?.path("schema")
            ?.takeUnless { it.isMissingNode }
            ?.let { resolvedPayloadSchema(it, "parameters.body", schemas) }
    }

    private fun findResponseSchema(op: JsonNode, schemas: Map<String, SchemaInfo>): ResolvedSchema? {
        val responses = op.path("responses")
        val successCodes = responses.fieldNames().asSequence()
            .filter { isSuccessResponseCode(it) }
        val codes = (PREFERRED_SUCCESS_RESPONSE_CODES.asSequence() + successCodes + sequenceOf("default")).distinct()
        for (code in codes) {
            val responseNode = responses.path(code)
            if (responseNode.isMissingNode) continue

            schemaFromContent(responseNode.path("content"), "responses.$code", schemas)?.let { return it }
            responseNode.path("schema")
                .takeUnless { it.isMissingNode }
                ?.let { resolvedPayloadSchema(it, "responses.$code.schema", schemas) }
                ?.let { return it }
        }
        return null
    }

    private fun schemaFromContent(
        content: JsonNode,
        source: String,
        schemas: Map<String, SchemaInfo>
    ): ResolvedSchema? {
        if (content.isMissingNode || !content.isObject) return null

        val mediaTypes = content.fieldNames().asSequence().toList()
        if (mediaTypes.isEmpty()) return null

        val mediaType = mediaTypes.firstOrNull { it.contains("json", ignoreCase = true) }
            ?: mediaTypes.first()

        return content.path(mediaType).path("schema")
            .takeUnless { it.isMissingNode }
            ?.let { resolvedPayloadSchema(it, "$source.content[$mediaType]", schemas) }
    }

    private fun isSuccessResponseCode(code: String): Boolean {
        val trimmed = code.trim()
        return trimmed.length == 3 &&
            trimmed[0] == '2' &&
            trimmed.drop(1).all { it.isDigit() || it.equals('x', ignoreCase = true) }
    }

    private fun resolvedPayloadSchema(
        node: JsonNode,
        source: String,
        schemas: Map<String, SchemaInfo>
    ): ResolvedSchema? {
        val schema = toSchemaInfo(node)
        return if (isEmptyPayloadSchema(schema, schemas)) null else ResolvedSchema(schema, source)
    }

    private fun isEmptyPayloadSchema(
        schema: SchemaInfo,
        schemas: Map<String, SchemaInfo>,
        visitedRefs: Set<String> = emptySet()
    ): Boolean {
        schema.ref?.let { ref ->
            if (ref in visitedRefs) return false
            val referenced = schemas[ref] ?: return false
            return isEmptyPayloadSchema(referenced, schemas, visitedRefs + ref)
        }

        val hasNoGeneratedShape = schema.format == null &&
            schema.enumValues.isEmpty() &&
            schema.properties.isEmpty() &&
            schema.items == null &&
            schema.additionalProperties == null

        return hasNoGeneratedShape && (schema.type == null || schema.type == "object")
    }

    private fun toSchemaInfo(node: JsonNode): SchemaInfo {
        node.str("\$ref")?.let { ref ->
            return SchemaInfo(ref = ref.substringAfterLast('/'))
        }

        val type = node.str("type")
        val format = node.str("format")
        val enumValues = enumValues(node)
        val additionalProperties = additionalPropertiesSchema(node)

        if (type == "array") {
            val items = node.path("items").takeUnless { it.isMissingNode }?.let { toSchemaInfo(it) }
            return SchemaInfo(type = "array", format = format, enumValues = enumValues, items = items)
        }

        val propertiesNode = node.path("properties")
        val required = node.path("required").mapNotNull { it.asText() }.toSet()

        val allOfNode = node.path("allOf")
        if (!allOfNode.isMissingNode && allOfNode.isArray) {
            val mergedProperties = LinkedHashMap<String, SchemaInfo>()
            val mergedRequired = mutableSetOf<String>()
            mergedRequired.addAll(required)
            for (subSchema in allOfNode) {
                if (subSchema.str("\$ref") != null) continue
                val subProps = subSchema.path("properties")
                if (!subProps.isMissingNode) {
                    subProps.objectFields().forEach { (name, value) ->
                        mergedProperties[name] = toSchemaInfo(value)
                    }
                }
                subSchema.path("required").forEach { mergedRequired.add(it.asText()) }
            }
            propertiesNode.objectFields().forEach { (name, value) ->
                mergedProperties[name] = toSchemaInfo(value)
            }
            return SchemaInfo(
                type = type ?: "object",
                enumValues = enumValues,
                properties = mergedProperties,
                required = mergedRequired,
                additionalProperties = additionalProperties
            )
        }

        // OpenAPI 3.1 commonly omits type for object schemas.
        if (!propertiesNode.isMissingNode || type == "object" || required.isNotEmpty() || additionalProperties != null) {
            val properties = LinkedHashMap<String, SchemaInfo>()
            propertiesNode.objectFields().forEach { (name, value) ->
                properties[name] = toSchemaInfo(value)
            }
            return SchemaInfo(
                type = "object",
                enumValues = enumValues,
                properties = properties,
                required = required,
                additionalProperties = additionalProperties
            )
        }

        return SchemaInfo(type = type, format = format, enumValues = enumValues)
    }

    private fun additionalPropertiesSchema(node: JsonNode): SchemaInfo? {
        val additionalProperties = node.path("additionalProperties")
        if (additionalProperties.isMissingNode || additionalProperties.isNull) return null
        if (additionalProperties.isBoolean) {
            return if (additionalProperties.asBoolean()) SchemaInfo(type = "object") else null
        }
        return toSchemaInfo(additionalProperties)
    }

    private fun enumValues(node: JsonNode): List<String> {
        val enumNode = node.path("enum")
        if (!enumNode.isArray) return emptyList()
        return enumNode.map { enumValue ->
            if (enumValue.isNull) "null" else enumValue.asText()
        }
    }

    private fun logReferenceResolution(
        direction: String,
        resolved: ResolvedSchema?,
        schemas: Map<String, SchemaInfo>,
        path: String,
        method: String,
        traceId: String
    ) {
        val ref = resolved?.schema?.ref ?: return
        if (schemas.containsKey(ref)) {
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "PARSER",
                    "$direction reference resolved. method=${method.uppercase()}, path=$path, " +
                        "ref=$ref, source=${resolved.source}"
                )
            )
        } else {
            LOG.warn(
                DiagnosticLog.message(
                    traceId,
                    "PARSER",
                    "$direction reference unresolved. method=${method.uppercase()}, path=$path, " +
                        "ref=$ref, source=${resolved.source}, availableSchemas=${schemas.size}"
                )
            )
        }
    }

    private fun logUnsupportedFeatures(root: JsonNode, traceId: String) {
        for (keyword in UNSUPPORTED_SCHEMA_KEYWORDS) {
            val count = countFieldOccurrences(root, keyword)
            if (count > 0) {
                LOG.warn(
                    DiagnosticLog.message(
                        traceId,
                        "PARSER",
                        "Document contains partially/unsupported schema keyword '$keyword'. occurrences=$count"
                    )
                )
            }
        }
        val arrayTypeCount = countArrayTypeDeclarations(root)
        if (arrayTypeCount > 0) {
            LOG.warn(
                DiagnosticLog.message(
                    traceId,
                    "PARSER",
                    "Document contains OpenAPI 3.1 JSON Schema array-form type declarations. " +
                        "occurrences=$arrayTypeCount"
                )
            )
        }
    }

    private fun countFieldOccurrences(node: JsonNode, fieldName: String): Int {
        var count = if (node.isObject && node.has(fieldName)) 1 else 0
        node.elements().forEachRemaining { child ->
            count += countFieldOccurrences(child, fieldName)
        }
        return count
    }

    private fun countArrayTypeDeclarations(node: JsonNode): Int {
        var count = if (node.isObject && node.path("type").isArray) 1 else 0
        node.elements().forEachRemaining { child ->
            count += countArrayTypeDeclarations(child)
        }
        return count
    }

    private fun JsonNode.str(field: String): String? {
        val node = path(field)
        return if (node.isMissingNode || node.isNull) null else node.asText()
    }

    private fun JsonNode.objectFields(): Sequence<Map.Entry<String, JsonNode>> {
        return fields().asSequence()
    }

    private fun toLocalPath(source: String): Path {
        require(source.isNotBlank()) { "Swagger/OpenAPI URL veya JSON dosya yolu bos olamaz." }
        return if (source.startsWith("file:", ignoreCase = true)) {
            Path.of(URI.create(source)).toAbsolutePath().normalize()
        } else {
            Path.of(source).toAbsolutePath().normalize()
        }
    }

    private data class ResolvedSchema(
        val schema: SchemaInfo,
        val source: String
    )
}

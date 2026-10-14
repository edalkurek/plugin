package org.example.ykplugin.model

// Full OpenAPI model parsed from a Swagger document.
// endpoints: all operations (path + http method)
// schemas: reusable schemas under components/schemas (name -> schema)
// swaggerUrl: runtime URL used to build SQL endpoint URLs. For local files this is
// derived from OpenAPI servers[0].url or Swagger 2 schemes/host/basePath.
// serviceName: document-level service name derived from info.title.
data class OpenApiModel(
    val endpoints: List<EndpointInfo>,
    val schemas: Map<String, SchemaInfo>,
    val swaggerUrl: String = "",
    val serviceName: String = "",
    val diagnosticTraceId: String = ""
)

// A single OpenAPI operation (endpoint).
// serviceName: PascalCase name derived from operationId or path + HTTP method.
data class EndpointInfo(
    val path: String,
    val httpMethod: String,
    val operationId: String?,
    val tag: String?,
    val serviceName: String,
    val requestSchema: SchemaInfo? = null,
    val responseSchema: SchemaInfo? = null
)

// A JSON schema (object / array / primitive / $ref).
// ref: when not null this is a reference; value is the schema name under components/schemas.
// items: element schema when type == "array".
// properties: fields when type == "object".
// additionalProperties: value schema when type == "object" represents an OpenAPI dictionary/map.
data class SchemaInfo(
    val type: String? = null,
    val format: String? = null,
    val ref: String? = null,
    val enumValues: List<String> = emptyList(),
    val properties: Map<String, SchemaInfo> = emptyMap(),
    val items: SchemaInfo? = null,
    val required: Set<String> = emptySet(),
    val additionalProperties: SchemaInfo? = null
)


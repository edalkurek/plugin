package org.example.ykplugin.generator

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import org.example.ykplugin.diagnostics.DiagnosticLog
import org.example.ykplugin.model.SchemaInfo

enum class DtoRootClassKind {
    REQUEST,
    RESPONSE
}

// Generates Java DTO classes using the IntelliJ PSI API.
//
// Features:
//  - Each DTO is a separate .java file.
//  - No Lombok; getters/setters are generated manually (all start with "get", incl. boolean).
//  - Nested objects, $ref references and object arrays are generated recursively.
//  - OpenAPI object schemas with additionalProperties are generated as Map<String, V>.
//  - Type text is written as FQN; imports are added automatically via shortenClassReferences.
//
// Used for a single variant (REMOTE or BACKEND) and a single target folder.
// All PSI writes must be performed inside a WriteCommandAction.
class DtoGenerator(
    private val project: Project,
    private val targetDir: PsiDirectory,
    private val packageName: String?,
    private val variant: DtoVariant,
    private val schemas: Map<String, SchemaInfo>,
    private val traceId: String
) {

    private val generated = LinkedHashSet<String>()
    private val generatedEnums = LinkedHashSet<String>()
    private val writtenFiles = LinkedHashSet<String>()

    val generatedDtoCount: Int
        get() = writtenFiles.size

    val generatedDtoNames: List<String>
        get() = writtenFiles.toList()

    init {
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "DTO",
                "DTO generator initialized. variant=$variant, target=${targetDir.virtualFile.path}, " +
                    "package=${packageName ?: "default"}, availableSchemas=${schemas.size}"
            )
        )
    }

    // Generates class(es) for a request/response schema and returns the type reference to use.
    // For an array schema returns List<...>, otherwise points to the generated class (ClassRef).
    fun generateClassFor(
        className: String,
        schema: SchemaInfo,
        rootClassKind: DtoRootClassKind? = NamingStrategy.rootClassKind(className)
    ): TypeRef {
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "DTO",
                "Root DTO requested. variant=$variant, class=$className, rootKind=${rootClassKind ?: "none"}, " +
                    "schema={${DiagnosticLog.schemaSummary(schema)}}"
            )
        )
        if (schema.ref == null && schema.type == "array") {
            val items = schema.items
            if (items == null) {
                LOG.warn(
                    DiagnosticLog.message(
                        traceId,
                        "DTO",
                        "Array root has no items. variant=$variant, class=$className; using Object."
                    )
                )
                return TypeRef.ListRef(TypeRef.JavaType("java.lang.Object"))
            }
            return TypeRef.ListRef(resolveFieldType(items, className, "item"))
        }
        val resolved = if (schema.ref != null) {
            val referenced = schemas[schema.ref]
            if (referenced == null) {
                LOG.warn(
                    DiagnosticLog.message(
                        traceId,
                        "DTO",
                        "Root reference unresolved. variant=$variant, class=$className, ref=${schema.ref}; " +
                            "an empty/fallback class may be generated."
                    )
                )
                schema
            } else {
                LOG.info(
                    DiagnosticLog.message(
                        traceId,
                        "DTO",
                        "Root reference resolved. variant=$variant, class=$className, ref=${schema.ref}, " +
                            "schema={${DiagnosticLog.schemaSummary(referenced)}}"
                    )
                )
                referenced
            }
        } else {
            schema
        }
        if (isEnumSchema(resolved)) {
            val enumClassName = NamingStrategy.rootEnumClassName(className, variant)
            buildEnum(enumClassName, resolved.enumValues)
            return TypeRef.ClassRef(enumClassName)
        }
        if (isPrimitiveSchema(resolved)) {
            val primitiveType = TypeRef.JavaType(TypeMapper.mapPrimitive(resolved.type, resolved.format))
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "Root primitive schema resolved without DTO class generation. variant=$variant, " +
                        "class=$className, javaType=${primitiveType.toFieldType()}"
                )
            )
            return primitiveType
        }
        if (isMapSchema(resolved)) {
            val mapType = resolveMapType(resolved, className, "value")
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "Root map schema resolved. variant=$variant, class=$className, " +
                        "javaType=${mapType.toFieldType()}"
                )
            )
            return mapType
        }
        buildClass(className, resolved, rootClassKind)
        return TypeRef.ClassRef(className)
    }

    // Resolves a field type, recursively generating sub-DTO classes when needed.
    private fun resolveFieldType(schema: SchemaInfo, ownerClass: String, propName: String): TypeRef {
        schema.ref?.let { refName ->
            val className = NamingStrategy.schemaClassName(refName, variant)
            val referenced = schemas[refName]
            if (referenced == null) {
                LOG.warn(
                    DiagnosticLog.message(
                        traceId,
                        "DTO",
                        "Field reference unresolved. variant=$variant, owner=$ownerClass, property=$propName, " +
                            "ref=$refName, generatedType=$className"
                    )
                )
            } else {
                LOG.info(
                    DiagnosticLog.message(
                        traceId,
                        "DTO",
                        "Field reference resolved. variant=$variant, owner=$ownerClass, property=$propName, " +
                            "ref=$refName, generatedType=$className"
                    )
                )
                if (isEnumSchema(referenced)) {
                    val enumClassName = NamingStrategy.schemaEnumClassName(refName, variant)
                    buildEnum(enumClassName, referenced.enumValues)
                    return TypeRef.ClassRef(enumClassName)
                }
                if (isMapSchema(referenced)) {
                    return resolveMapType(referenced, ownerClass, propName)
                }
                buildClass(className, referenced, null)
            }
            return TypeRef.ClassRef(className)
        }

        if (isEnumSchema(schema)) {
            val enumClassName = NamingStrategy.fieldEnumClassName(propName, variant)
            buildEnum(enumClassName, schema.enumValues)
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "Enum field type resolved. variant=$variant, owner=$ownerClass, property=$propName, " +
                        "generatedType=$enumClassName, values=${schema.enumValues.size}"
                )
            )
            return TypeRef.ClassRef(enumClassName)
        }

        val resolvedType = when (schema.type) {
            "array" -> {
                val items = schema.items
                if (items == null) {
                    LOG.warn(
                        DiagnosticLog.message(
                            traceId,
                            "DTO",
                            "Array property has no items. variant=$variant, owner=$ownerClass, " +
                                "property=$propName; using List<Object>."
                        )
                    )
                    TypeRef.ListRef(TypeRef.JavaType("java.lang.Object"))
                } else {
                    TypeRef.ListRef(resolveFieldType(items, ownerClass, propName))
                }
            }

            "object" -> {
                if (isMapSchema(schema)) {
                    resolveMapType(schema, ownerClass, propName)
                } else {
                    val className = NamingStrategy.schemaClassName(NameUtils.capitalize(propName), variant)
                    LOG.info(
                        DiagnosticLog.message(
                            traceId,
                            "DTO",
                            "Inline object found. variant=$variant, owner=$ownerClass, property=$propName, " +
                                "generatedType=$className, properties=${schema.properties.size}"
                        )
                    )
                    buildClass(className, schema, null)
                    TypeRef.ClassRef(className)
                }
            }

            else -> TypeRef.JavaType(TypeMapper.mapPrimitive(schema.type, schema.format))
        }
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "DTO",
                "Field type resolved. variant=$variant, owner=$ownerClass, property=$propName, " +
                    "schema={${DiagnosticLog.schemaSummary(schema)}}, javaType=${resolvedType.toFieldType()}"
            )
        )
        return resolvedType
    }

    private fun isEnumSchema(schema: SchemaInfo): Boolean =
        schema.enumValues.isNotEmpty() && schema.type != "array" && schema.type != "object"

    private fun isPrimitiveSchema(schema: SchemaInfo): Boolean =
        schema.type != null && schema.type != "array" && schema.type != "object"

    private fun isMapSchema(schema: SchemaInfo): Boolean =
        schema.type == "object" && schema.additionalProperties != null

    private fun resolveMapType(schema: SchemaInfo, ownerClass: String, propName: String): TypeRef {
        val valueSchema = schema.additionalProperties
        val valueType = if (valueSchema == null || isFreeFormValueSchema(valueSchema)) {
            TypeRef.JavaType("java.lang.Object")
        } else {
            resolveFieldType(valueSchema, ownerClass, propName)
        }
        val mapType = TypeRef.MapRef(valueType)
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "DTO",
                "Map schema resolved. variant=$variant, owner=$ownerClass, property=$propName, " +
                    "valueSchema={${DiagnosticLog.schemaSummary(valueSchema)}}, javaType=${mapType.toFieldType()}"
            )
        )
        return mapType
    }

    private fun isFreeFormValueSchema(schema: SchemaInfo): Boolean {
        val hasShape = schema.ref != null ||
            schema.format != null ||
            schema.enumValues.isNotEmpty() ||
            schema.properties.isNotEmpty() ||
            schema.items != null ||
            schema.required.isNotEmpty() ||
            schema.additionalProperties != null
        return !hasShape && (schema.type == null || schema.type == "object")
    }

    private fun buildEnum(className: String, enumValues: List<String>) {
        if (!generatedEnums.add(className)) {
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "Enum build skipped because class was already visited. variant=$variant, class=$className"
                )
            )
            return
        }

        LOG.info(
            DiagnosticLog.message(
                traceId,
                "DTO",
                "Enum build started. variant=$variant, class=$className, values=${enumValues.size}"
            )
        )

        val constants = enumConstants(enumValues)
        val text = buildString {
            if (!packageName.isNullOrBlank()) append("package ").append(packageName).append(";\n\n")
            append("public enum ").append(className).append(" {\n\n")
            constants.forEachIndexed { index, constant ->
                append("    ").append(constant.name).append('(').append(constant.valueLiteral).append(')')
                append(if (index == constants.lastIndex) ";\n\n" else ",\n")
            }
            append("    private final String value;\n\n")
            append("    ").append(className).append("(String value) { this.value = value; }\n\n")
            append("    public String getValue() { return value; }\n\n")
            append("    public static ").append(className).append(" fromValue(String value) {\n")
            append("        for (").append(className).append(" item : ").append(className).append(".values()) {\n")
            append("            if (java.util.Objects.equals(item.value, value)) { return item; }\n")
            append("        }\n")
            append("        throw new IllegalArgumentException(\"Unexpected value '\" + value + \"'\");\n")
            append("    }\n")
            append("}\n")
        }

        writeFile(className, text, "Enum")
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "DTO",
                "Enum build completed. variant=$variant, class=$className, values=${enumValues.size}, " +
                    "generatedDtoCount=$generatedDtoCount"
            )
        )
    }

    private fun buildClass(className: String, schema: SchemaInfo, rootClassKind: DtoRootClassKind?) {
        if (!generated.add(className)) {
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "DTO build skipped because class was already visited. variant=$variant, class=$className"
                )
            )
            return
        }

        LOG.info(
            DiagnosticLog.message(
                traceId,
                "DTO",
                "DTO build started. variant=$variant, class=$className, rootKind=${rootClassKind ?: "none"}, " +
                    "properties=${schema.properties.size}, required=${schema.required.size}"
            )
        )

        val baseClassFqn = baseClassFqn(rootClassKind)
        val baseClassSimpleName = baseClassFqn?.substringAfterLast('.')
        val fields = StringBuilder()
        val accessors = StringBuilder()

        for ((propName, propSchema) in schema.properties) {
            val fieldName = NameUtils.toCamelCase(propName)
            if (fieldName.isEmpty()) {
                LOG.warn(
                    DiagnosticLog.message(
                        traceId,
                        "DTO",
                        "Property skipped because generated field name is empty. variant=$variant, " +
                            "class=$className, sourceProperty=$propName"
                    )
                )
                continue
            }

            val typeText = resolveFieldType(propSchema, className, propName).toFieldType()
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "DTO field generated. variant=$variant, class=$className, sourceProperty=$propName, " +
                        "field=$fieldName, javaType=$typeText, required=${propName in schema.required}"
                )
            )
            val cap = NameUtils.capitalize(fieldName)

            fields.append("    private ").append(typeText).append(' ').append(fieldName).append(";\n")
            accessors.append("    public ").append(typeText).append(" get").append(cap)
                .append("() { return ").append(fieldName).append("; }\n")
            accessors.append("    public void set").append(cap).append('(')
                .append(typeText).append(' ').append(fieldName)
                .append(") { this.").append(fieldName).append(" = ").append(fieldName).append("; }\n\n")
        }

        val text = buildString {
            if (!packageName.isNullOrBlank()) append("package ").append(packageName).append(";\n\n")
            if (baseClassFqn != null) append("import ").append(baseClassFqn).append(";\n\n")
            append("public class ").append(className)
            if (baseClassSimpleName != null) append(" extends ").append(baseClassSimpleName)
            append(" {\n\n")
            append(fields)
            append('\n')
            append(accessors)
            append("}\n")
        }

        writeFile(className, text, "DTO")
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "DTO",
                "DTO build completed. variant=$variant, class=$className, fields=${schema.properties.size}, " +
                    "generatedDtoCount=$generatedDtoCount"
            )
        )
    }

    private fun enumConstants(enumValues: List<String>): List<EnumConstant> {
        val usedNames = LinkedHashMap<String, Int>()
        return enumValues.map { value ->
            val baseName = enumConstantBaseName(value)
            val usageCount = (usedNames[baseName] ?: 0) + 1
            usedNames[baseName] = usageCount
            val constantName = if (usageCount == 1) baseName else "${baseName}_$usageCount"
            EnumConstant(constantName, javaStringLiteral(value))
        }
    }

    private fun enumConstantBaseName(value: String): String {
        val builder = StringBuilder()
        for (ch in value.trim()) {
            when (ch) {
                in 'A'..'Z' -> builder.append(ch)
                in 'a'..'z' -> builder.append(ch.uppercaseChar())
                in '0'..'9' -> builder.append(ch)
                else -> if (builder.isNotEmpty() && builder.last() != '_') builder.append('_')
            }
        }
        val candidate = builder.toString().trim('_')
        return when {
            candidate.isBlank() -> "VALUE"
            candidate.first().isDigit() -> "VALUE_$candidate"
            else -> candidate
        }
    }

    private fun javaStringLiteral(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> {
                    if (ch.code in 0x20..0x7E) {
                        append(ch)
                    } else {
                        append("\\u").append(ch.code.toString(16).uppercase().padStart(4, '0'))
                    }
                }
            }
        }
        append('"')
    }

    private fun writeFile(className: String, text: String, fileKind: String) {
        val fileName = "$className.java"
        val parentVf = targetDir.virtualFile

        val existing = parentVf.findChild(fileName)
        if (existing != null) {
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "Existing $fileKind file will be replaced. variant=$variant, path=${existing.path}"
                )
            )
            existing.delete(this)
        }

        val vf = parentVf.createChildData(this, fileName)
        VfsUtil.saveText(vf, text)
        writtenFiles.add(className)
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "DTO",
                "$fileKind file written. variant=$variant, path=${vf.path}, chars=${text.length}"
            )
        )

        val psiFile = PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile
        if (psiFile == null) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "$fileKind file was written but PSI Java file could not be resolved. " +
                        "variant=$variant, path=${vf.path}"
                )
            )
            return
        }
        try {
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiFile)
            CodeStyleManager.getInstance(project).reformat(psiFile)
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "$fileKind PSI formatting completed. variant=$variant, path=${vf.path}"
                )
            )
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "DTO",
                    "$fileKind file was written but PSI formatting failed. variant=$variant, path=${vf.path}, " +
                        "exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
        }
    }

    private data class EnumConstant(
        val name: String,
        val valueLiteral: String
    )

    private fun baseClassFqn(rootClassKind: DtoRootClassKind?): String? =
        StandardBaseTypes.rootBaseClass(variant, rootClassKind)

    private companion object {
        val LOG = Logger.getInstance(DtoGenerator::class.java)
    }
}


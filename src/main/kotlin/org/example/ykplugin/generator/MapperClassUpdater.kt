package org.example.ykplugin.generator

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.PsiType
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import org.example.ykplugin.diagnostics.DiagnosticLog

data class MapperMethodSpec(
    val fromTypeFqn: String,
    val toTypeFqn: String,
    val fromClass: PsiClass,
    val toClass: PsiClass
)

private data class FieldPair(
    val name: String,
    val fromField: PsiField,
    val toField: PsiField
)

private data class MethodBuildResult(
    val text: String,
    val assignmentCount: Int
)

private data class ClassPairKey(
    val fromTypeFqn: String,
    val toTypeFqn: String
)

private data class HelperMethod(
    val name: String,
    val text: String
)

private data class MethodSignature(
    val name: String,
    val parameterTypeFqn: String
)

private class HelperBuildContext(
    targetClass: PsiClass
) {
    val helperNamesByKey = LinkedHashMap<ClassPairKey, String>()
    val helperMethodsByKey = LinkedHashMap<ClassPairKey, HelperMethod>()
    val helperKeysInProgress = LinkedHashSet<ClassPairKey>()
    val methodReturnTypesBySignature = targetClass.methods.mapNotNull { method ->
        val parameterType = method.parameterList.parameters.singleOrNull()?.type?.canonicalText ?: return@mapNotNull null
        MethodSignature(method.name, parameterType) to method.returnType?.canonicalText
    }.toMap().toMutableMap()

    fun registerAvailableMapping(fromTypeFqn: String, toTypeFqn: String, methodName: String) {
        helperNamesByKey[ClassPairKey(fromTypeFqn, toTypeFqn)] = methodName
        methodReturnTypesBySignature[MethodSignature(methodName, fromTypeFqn)] = toTypeFqn
    }
}

// Adds DTO map methods to an existing Java class.
// The target class is never created here; it must come from the Java file selected by the user.
class MapperClassUpdater(
    private val project: Project,
    private val traceId: String
) {

    fun addMethods(mapperFile: PsiJavaFile, methods: List<MapperMethodSpec>) {
        val filePath = mapperFile.virtualFile?.path ?: mapperFile.name
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "MAPPER",
                "Mapper update started. file=$filePath, requestedMethods=${methods.size}"
            )
        )
        try {
            addMethodsInternal(mapperFile, methods, filePath)
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "MAPPER",
                    "Mapper update failed. file=$filePath, exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }
    }

    private fun addMethodsInternal(mapperFile: PsiJavaFile, methods: List<MapperMethodSpec>, filePath: String) {
        if (methods.isEmpty()) {
            LOG.info(DiagnosticLog.message(traceId, "MAPPER", "No mapper methods requested. file=$filePath"))
            return
        }
        val targetClass = mapperFile.classes.firstOrNull { !it.isInterface }
            ?: mapperFile.classes.firstOrNull()
            ?: run {
                LOG.error(
                    DiagnosticLog.message(
                        traceId,
                        "MAPPER",
                        "Mapper update aborted: no Java class found. file=$filePath"
                    )
                )
                return
            }

        val factory = JavaPsiFacade.getElementFactory(project)
        val parserFacade = ServiceManager.getService(project, PsiParserFacade::class.java)
        val javaCodeStyle = JavaCodeStyleManager.getInstance(project)
        val codeStyle = CodeStyleManager.getInstance(project)
        val helperContext = HelperBuildContext(targetClass)
        var added = 0
        var skipped = 0

        for (spec in methods) {
            helperContext.registerAvailableMapping(
                spec.fromTypeFqn,
                spec.toTypeFqn,
                mapperMethodName(spec.toClass)
            )
        }

        for (spec in methods) {
            val methodName = mapperMethodName(spec.toClass)
            if (hasEquivalentMapMethod(targetClass, spec, methodName)) {
                skipped++
                LOG.info(
                    DiagnosticLog.message(
                        traceId,
                        "MAPPER",
                        "Equivalent mapper method already exists. method=$methodName, " +
                            "from=${spec.fromTypeFqn}, to=${spec.toTypeFqn}"
                    )
                )
                continue
            }

            val buildResult = buildRootMethodText(spec, methodName, helperContext)
            val method = factory.createMethodFromText(buildResult.text, targetClass)
            val anchor = targetClass.rBrace
            if (anchor == null) {
                LOG.error(
                    DiagnosticLog.message(
                        traceId,
                        "MAPPER",
                        "Mapper method could not be inserted because class has no closing brace. " +
                            "class=${targetClass.qualifiedName ?: targetClass.name}"
                    )
                )
                continue
            }
            targetClass.addBefore(parserFacade.createWhiteSpaceFromText("\n\n    "), anchor)
            val addedMethod = targetClass.addBefore(method, anchor)
            javaCodeStyle.shortenClassReferences(addedMethod)
            codeStyle.reformat(addedMethod)
            targetClass.addBefore(parserFacade.createWhiteSpaceFromText("\n"), anchor)
            added++
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "MAPPER",
                    "Mapper method added. method=$methodName, from=${spec.fromTypeFqn}, to=${spec.toTypeFqn}, " +
                        "mappedFields=${buildResult.assignmentCount}"
                )
            )
        }
        for (helperMethod in helperContext.helperMethodsByKey.values) {
            if (hasMethodWithSignature(targetClass, helperMethod.name, helperMethod.text)) {
                continue
            }
            val helper = factory.createMethodFromText(helperMethod.text, targetClass)
            val anchor = targetClass.rBrace
            if (anchor == null) {
                LOG.error(
                    DiagnosticLog.message(
                        traceId,
                        "MAPPER",
                        "Mapper helper method could not be inserted because class has no closing brace. " +
                            "class=${targetClass.qualifiedName ?: targetClass.name}, helper=${helperMethod.name}"
                    )
                )
                continue
            }
            targetClass.addBefore(parserFacade.createWhiteSpaceFromText("\n\n    "), anchor)
            val addedHelper = targetClass.addBefore(helper, anchor)
            javaCodeStyle.shortenClassReferences(addedHelper)
            codeStyle.reformat(addedHelper)
            targetClass.addBefore(parserFacade.createWhiteSpaceFromText("\n"), anchor)
            added++
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "MAPPER",
                    "Mapper helper method added. helper=${helperMethod.name}"
                )
            )
        }
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "MAPPER",
                "Mapper update completed. file=$filePath, added=$added, skipped=$skipped"
            )
        )
    }

    private fun hasEquivalentMapMethod(targetClass: PsiClass, spec: MapperMethodSpec, methodName: String): Boolean =
        targetClass.methods.any { method ->
            if (method.name != methodName) return@any false
            if (method.parameterList.parametersCount != 1) return@any false

            val parameterType = method.parameterList.parameters[0].type
            typeMatches(parameterType, spec.fromTypeFqn, spec.fromClass) &&
                typeMatches(method.returnType, spec.toTypeFqn, spec.toClass)
        }

    private fun typeMatches(type: PsiType?, expectedFqn: String, expectedClass: PsiClass): Boolean {
        if (type == null) return false
        val canonicalText = type.canonicalText
        return canonicalText == expectedFqn || canonicalText == expectedClass.name
    }

    private fun buildRootMethodText(
        spec: MapperMethodSpec,
        methodName: String,
        context: HelperBuildContext
    ): MethodBuildResult =
        buildMappingMethodText(
            visibility = "public",
            methodName = methodName,
            fromTypeFqn = spec.fromTypeFqn,
            toTypeFqn = spec.toTypeFqn,
            fromClass = spec.fromClass,
            toClass = spec.toClass,
            context = context
        )

    private fun buildHelperMethodText(
        methodName: String,
        fromClass: PsiClass,
        toClass: PsiClass,
        context: HelperBuildContext
    ): MethodBuildResult =
        buildMappingMethodText(
            visibility = "private",
            methodName = methodName,
            fromTypeFqn = typeText(fromClass),
            toTypeFqn = typeText(toClass),
            fromClass = fromClass,
            toClass = toClass,
            context = context
        )

    private fun buildMappingMethodText(
        visibility: String,
        methodName: String,
        fromTypeFqn: String,
        toTypeFqn: String,
        fromClass: PsiClass,
        toClass: PsiClass,
        context: HelperBuildContext
    ): MethodBuildResult {
        if (fromClass.isEnum && toClass.isEnum) {
            val text = buildString {
                append(visibility).append(" static ").append(toTypeFqn).append(" ").append(methodName)
                    .append("(").append(fromTypeFqn).append(" from) {\n")
                append("        return ").append(toTypeFqn).append(".valueOf(from.name());\n")
                append("}\n")
            }
            return MethodBuildResult(text, 1)
        }

        val assignments = matchingFieldPairs(fromClass, toClass)
            .mapNotNull { fieldPair -> assignmentFor(fieldPair, context) }

        val text = buildString {
            append(visibility).append(" static ").append(toTypeFqn).append(" ").append(methodName)
                .append("(").append(fromTypeFqn).append(" from) {\n")
            append("        if (from == null) {\n")
            append("            return null;\n")
            append("        }\n")
            append("        ").append(toTypeFqn).append(" to = new ").append(toTypeFqn).append("();\n")
            if (assignments.isNotEmpty()) {
                append(assignments.joinToString(separator = "\n")).append("\n")
            }
            append("        return to;\n")
            append("}\n")
        }
        return MethodBuildResult(text, assignments.size)
    }

    private fun assignmentFor(fieldPair: FieldPair, context: HelperBuildContext): String? {
        val suffix = NameUtils.capitalize(fieldPair.name)
        val sourceExpression = "from.get$suffix()"
        val assignmentExpression = when {
            canAssignDirectly(fieldPair.fromField.type, fieldPair.toField.type) -> sourceExpression
            else -> enumMappingExpression(fieldPair.fromField.type, fieldPair.toField.type, sourceExpression)
                ?: nestedMappingExpression(fieldPair.fromField.type, fieldPair.toField.type, sourceExpression, context)
        } ?: return null

        return "        to.set$suffix($assignmentExpression);"
    }

    private fun nestedMappingExpression(
        fromType: PsiType,
        toType: PsiType,
        sourceExpression: String,
        context: HelperBuildContext
    ): String? {
        val fromElementType = listElementType(fromType)
        val toElementType = listElementType(toType)
        if (fromElementType != null && toElementType != null) {
            enumMappingExpression(fromElementType, toElementType, "item")?.let { itemMapping ->
                return "$sourceExpression == null ? null : " +
                    "$sourceExpression.stream().map(item -> $itemMapping).collect(java.util.stream.Collectors.toList())"
            }

            val fromElementClass = resolveMappableClass(fromElementType) ?: return null
            val toElementClass = resolveMappableClass(toElementType) ?: return null
            val helperName = ensureHelperMethod(fromElementClass, toElementClass, context)
            return "$sourceExpression == null ? null : " +
                "$sourceExpression.stream().map(item -> $helperName(item)).collect(java.util.stream.Collectors.toList())"
        }

        val fromClass = resolveMappableClass(fromType) ?: return null
        val toClass = resolveMappableClass(toType) ?: return null
        val helperName = ensureHelperMethod(fromClass, toClass, context)
        return "$helperName($sourceExpression)"
    }

    private fun enumMappingExpression(fromType: PsiType, toType: PsiType, sourceExpression: String): String? {
        val fromClass = resolveClass(fromType) ?: return null
        val toClass = resolveClass(toType) ?: return null
        if (!fromClass.isEnum || !toClass.isEnum) return null
        return "${typeText(toClass)}.valueOf($sourceExpression.name())"
    }

    private fun ensureHelperMethod(fromClass: PsiClass, toClass: PsiClass, context: HelperBuildContext): String {
        val key = ClassPairKey(typeText(fromClass), typeText(toClass))
        context.helperNamesByKey[key]?.let { return it }

        val methodName = uniqueHelperName(helperMethodName(toClass), typeText(fromClass), typeText(toClass), context)
        context.helperNamesByKey[key] = methodName

        if (!context.helperKeysInProgress.add(key)) {
            return methodName
        }
        val buildResult = buildHelperMethodText(methodName, fromClass, toClass, context)
        context.helperMethodsByKey[key] = HelperMethod(methodName, buildResult.text)
        context.methodReturnTypesBySignature[MethodSignature(methodName, typeText(fromClass))] = typeText(toClass)
        context.helperKeysInProgress.remove(key)
        return methodName
    }

    private fun matchingFieldPairs(fromClass: PsiClass, toClass: PsiClass): List<FieldPair> {
        val fromFieldsByName = fromClass.fields
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .mapNotNull { field -> field.name?.let { it to field } }
            .toMap()

        return toClass.fields
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .mapNotNull { toField ->
                val fieldName = toField.name ?: return@mapNotNull null
                val fromField = fromFieldsByName[fieldName] ?: return@mapNotNull null
                FieldPair(fieldName, fromField, toField)
            }
    }

    private fun canAssignDirectly(fromType: PsiType, toType: PsiType): Boolean =
        fromType.canonicalText == toType.canonicalText || toType.isAssignableFrom(fromType)

    private fun listElementType(type: PsiType): PsiType? {
        val classType = type as? PsiClassType ?: return null
        val qualifiedName = classType.resolve()?.qualifiedName ?: return null
        if (qualifiedName !in LIST_TYPE_NAMES) return null
        return classType.parameters.singleOrNull()
    }

    private fun resolveMappableClass(type: PsiType): PsiClass? {
        val psiClass = resolveClass(type) ?: return null
        val qualifiedName = psiClass.qualifiedName ?: return psiClass
        if (qualifiedName.startsWith("java.") ||
            qualifiedName.startsWith("javax.") ||
            qualifiedName.startsWith("jakarta.") ||
            qualifiedName.startsWith("kotlin.")
        ) {
            return null
        }
        return psiClass
    }

    private fun resolveClass(type: PsiType): PsiClass? =
        (type as? PsiClassType)?.resolve()

    private fun helperMethodName(toClass: PsiClass): String =
        mapperMethodName(toClass)

    private fun mapperMethodName(toClass: PsiClass): String =
        "mapTo${toClass.name ?: "Object"}"

    private fun uniqueHelperName(
        baseName: String,
        fromTypeFqn: String,
        toTypeFqn: String,
        context: HelperBuildContext
    ): String {
        var candidate = baseName
        var index = 2
        while (hasConflictingReturnType(candidate, fromTypeFqn, toTypeFqn, context)) {
            candidate = "$baseName$index"
            index++
        }
        return candidate
    }

    private fun hasConflictingReturnType(
        methodName: String,
        fromTypeFqn: String,
        toTypeFqn: String,
        context: HelperBuildContext
    ): Boolean {
        val existingReturnType = context.methodReturnTypesBySignature[MethodSignature(methodName, fromTypeFqn)]
            ?: return false
        return existingReturnType != toTypeFqn
    }

    private fun typeText(psiClass: PsiClass): String =
        psiClass.qualifiedName ?: psiClass.name ?: "java.lang.Object"

    private fun hasMethodWithSignature(targetClass: PsiClass, methodName: String, methodText: String): Boolean {
        val parsedMethod = JavaPsiFacade.getElementFactory(project).createMethodFromText(methodText, targetClass)
        return targetClass.methods.any { method ->
            if (method.name != methodName) return@any false
            if (method.parameterList.parametersCount != parsedMethod.parameterList.parametersCount) return@any false
            val parametersMatch = method.parameterList.parameters.zip(parsedMethod.parameterList.parameters)
                .all { (existing, parsed) -> existing.type.canonicalText == parsed.type.canonicalText }
            parametersMatch && method.returnType?.canonicalText == parsedMethod.returnType?.canonicalText
        }
    }

    private companion object {
        val LOG = Logger.getInstance(MapperClassUpdater::class.java)
        val LIST_TYPE_NAMES = setOf("java.util.List", "java.util.Collection")
    }
}

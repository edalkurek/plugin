package org.example.ykplugin.generator

// DTO variant. The "Adc" infix is added only for BACKEND.
//
// Example naming:
//  - Remote :  AddressDto,    RequestGetUser,    ResponseGetUser
//  - Backend:  AddressAdcDto, RequestAdcGetUser, ResponseAdcGetUser
enum class DtoVariant(val infix: String) {
    REMOTE(""),
    BACKEND("Adc")
}

// Central place for class and method naming rules.
object NamingStrategy {

    fun requestClassName(serviceName: String, variant: DtoVariant): String =
        "Request${variant.infix}${NameUtils.capitalize(serviceName)}"

    fun responseClassName(serviceName: String, variant: DtoVariant): String =
        "Response${variant.infix}${NameUtils.capitalize(serviceName)}"

    fun schemaClassName(schemaName: String, variant: DtoVariant): String =
        "${NameUtils.capitalize(schemaName.withoutDtoSuffix())}${variant.infix}Dto"

    fun schemaEnumClassName(schemaName: String, variant: DtoVariant): String =
        NameUtils.toPascalCase(schemaName.withoutDtoSuffix()).ifBlank { "Value" } + enumVariantSuffix(variant)

    fun fieldEnumClassName(propertyName: String, variant: DtoVariant): String =
        NameUtils.toPascalCase(propertyName).ifBlank { "Value" } + enumVariantSuffix(variant)

    fun rootEnumClassName(className: String, variant: DtoVariant): String =
        className.withoutBackendRootInfix().removeSuffix("Enum") + enumVariantSuffix(variant)

    fun rootClassKind(className: String): DtoRootClassKind? = when {
        className.endsWith("Request") -> DtoRootClassKind.REQUEST
        className.endsWith("Response") -> DtoRootClassKind.RESPONSE
        else -> null
    }

    fun methodName(serviceName: String): String =
        NameUtils.decapitalize(serviceName)

    private fun String.withoutDtoSuffix(): String =
        if (endsWith("Dto", ignoreCase = true)) dropLast(3) else this

    private fun String.withoutBackendRootInfix(): String = when {
        startsWith("RequestAdc") -> "Request" + removePrefix("RequestAdc")
        startsWith("ResponseAdc") -> "Response" + removePrefix("ResponseAdc")
        else -> this
    }

    private fun enumVariantSuffix(variant: DtoVariant): String =
        if (variant == DtoVariant.BACKEND) variant.infix else ""
}


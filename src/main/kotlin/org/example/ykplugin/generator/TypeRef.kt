package org.example.ykplugin.generator

// Java type reference for a generated field / return type.
//  - ClassRef : a generated DTO in this variant's package (used by simple name)
//  - ListRef  : java.util.List<...>
//  - MapRef   : java.util.Map<java.lang.String, ...>
//  - JavaType : a built-in type such as java.* (FQN)
sealed interface TypeRef {
    data class ClassRef(val simpleName: String) : TypeRef
    data class ListRef(val element: TypeRef) : TypeRef
    data class MapRef(val value: TypeRef) : TypeRef
    data class JavaType(val fqn: String) : TypeRef
}

// Type text used inside the DTO file itself (same package).
fun TypeRef.toFieldType(): String = when (this) {
    is TypeRef.ClassRef -> simpleName
    is TypeRef.ListRef -> "java.util.List<${element.toFieldType()}>"
    is TypeRef.MapRef -> "java.util.Map<java.lang.String, ${value.toFieldType()}>"
    is TypeRef.JavaType -> fqn
}

// Fully qualified type text used from another package (e.g. service interface).
fun TypeRef.toFqn(pkg: String?): String = when (this) {
    is TypeRef.ClassRef -> if (pkg.isNullOrBlank()) simpleName else "$pkg.$simpleName"
    is TypeRef.ListRef -> "java.util.List<${element.toFqn(pkg)}>"
    is TypeRef.MapRef -> "java.util.Map<java.lang.String, ${value.toFqn(pkg)}>"
    is TypeRef.JavaType -> fqn
}


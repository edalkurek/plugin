package org.example.ykplugin.generator

object StandardBaseTypes {
    const val REMOTE_REST_DTO = "com.ykb.adc.rest.model.BaseRestDTO"
    const val BACKEND_REQUEST = "com.ykb.adc.commons.model.BaseRequest"
    const val BACKEND_RESPONSE = "com.ykb.adc.commons.model.BaseResponse"

    fun requestType(variant: DtoVariant): String = when (variant) {
        DtoVariant.REMOTE -> REMOTE_REST_DTO
        DtoVariant.BACKEND -> BACKEND_REQUEST
    }

    fun responseType(variant: DtoVariant): String = when (variant) {
        DtoVariant.REMOTE -> REMOTE_REST_DTO
        DtoVariant.BACKEND -> BACKEND_RESPONSE
    }

    fun rootBaseClass(variant: DtoVariant, rootClassKind: DtoRootClassKind?): String? = when (rootClassKind) {
        DtoRootClassKind.REQUEST -> requestType(variant)
        DtoRootClassKind.RESPONSE -> responseType(variant)
        null -> null
    }
}

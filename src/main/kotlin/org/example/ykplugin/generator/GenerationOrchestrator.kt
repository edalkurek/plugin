package org.example.ykplugin.generator

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.example.ykplugin.diagnostics.DiagnosticLog
import org.example.ykplugin.model.EndpointInfo
import org.example.ykplugin.model.OpenApiModel

data class GenerationRequest(
    val model: OpenApiModel,
    val selectedEndpoints: List<EndpointInfo>,
    val remoteDtoPath: String,
    val backendDtoPath: String,
    val remoteServicePath: String,
    val backendServicePath: String,
    val mapperClassPath: String
)

sealed interface GenerationResult {
    data class Success(
        val generatedEndpointCount: Int,
        val warnings: List<String> = emptyList()
    ) : GenerationResult

    data class Error(val message: String) : GenerationResult
}

// Coordinates DTO, service, mapper and SQL generation.
class GenerationOrchestrator(private val project: Project) {

    private companion object {
        val LOG = Logger.getInstance(GenerationOrchestrator::class.java)
    }

    fun generate(request: GenerationRequest): GenerationResult {
        val traceId = request.model.diagnosticTraceId.ifBlank { DiagnosticLog.newTraceId() }
        val started = System.nanoTime()
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "GENERATION",
                "Generation started. project=${project.name}, selectedEndpoints=${request.selectedEndpoints.size}, " +
                    "modelEndpoints=${request.model.endpoints.size}, modelSchemas=${request.model.schemas.size}, " +
                    "serviceName=${request.model.serviceName}"
            )
        )
        request.selectedEndpoints.forEachIndexed { index, endpoint ->
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "GENERATION",
                    "Selected endpoint ${index + 1}/${request.selectedEndpoints.size}. " +
                        "method=${endpoint.httpMethod}, path=${endpoint.path}, service=${endpoint.serviceName}, " +
                        "request={${DiagnosticLog.schemaSummary(endpoint.requestSchema)}}, " +
                        "response={${DiagnosticLog.schemaSummary(endpoint.responseSchema)}}"
                )
            )
        }

        return try {
            generateInternal(request, traceId, started)
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "GENERATION",
                    "Generation failed after ${DiagnosticLog.elapsedMillis(started)} ms. " +
                        "exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            GenerationResult.Error("Kod uretimi basarisiz: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun generateInternal(
        request: GenerationRequest,
        traceId: String,
        started: Long
    ): GenerationResult {
        val remoteDir = toPsiDir(request.remoteDtoPath)
            ?: return failure(traceId, "Remote DTO klasoru bulunamadi: ${request.remoteDtoPath}")
        val backendDir = toPsiDir(request.backendDtoPath)
            ?: return failure(traceId, "Backend DTO klasoru bulunamadi: ${request.backendDtoPath}")
        val remoteServiceFile = toPsiJavaFile(request.remoteServicePath)
            ?: return failure(traceId, "Remote servis dosyasi gecerli bir Java dosyasi degil: ${request.remoteServicePath}")
        val backendServiceFile = toPsiJavaFile(request.backendServicePath)
            ?: return failure(traceId, "Backend servis dosyasi gecerli bir Java dosyasi degil: ${request.backendServicePath}")
        val mapperClassFile = toPsiJavaFile(request.mapperClassPath)
            ?: return failure(traceId, "Mapper class dosyasi gecerli bir Java dosyasi degil: ${request.mapperClassPath}")

        val remotePkg = JavaDirectoryService.getInstance().getPackage(remoteDir)?.qualifiedName
        val backendPkg = JavaDirectoryService.getInstance().getPackage(backendDir)?.qualifiedName
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "GENERATION",
                "Generation targets resolved. remoteDtoDir=${remoteDir.virtualFile.path}, " +
                    "remotePackage=${remotePkg ?: "default"}, backendDtoDir=${backendDir.virtualFile.path}, " +
                    "backendPackage=${backendPkg ?: "default"}, " +
                    "remoteService=${remoteServiceFile.virtualFile?.path}, " +
                    "backendService=${backendServiceFile.virtualFile?.path}, " +
                    "mapper=${mapperClassFile.virtualFile?.path}"
            )
        )

        val dtoErrors = mutableListOf<String>()

        WriteCommandAction.runWriteCommandAction(project, "Swagger Kod Uretimi", null, {
            LOG.info(DiagnosticLog.message(traceId, "GENERATION", "Write command started."))
            val schemas = request.model.schemas
            val remoteGen = DtoGenerator(project, remoteDir, remotePkg, DtoVariant.REMOTE, schemas, traceId)
            val backendGen = DtoGenerator(project, backendDir, backendPkg, DtoVariant.BACKEND, schemas, traceId)

            val remoteMethods = ArrayList<MethodSpec>()
            val backendMethods = ArrayList<MethodSpec>()
            val mapperMethods = ArrayList<MapperMethodSpec>()

            for (endpoint in request.selectedEndpoints) {
                try {
                    remoteMethods.add(buildMethod(endpoint, remoteGen, DtoVariant.REMOTE, remotePkg, traceId))
                } catch (e: Exception) {
                    LOG.error(
                        DiagnosticLog.message(
                            traceId,
                            "GENERATION",
                            "Remote endpoint generation failed. method=${endpoint.httpMethod}, " +
                                "path=${endpoint.path}, service=${endpoint.serviceName}, message=${e.message}"
                        ),
                        e
                    )
                    dtoErrors.add("Remote - ${endpoint.serviceName}: ${e.message}")
                }

                try {
                    backendMethods.add(buildMethod(endpoint, backendGen, DtoVariant.BACKEND, backendPkg, traceId))
                } catch (e: Exception) {
                    LOG.error(
                        DiagnosticLog.message(
                            traceId,
                            "GENERATION",
                            "Backend endpoint generation failed. method=${endpoint.httpMethod}, " +
                                "path=${endpoint.path}, service=${endpoint.serviceName}, message=${e.message}"
                        ),
                        e
                    )
                    dtoErrors.add("Backend - ${endpoint.serviceName}: ${e.message}")
                }
            }

            for (endpoint in request.selectedEndpoints) {
                try {
                    val requestSchema = endpoint.requestSchema
                    if (requestSchema == null) {
                        LOG.info(
                            DiagnosticLog.message(
                                traceId,
                                "MAPPER",
                                "Request mapper preparation skipped: endpoint has no request schema. " +
                                    "method=${endpoint.httpMethod}, path=${endpoint.path}"
                            )
                        )
                    } else {
                        val remoteRequestType = remoteGen.generateClassFor(
                            NamingStrategy.requestClassName(endpoint.serviceName, DtoVariant.REMOTE),
                            requestSchema,
                            DtoRootClassKind.REQUEST
                        )
                        val backendRequestType = backendGen.generateClassFor(
                            NamingStrategy.requestClassName(endpoint.serviceName, DtoVariant.BACKEND),
                            requestSchema,
                            DtoRootClassKind.REQUEST
                        )
                        val remoteRequestClass = findGeneratedClass(remoteRequestType, remoteDir, traceId, "remote")
                        val backendRequestClass = findGeneratedClass(backendRequestType, backendDir, traceId, "backend")
                        if (remoteRequestClass != null && backendRequestClass != null) {
                            mapperMethods.add(
                                MapperMethodSpec(
                                    fromTypeFqn = backendRequestType.toFqn(backendPkg),
                                    toTypeFqn = remoteRequestType.toFqn(remotePkg),
                                    fromClass = backendRequestClass,
                                    toClass = remoteRequestClass
                                )
                            )
                            LOG.info(
                                DiagnosticLog.message(
                                    traceId,
                                    "MAPPER",
                                    "Request mapper specification prepared. endpoint=${endpoint.serviceName}, " +
                                        "from=${backendRequestType.toFqn(backendPkg)}, " +
                                        "to=${remoteRequestType.toFqn(remotePkg)}"
                                )
                            )
                        } else {
                            LOG.warn(
                                DiagnosticLog.message(
                                    traceId,
                                    "MAPPER",
                                    "Request mapper specification skipped because generated PSI class is missing. " +
                                        "endpoint=${endpoint.serviceName}, remoteFound=${remoteRequestClass != null}, " +
                                        "backendFound=${backendRequestClass != null}"
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    LOG.error(
                        DiagnosticLog.message(
                            traceId,
                            "MAPPER",
                            "Request mapper preparation failed. endpoint=${endpoint.serviceName}, message=${e.message}"
                        ),
                        e
                    )
                    dtoErrors.add("Request Mapper - ${endpoint.serviceName}: ${e.message}")
                }

                try {
                    val responseSchema = endpoint.responseSchema
                    if (responseSchema == null) {
                        LOG.info(
                            DiagnosticLog.message(
                                traceId,
                                "MAPPER",
                                "Mapper preparation skipped: endpoint has no response schema. " +
                                    "method=${endpoint.httpMethod}, path=${endpoint.path}"
                            )
                        )
                        continue
                    }

                    val remoteResponseType = remoteGen.generateClassFor(
                        NamingStrategy.responseClassName(endpoint.serviceName, DtoVariant.REMOTE),
                        responseSchema,
                        DtoRootClassKind.RESPONSE
                    )
                    val backendResponseType = backendGen.generateClassFor(
                        NamingStrategy.responseClassName(endpoint.serviceName, DtoVariant.BACKEND),
                        responseSchema,
                        DtoRootClassKind.RESPONSE
                    )
                    val remoteResponseClass = findGeneratedClass(remoteResponseType, remoteDir, traceId, "remote")
                    val backendResponseClass = findGeneratedClass(backendResponseType, backendDir, traceId, "backend")
                    if (remoteResponseClass != null && backendResponseClass != null) {
                        mapperMethods.add(
                            MapperMethodSpec(
                                fromTypeFqn = remoteResponseType.toFqn(remotePkg),
                                toTypeFqn = backendResponseType.toFqn(backendPkg),
                                fromClass = remoteResponseClass,
                                toClass = backendResponseClass
                            )
                        )
                        LOG.info(
                            DiagnosticLog.message(
                                traceId,
                                "MAPPER",
                                "Mapper specification prepared. endpoint=${endpoint.serviceName}, " +
                                    "from=${remoteResponseType.toFqn(remotePkg)}, " +
                                    "to=${backendResponseType.toFqn(backendPkg)}"
                            )
                        )
                    } else {
                        LOG.warn(
                            DiagnosticLog.message(
                                traceId,
                                "MAPPER",
                                "Mapper specification skipped because generated PSI class is missing. " +
                                    "endpoint=${endpoint.serviceName}, remoteFound=${remoteResponseClass != null}, " +
                                    "backendFound=${backendResponseClass != null}"
                            )
                        )
                    }
                } catch (e: Exception) {
                    LOG.error(
                        DiagnosticLog.message(
                            traceId,
                            "MAPPER",
                            "Mapper preparation failed. endpoint=${endpoint.serviceName}, message=${e.message}"
                        ),
                        e
                    )
                    dtoErrors.add("Mapper - ${endpoint.serviceName}: ${e.message}")
                }
            }

            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "GENERATION",
                    "DTO generation summary. remoteCount=${remoteGen.generatedDtoCount}, " +
                        "remoteClasses=${remoteGen.generatedDtoNames}, backendCount=${backendGen.generatedDtoCount}, " +
                        "backendClasses=${backendGen.generatedDtoNames}, errors=${dtoErrors.size}"
                )
            )

            val updater = ServiceInterfaceUpdater(project, traceId)
            updater.addMethods(remoteServiceFile, remoteMethods)
            updater.addMethods(backendServiceFile, backendMethods)
            MapperClassUpdater(project, traceId).addMethods(mapperClassFile, mapperMethods)

            val sqlServiceName = JavaPackagePathResolver.fromSourcePath(request.remoteServicePath)
                ?: remoteServiceFile.classes.firstOrNull()?.qualifiedName
                ?: request.model.serviceName
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "SQL",
                    "SQL service name resolved. value=$sqlServiceName"
                )
            )

            SqlScriptGenerator(project, traceId).generateAndOpen(
                request.selectedEndpoints,
                request.model.swaggerUrl,
                sqlServiceName
            )
            LOG.info(DiagnosticLog.message(traceId, "GENERATION", "Write command completed."))
        })

        try {
            VirtualFileManager.getInstance().asyncRefresh(null)
            LOG.info(DiagnosticLog.message(traceId, "GENERATION", "Virtual file system refresh scheduled."))
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "GENERATION",
                    "Virtual file system refresh failed. exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }

        LOG.info(
            DiagnosticLog.message(
                traceId,
                "GENERATION",
                "Generation completed. selectedEndpoints=${request.selectedEndpoints.size}, " +
                    "warnings=${dtoErrors.size}, elapsedMs=${DiagnosticLog.elapsedMillis(started)}"
            )
        )
        return GenerationResult.Success(request.selectedEndpoints.size, dtoErrors)
    }

    private fun buildMethod(
        endpoint: EndpointInfo,
        generator: DtoGenerator,
        variant: DtoVariant,
        pkg: String?,
        traceId: String
    ): MethodSpec {
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "SERVICE",
                "Building service method. variant=$variant, endpoint=${endpoint.serviceName}, " +
                    "method=${endpoint.httpMethod}, path=${endpoint.path}"
            )
        )
        val returnType = endpoint.responseSchema
            ?.let {
                generator.generateClassFor(
                    NamingStrategy.responseClassName(endpoint.serviceName, variant),
                    it,
                    DtoRootClassKind.RESPONSE
                ).toFqn(pkg)
            }
            ?: StandardBaseTypes.responseType(variant)
        val paramType = endpoint.requestSchema
            ?.let {
                generator.generateClassFor(
                    NamingStrategy.requestClassName(endpoint.serviceName, variant),
                    it,
                    DtoRootClassKind.REQUEST
                ).toFqn(pkg)
            }
            ?: StandardBaseTypes.requestType(variant)
        val method = MethodSpec(NamingStrategy.methodName(endpoint.serviceName), returnType, paramType)
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "SERVICE",
                "Service method resolved. variant=$variant, name=${method.name}, " +
                    "returnType=${method.returnTypeFqn}, paramType=${method.paramTypeFqn ?: "none"}"
            )
        )
        return method
    }

    private fun findGeneratedClass(
        typeRef: TypeRef,
        dir: PsiDirectory,
        traceId: String,
        side: String
    ): PsiClass? {
        val className = (typeRef as? TypeRef.ClassRef)?.simpleName
        if (className == null) {
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "MAPPER",
                    "Generated type is not a class; mapper PSI lookup skipped. side=$side, type=$typeRef"
                )
            )
            return null
        }
        val file = dir.findFile("$className.java") as? PsiJavaFile
        if (file == null) {
            LOG.warn(
                DiagnosticLog.message(
                    traceId,
                    "MAPPER",
                    "Generated DTO file not found for mapper. side=$side, class=$className, dir=${dir.virtualFile.path}"
                )
            )
            return null
        }
        val result = file.classes.firstOrNull { it.name == className }
        if (result == null) {
            LOG.warn(
                DiagnosticLog.message(
                    traceId,
                    "MAPPER",
                    "Generated DTO PSI class not found in file. side=$side, class=$className, file=${file.virtualFile?.path}"
                )
            )
        }
        return result
    }

    private fun toPsiDir(path: String): PsiDirectory? {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return null
        if (!vf.isDirectory) return null
        return PsiManager.getInstance(project).findDirectory(vf)
    }

    private fun toPsiJavaFile(path: String): PsiJavaFile? {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return null
        return PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile
    }

    private fun failure(traceId: String, message: String): GenerationResult.Error {
        LOG.error(DiagnosticLog.message(traceId, "GENERATION", message))
        return GenerationResult.Error(message)
    }
}

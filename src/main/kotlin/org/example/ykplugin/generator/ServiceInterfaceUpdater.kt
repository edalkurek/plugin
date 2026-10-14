package org.example.ykplugin.generator

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import org.example.ykplugin.diagnostics.DiagnosticLog

// Describes a method signature to add to an interface.
data class MethodSpec(
    val name: String,
    val returnTypeFqn: String,
    val paramTypeFqn: String?
)

// Appends new method signatures to the end of an existing Java interface body
// without breaking existing code. Methods with an already existing name are skipped.
// Types are passed as FQN; imports are added automatically.
class ServiceInterfaceUpdater(
    private val project: Project,
    private val traceId: String
) {

    fun addMethods(serviceFile: PsiJavaFile, methods: List<MethodSpec>) {
        val filePath = serviceFile.virtualFile?.path ?: serviceFile.name
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "SERVICE",
                "Service update started. file=$filePath, requestedMethods=${methods.size}"
            )
        )
        try {
            addMethodsInternal(serviceFile, methods, filePath)
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "SERVICE",
                    "Service update failed. file=$filePath, exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }
    }

    private fun addMethodsInternal(serviceFile: PsiJavaFile, methods: List<MethodSpec>, filePath: String) {
        val psiClass: PsiClass = serviceFile.classes.firstOrNull { it.isInterface }
            ?: serviceFile.classes.firstOrNull()
            ?: run {
                LOG.error(
                    DiagnosticLog.message(
                        traceId,
                        "SERVICE",
                        "Service update aborted: no Java class or interface found. file=$filePath"
                    )
                )
                return
            }

        LOG.info(
            DiagnosticLog.message(
                traceId,
                "SERVICE",
                "Target service class resolved. file=$filePath, class=${psiClass.qualifiedName ?: psiClass.name}, " +
                    "isInterface=${psiClass.isInterface}, existingMethods=${psiClass.methods.size}"
            )
        )
        val factory = JavaPsiFacade.getElementFactory(project)
        val javaCodeStyle = JavaCodeStyleManager.getInstance(project)
        val codeStyle = CodeStyleManager.getInstance(project)
        val existingNames = psiClass.methods.map { it.name }.toMutableSet()
        var added = 0
        var skipped = 0

        for (spec in methods) {
            if (!existingNames.add(spec.name)) {
                skipped++
                LOG.info(
                    DiagnosticLog.message(
                        traceId,
                        "SERVICE",
                        "Method skipped because name already exists. file=$filePath, method=${spec.name}"
                    )
                )
                continue
            }

            val signature = if (spec.paramTypeFqn == null) {
                "${spec.returnTypeFqn} ${spec.name}();"
            } else {
                "${spec.returnTypeFqn} ${spec.name}(${spec.paramTypeFqn} request);"
            }
            val method = factory.createMethodFromText(signature, psiClass)
            val addedMethod = psiClass.add(method)
            javaCodeStyle.shortenClassReferences(addedMethod)
            codeStyle.reformat(addedMethod)
            added++
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "SERVICE",
                    "Method added. file=$filePath, signature=$signature"
                )
            )
        }

        LOG.info(
            DiagnosticLog.message(
                traceId,
                "SERVICE",
                "Service update completed. file=$filePath, added=$added, skipped=$skipped"
            )
        )
    }

    private companion object {
        val LOG = Logger.getInstance(ServiceInterfaceUpdater::class.java)
    }
}


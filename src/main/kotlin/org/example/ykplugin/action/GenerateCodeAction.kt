package org.example.ykplugin.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import org.example.ykplugin.diagnostics.DiagnosticLog
import org.example.ykplugin.generator.GenerationOrchestrator
import org.example.ykplugin.generator.GenerationResult
import org.example.ykplugin.ui.GeneratorDialog

// Main action triggered from the Tools menu. Opens the wizard and starts
// generation after the user clicks "Generate".
class GenerateCodeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            LOG.error(DiagnosticLog.message("no-trace", "ACTION", "Action invoked without an open project."))
            return
        }
        LOG.info(
            DiagnosticLog.message(
                "no-trace",
                "ACTION",
                "Swagger generator action invoked. project=${project.name}"
            )
        )
        val dialog = GeneratorDialog(project)
        if (!dialog.showAndGet()) {
            LOG.info(DiagnosticLog.message("no-trace", "ACTION", "Generator dialog cancelled."))
            return
        }

        val request = dialog.buildRequest()
        if (request == null) {
            LOG.error(DiagnosticLog.message("no-trace", "ACTION", "Dialog accepted but generation request is null."))
            return
        }
        val traceId = request.model.diagnosticTraceId.ifBlank { "no-trace" }
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "ACTION",
                "Generation request accepted. selectedEndpoints=${request.selectedEndpoints.size}"
            )
        )
        when (val result = GenerationOrchestrator(project).generate(request)) {
            is GenerationResult.Success -> {
                LOG.info(
                    DiagnosticLog.message(
                        traceId,
                        "ACTION",
                        "Generation result received. status=success, endpoints=${result.generatedEndpointCount}, " +
                            "warnings=${result.warnings.size}"
                    )
                )
                if (result.warnings.isEmpty()) {
                    Messages.showInfoMessage(
                        project,
                        "${result.generatedEndpointCount} endpoint i\u00e7in \u00fcretim tamamland\u0131.",
                        "Ba\u015far\u0131l\u0131"
                    )
                } else {
                    Messages.showWarningDialog(
                        project,
                        "${result.generatedEndpointCount} endpoint i\u00e7in \u00fcretim tamamland\u0131, " +
                            "ancak baz\u0131 DTO'lar olu\u015fturulamad\u0131:\n\n" +
                            result.warnings.joinToString("\n"),
                        "K\u0131smen Tamamland\u0131"
                    )
                }
            }
            is GenerationResult.Error -> {
                LOG.error(
                    DiagnosticLog.message(
                        traceId,
                        "ACTION",
                        "Generation result received. status=error, message=${result.message}"
                    )
                )
                Messages.showErrorDialog(project, result.message, "\u00dcretim Hatas\u0131")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    private companion object {
        val LOG = Logger.getInstance(GenerateCodeAction::class.java)
    }
}


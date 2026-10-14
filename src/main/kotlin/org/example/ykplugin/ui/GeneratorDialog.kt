package org.example.ykplugin.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import org.example.ykplugin.diagnostics.DiagnosticLog
import org.example.ykplugin.generator.GenerationRequest
import org.example.ykplugin.model.EndpointInfo
import org.example.ykplugin.model.OpenApiModel
import org.example.ykplugin.parser.SwaggerParser
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel

// Main wizard dialog (DialogWrapper).
//
// Flow:
//  1. Enter a Swagger/OpenAPI URL or select a JSON file, then load endpoints.
//  2. Select desired endpoints via checkboxes.
//  3. Pick target paths (Remote DTO, Backend DTO, Remote Service, Backend Service, Mapper Class).
//  4. Click "Generate" to trigger generation.
class GeneratorDialog(private val project: Project) : DialogWrapper(project) {

    private companion object {
        val LOG = Logger.getInstance(GeneratorDialog::class.java)
    }

    private val urlField = TextFieldWithBrowseButton()
    private val loadButton = JButton("Y\u00fckle")

    private val rootNode = CheckedTreeNode("Endpoints")
    private val tree = CheckboxTree(createRenderer(), rootNode)

    private val remoteDtoField = TextFieldWithBrowseButton()
    private val backendDtoField = TextFieldWithBrowseButton()
    private val remoteServiceField = TextFieldWithBrowseButton()
    private val backendServiceField = TextFieldWithBrowseButton()
    private val mapperClassField = TextFieldWithBrowseButton()

    private var model: OpenApiModel? = null

    init {
        title = "YKB Rest Service Generator"
        setOKButtonText("Olu\u015ftur")
        setCancelButtonText("\u0130ptal")
        setupBrowseButtons()
        loadButton.addActionListener { loadSwagger() }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val urlPanel = JPanel(BorderLayout(JBUI.scale(5), 0)).apply {
            add(urlField, BorderLayout.CENTER)
            add(loadButton, BorderLayout.EAST)
        }
        val topForm = FormBuilder.createFormBuilder()
            .addLabeledComponent("Swagger/OpenAPI JSON:", urlPanel)
            .panel

        val treePanel = JPanel(BorderLayout()).apply {
            border = IdeBorderFactory.createTitledBorder("Endpoint Se\u00e7imi")
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }

        val pathForm = FormBuilder.createFormBuilder()
            .addLabeledComponent("Remote DTO Klasörü:", remoteDtoField)
            .addLabeledComponent("Backend DTO Klasörü:", backendDtoField)
            .addLabeledComponent("Remote Interface:", remoteServiceField)
            .addLabeledComponent("Backend Interface:", backendServiceField)
            .addLabeledComponent("Mapper Class:", mapperClassField)
            .panel

        return JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            add(topForm, BorderLayout.NORTH)
            add(treePanel, BorderLayout.CENTER)
            add(pathForm, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(620), JBUI.scale(520))
        }
    }

    private fun setupBrowseButtons() {
        val folderDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val javaFileDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withFileFilter { file: VirtualFile -> file.extension == "java" }
        val jsonFileDescriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle("Swagger/OpenAPI JSON Dosyasi Sec")
            .withDescription("Yerel Swagger/OpenAPI .json dosyasini secin")
            .withFileFilter { file: VirtualFile -> file.extension.equals("json", ignoreCase = true) }

        addBrowse(urlField, jsonFileDescriptor)
        addBrowse(remoteDtoField, folderDescriptor)
        addBrowse(backendDtoField, folderDescriptor)
        addBrowse(remoteServiceField, javaFileDescriptor)
        addBrowse(backendServiceField, javaFileDescriptor)
        addBrowse(mapperClassField, javaFileDescriptor)
    }

    private fun addBrowse(field: TextFieldWithBrowseButton, descriptor: FileChooserDescriptor) {
        field.addActionListener {
            FileChooser.chooseFile(descriptor, project, null)?.let { field.text = it.path }
        }
    }

    private fun loadSwagger() {
        val source = urlField.text.trim()
        if (source.isEmpty()) {
            LOG.warn(DiagnosticLog.message("no-trace", "UI", "Swagger load rejected because source is blank."))
            Messages.showErrorDialog(
                project,
                "L\u00fctfen bir Swagger/OpenAPI URL veya JSON dosya yolu girin.",
                "Kaynak Gerekli"
            )
            return
        }
        val traceId = DiagnosticLog.newTraceId()
        val started = System.nanoTime()
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "UI",
                "Load button clicked. project=${project.name}, source=${DiagnosticLog.safeUrl(source)}"
            )
        )
        try {
            val loaded = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<OpenApiModel, Exception> { SwaggerParser().parseSource(source, traceId) },
                "Swagger Y\u00fckleniyor...",
                true,
                project
            )
            model = loaded
            populateTree(loaded)
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "UI",
                    "Swagger model loaded into dialog. endpoints=${loaded.endpoints.size}, " +
                        "schemas=${loaded.schemas.size}, elapsedMs=${DiagnosticLog.elapsedMillis(started)}"
                )
            )
            if (loaded.endpoints.isEmpty()) {
                LOG.warn(
                    DiagnosticLog.message(
                        traceId,
                        "UI",
                        "Loaded document contains no supported HTTP operations."
                    )
                )
                Messages.showWarningDialog(project, "Dok\u00fcmanda endpoint bulunamad\u0131.", "Uyar\u0131")
            }
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "UI",
                    "Swagger load failed after ${DiagnosticLog.elapsedMillis(started)} ms. " +
                        "exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            Messages.showErrorDialog(project, "Y\u00fckleme ba\u015far\u0131s\u0131z: ${e.message}", "Hata")
        }
    }

    private fun populateTree(model: OpenApiModel) {
        LOG.info(
            DiagnosticLog.message(
                model.diagnosticTraceId.ifBlank { "no-trace" },
                "UI",
                "Populating endpoint tree. endpoints=${model.endpoints.size}, " +
                    "tags=${model.endpoints.map { it.tag ?: "default" }.distinct().size}"
            )
        )
        rootNode.removeAllChildren()
        model.endpoints.groupBy { it.tag ?: "default" }.forEach { (tag, endpoints) ->
            val tagNode = CheckedTreeNode(tag).apply { isChecked = false }
            endpoints.forEach { endpoint ->
                tagNode.add(CheckedTreeNode(endpoint).apply { isChecked = false })
            }
            rootNode.add(tagNode)
        }
        (tree.model as DefaultTreeModel).reload()
        TreeUtil.expandAll(tree)
        LOG.info(
            DiagnosticLog.message(
                model.diagnosticTraceId.ifBlank { "no-trace" },
                "UI",
                "Endpoint tree populated. rootChildren=${rootNode.childCount}"
            )
        )
    }

    private fun selectedEndpoints(): List<EndpointInfo> =
        tree.getCheckedNodes(EndpointInfo::class.java, null).toList()

    private fun createRenderer(): CheckboxTree.CheckboxTreeCellRenderer =
        object : CheckboxTree.CheckboxTreeCellRenderer() {
            override fun customizeRenderer(
                tree: JTree?, value: Any?, selected: Boolean,
                expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ) {
                val node = value as? CheckedTreeNode ?: return
                when (val obj = node.userObject) {
                    is EndpointInfo -> {
                        textRenderer.append("${obj.httpMethod} ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                        textRenderer.append(obj.path)
                        textRenderer.append("  (${obj.serviceName})", SimpleTextAttributes.GRAY_ATTRIBUTES)
                    }
                    is String -> textRenderer.append(obj, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
            }
        }

    override fun doValidate(): ValidationInfo? = when {
        model == null -> ValidationInfo("\u00d6nce Swagger URL'sini veya JSON dosyasini y\u00fckleyin.", urlField)
        selectedEndpoints().isEmpty() -> ValidationInfo("En az bir endpoint se\u00e7in.", tree)
        remoteDtoField.text.isBlank() -> ValidationInfo("Remote DTO yolu gerekli.", remoteDtoField)
        backendDtoField.text.isBlank() -> ValidationInfo("Backend DTO yolu gerekli.", backendDtoField)
        remoteServiceField.text.isBlank() -> ValidationInfo("Remote servis dosyas\u0131 gerekli.", remoteServiceField)
        backendServiceField.text.isBlank() -> ValidationInfo("Backend servis dosyas\u0131 gerekli.", backendServiceField)
        mapperClassField.text.isBlank() -> ValidationInfo("Mapper class dosyasi gerekli.", mapperClassField)
        else -> null
    }

    // Collects generation inputs after OK.
    fun buildRequest(): GenerationRequest? {
        val currentModel = model
        if (currentModel == null) {
            LOG.error(DiagnosticLog.message("no-trace", "UI", "Generation request could not be built: model is null."))
            return null
        }
        val selected = selectedEndpoints()
        LOG.info(
            DiagnosticLog.message(
                currentModel.diagnosticTraceId.ifBlank { "no-trace" },
                "UI",
                "Building generation request. selectedEndpoints=${selected.size}, " +
                    "remoteDtoPath=${remoteDtoField.text.trim()}, backendDtoPath=${backendDtoField.text.trim()}, " +
                    "remoteServicePath=${remoteServiceField.text.trim()}, " +
                    "backendServicePath=${backendServiceField.text.trim()}, mapperPath=${mapperClassField.text.trim()}"
            )
        )
        return GenerationRequest(
            model = currentModel,
            selectedEndpoints = selected,
            remoteDtoPath = remoteDtoField.text.trim(),
            backendDtoPath = backendDtoField.text.trim(),
            remoteServicePath = remoteServiceField.text.trim(),
            backendServicePath = backendServiceField.text.trim(),
            mapperClassPath = mapperClassField.text.trim()
        )
    }
}


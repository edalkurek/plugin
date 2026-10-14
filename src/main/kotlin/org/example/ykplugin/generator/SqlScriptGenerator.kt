package org.example.ykplugin.generator

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.example.ykplugin.diagnostics.DiagnosticLog
import org.example.ykplugin.model.EndpointInfo

// Generates the REST remote registry SQL script (NGI_REST_REMOTE_REGISTRIES) for the
// selected endpoints and opens it in the editor as a new scratch SQL tab.
//
// For every endpoint a DELETE + INSERT pair is produced. The TEST/UAT/LIVE endpoint
// URLs are derived from the Swagger URL entered in the wizard (see buildEnvUrls).
class SqlScriptGenerator(
    private val project: Project,
    private val traceId: String
) {

    private companion object {
        val LOG = Logger.getInstance(SqlScriptGenerator::class.java)
        // Service timeout written into each registry row.
        // NOTE: section 1 of the spec states 5000, while the sample INSERT shows 2000.
        // Using 5000 as the explicit field default; change this single value if 2000 is wanted.
        const val SERVICE_TIMEOUT = 5000

        // Trailing Swagger suffixes to strip so only the context/base path remains.
        val API_DOCS_VERSIONED = Regex("/v\\d+/api-docs/?$")
        val API_DOCS_PLAIN = Regex("/api-docs/?$")
        val CLOUD_DOMAIN_SEGMENT = Regex("(?<=\\.)(?:cldnp|cld)(?=\\.)", RegexOption.IGNORE_CASE)
    }

    private data class EnvUrls(val test: String, val uat: String, val live: String)

    fun generateAndOpen(
        endpoints: List<EndpointInfo>,
        swaggerUrl: String,
        serviceName: String
    ) {
        LOG.info(
            DiagnosticLog.message(
                traceId,
                "SQL",
                "SQL generation started. endpoints=${endpoints.size}, serviceName=$serviceName, " +
                    "swaggerUrl=${DiagnosticLog.safeUrl(swaggerUrl)}"
            )
        )
        try {
            val language = Language.findLanguageByID("SQL") ?: PlainTextLanguage.INSTANCE
            val sql = buildSql(endpoints, swaggerUrl, serviceName)
            val file = ScratchRootType.getInstance().createScratchFile(
                project,
                "generated_services.sql",
                language,
                sql,
                ScratchFileService.Option.create_new_always
            )
            if (file == null) {
                LOG.error(
                    DiagnosticLog.message(
                        traceId,
                        "SQL",
                        "SQL scratch file could not be created. chars=${sql.length}"
                    )
                )
                return
            }

            FileEditorManager.getInstance(project).openFile(file, true)
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "SQL",
                    "SQL generation completed. file=${file.path}, chars=${sql.length}, endpoints=${endpoints.size}"
                )
            )
        } catch (e: Exception) {
            LOG.error(
                DiagnosticLog.message(
                    traceId,
                    "SQL",
                    "SQL generation failed. exception=${e.javaClass.name}, message=${e.message}"
                ),
                e
            )
            throw e
        }
    }

    private fun buildSql(
        endpoints: List<EndpointInfo>,
        swaggerUrl: String,
        serviceName: String
    ): String {
        val service = sqlEscape(serviceName)
        val sb = StringBuilder()
        sb.append("-- Auto-generated REST remote registry script\n")
        sb.append("-- SERVICE_NAME: ").append(serviceName).append('\n')
        sb.append("-- Source URL : ").append(swaggerUrl).append("\n\n")

        for (e in endpoints) {
            val methodName = sqlEscape(NamingStrategy.methodName(e.serviceName))
            val httpMethod = sqlEscape(e.httpMethod.uppercase())
            val urls = buildEnvUrls(swaggerUrl, e.path)
            LOG.info(
                DiagnosticLog.message(
                    traceId,
                    "SQL",
                    "Endpoint URLs derived. method=${e.httpMethod}, path=${e.path}, " +
                        "test=${urls.test}, uat=${urls.uat}, live=${urls.live}"
                )
            )

            sb.append("DELETE FROM NGI_REST_REMOTE_REGISTRIES WHERE SERVICE_NAME = '")
                .append(service).append("' AND METHOD_NAME = '").append(methodName).append("';\n")

            sb.append("INSERT INTO NGI_REST_REMOTE_REGISTRIES ")
                .append("(SERVICE_NAME, METHOD_NAME, HTTP_METHOD, TEST_ENDPOINT_URL, ")
                .append("UAT_ENDPOINT_URL, LIVE_ENDPOINT_URL, SERVICE_TIMEOUT)\n")
            sb.append("VALUES ('").append(service)
                .append("', '").append(methodName)
                .append("', '").append(httpMethod)
                .append("', '").append(sqlEscape(urls.test))
                .append("', '").append(sqlEscape(urls.uat))
                .append("', '").append(sqlEscape(urls.live))
                .append("', ").append(SERVICE_TIMEOUT).append(");\n\n")
        }
        return sb.toString()
    }

    // Derives TEST/UAT/LIVE endpoint URLs from the Swagger URL + the endpoint path.
    //
    // Rules:
    //  - The ".troubleshoot" host cluster segment is swapped to ".be".
    //  - The Swagger suffix ("/v3/api-docs") is stripped, leaving the context/base path,
    //    then the endpoint path is appended.
    //  - TEST keeps "-tst", UAT replaces "-tst" with "-uat", LIVE removes "-tst".
    //  - TEST/UAT use the "cldnp" domain segment; LIVE uses "cld".
    //  - Generated endpoint URLs always use HTTPS, regardless of the Swagger URL scheme.
    //
    // Example:
    //  in  : https://app-tst.troubleshoot.cldnp.example.com/ctx/v3/api-docs  (+ /entry)
    //  test: https://app-tst.be.cldnp.example.com/ctx/entry
    //  uat : https://app-uat.be.cldnp.example.com/ctx/entry
    //  live: https://app.be.cld.example.com/ctx/entry
    private fun buildEnvUrls(swaggerUrl: String, endpointPath: String): EnvUrls {
        val schemeSep = swaggerUrl.indexOf("://")
        val afterScheme = if (schemeSep >= 0) swaggerUrl.substring(schemeSep + 3) else swaggerUrl

        val firstSlash = afterScheme.indexOf('/')
        val host = if (firstSlash >= 0) afterScheme.substring(0, firstSlash) else afterScheme
        val rawPath = if (firstSlash >= 0) afterScheme.substring(firstSlash) else ""

        // Context/base path = everything before the Swagger api-docs suffix.
        val basePath = rawPath
            .replaceFirst(API_DOCS_VERSIONED, "")
            .replaceFirst(API_DOCS_PLAIN, "")
            .trimEnd('/')

        val ep = endpointPath.trim().let { if (it.startsWith("/")) it else "/$it" }

        // Common rule: the ".troubleshoot" cluster becomes ".be".
        val baseHost = host.replace(".troubleshoot", ".be")

        val testHost = baseHost.replace(CLOUD_DOMAIN_SEGMENT, "cldnp")
        val uatHost = baseHost
            .replace("-tst", "-uat")
            .replace(CLOUD_DOMAIN_SEGMENT, "cldnp")
        val liveHostWithoutEnvironment = if (baseHost.contains("-tst.")) {
            baseHost.replace("-tst.", ".")
        } else {
            baseHost.replace("-tst", "")
        }
        val liveHost = liveHostWithoutEnvironment.replace(CLOUD_DOMAIN_SEGMENT, "cld")

        fun assemble(h: String) = "https://$h$basePath$ep"
        return EnvUrls(assemble(testHost), assemble(uatHost), assemble(liveHost))
    }

    private fun sqlEscape(value: String): String = value.replace("'", "''")
}


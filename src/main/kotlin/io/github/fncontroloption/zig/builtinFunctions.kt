package io.github.fncontroloption.zig

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import io.github.fncontroloption.zig.psi2.ZigBuiltinFnProto
import io.github.fncontroloption.zig.psi2.createZigFile
import org.jsoup.Jsoup
import java.io.InputStream

@Service(Service.Level.PROJECT)
class ZigBuiltinFunctionsService(private val project: Project) {
    private var cachedBuiltinFunctions: Map<String, ZigBuiltinFnProto>? = null

    val builtinFunctions: Map<String, ZigBuiltinFnProto>
        get() {
            // We do an initial null check here to avoid unnecessary
            // acquisitions of the write lock.
            if (cachedBuiltinFunctions == null) {
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().runWriteAction {
                        loadBuiltinFunctions()
                    }
                }
            }
            return cachedBuiltinFunctions ?: emptyMap()
        }

    val modificationTracker = SimpleModificationTracker()

    private fun loadBuiltinFunctions() {
        if (cachedBuiltinFunctions == null) {
            val stream = javaClass.classLoader.getResourceAsStream("langref.html")!!
            val parserService = project.service<ZigParserService>()
            val previousMode = parserService.mode
            parserService.mode = ZigParserMode.BUILTIN_FN_PROTO
            try {
                cachedBuiltinFunctions = stream.use { parseBuiltinsFromLangref(project, it) }
            } finally {
                parserService.mode = previousMode
            }
            modificationTracker.incModificationCount()
        }
    }

    companion object {
        fun getBuiltinFnProto(project: Project, name: String): ZigBuiltinFnProto? {
            return project.service<ZigBuiltinFunctionsService>().builtinFunctions[name]
        }

        fun getModificationTracker(project: Project): ModificationTracker {
            return project.service<ZigBuiltinFunctionsService>().modificationTracker
        }
    }
}

/* TODO: delete
data class ZigBuiltinFunction(
    val name: String,
    val parameters: List<Parameter>,
    val returnType: String,
) {
    override fun toString() = buildString {
        append(name)
        append('(')
        for ((i, parameter) in parameters.withIndex()) {
            if (i > 0) append(", ")
            append(parameter)
        }
        append(") ")
        append(returnType)
    }

    data class Parameter(
        val comptime: Boolean,
        val noalias: Boolean,
        val name: String,
        val type: String?,
    ) {
        override fun toString() = buildString {
            if (comptime) append("comptime ")
            if (noalias) append("noalias ")
            append(name)
            if (type != null) {
                append(": ")
                append(type)
            }
        }
    }
}
 */

private fun parseBuiltinsFromLangref(project: Project, stream: InputStream): Map<String, ZigBuiltinFnProto> {
    val document = Jsoup.parse(stream, null, "")

    val builtinFunctionsElement = document.getElementById("Builtin-Functions")!!
    val builtinFunctionsTag = builtinFunctionsElement.tagName()
    check(builtinFunctionsTag == "h2")

    val builtinMap = mutableMapOf<String, ZigBuiltinFnProto>()

    var element = builtinFunctionsElement.nextElementSibling()
    while (element != null && element.tagName() != builtinFunctionsTag) {
        if (element.tagName() == "pre" && element.previousElementSibling()?.tagName() == "h3") {
            val text = element.text()
            val file = createZigFile(project, text)
            val element = file.firstChild as ZigBuiltinFnProto
            builtinMap[element.builtinElement.text] = element
        }
        element = element.nextElementSibling()
    }

    return builtinMap
}

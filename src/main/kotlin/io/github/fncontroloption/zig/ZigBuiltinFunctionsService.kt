package io.github.fncontroloption.zig

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.testFramework.LightVirtualFile
import io.github.fncontroloption.zig.psi2.ZigBuiltinFnProto
import org.jsoup.Jsoup
import java.io.InputStream

@Service(Service.Level.PROJECT)
class ZigBuiltinFunctionsService(private val project: Project) {
    private var cachedBuiltinFunctions: Map<String, ZigBuiltinFnProto>? = null

    val builtinFunctions: Map<String, ZigBuiltinFnProto>
        get() = cachedBuiltinFunctions ?: emptyMap()

    val modificationTracker = SimpleModificationTracker()

    fun loadBuiltinFunctions() {
        if (cachedBuiltinFunctions == null) {
            val stream = javaClass.classLoader.getResourceAsStream("langref.html")!!
            cachedBuiltinFunctions = stream.use { parseBuiltinsFromLangref(project, it) }
            modificationTracker.incModificationCount()
        }
    }

    companion object {
        fun getAll(project: Project): Collection<ZigBuiltinFnProto> {
            return project.service<ZigBuiltinFunctionsService>().builtinFunctions.values
        }

        fun getBuiltinFnProto(project: Project, name: String): ZigBuiltinFnProto? {
            return project.service<ZigBuiltinFunctionsService>().builtinFunctions[name]
        }

        fun getModificationTracker(project: Project): ModificationTracker {
            return project.service<ZigBuiltinFunctionsService>().modificationTracker
        }
    }
}

private const val BUILTIN_FN_PROTO_FILE_HEADER =
    """const std = @import("std");
const AtomicOrder = std.lang.AtomicOrder;
const AtomicRmwOp = std.lang.AtomicRmwOp;
const BranchHint = std.lang.BranchHint;
const FloatMode = std.lang.FloatMode;
const PrefetchOptions = std.lang.PrefetchOptions;"""

private fun parseBuiltinsFromLangref(project: Project, stream: InputStream): Map<String, ZigBuiltinFnProto> {
    val document = Jsoup.parse(stream, null, "")

    val builtinFunctionsElement = document.getElementById("Builtin-Functions")!!
    val builtinFunctionsTag = builtinFunctionsElement.tagName()
    check(builtinFunctionsTag == "h2")

    val builtinMap = mutableMapOf<String, ZigBuiltinFnProto>()

    var element = builtinFunctionsElement.nextElementSibling()
    while (element != null && element.tagName() != builtinFunctionsTag) {
        val previousElement = element.previousElementSibling()
        if (element.tagName() == "pre" && previousElement?.tagName() == "h3") {
            val fileName = "@${previousElement.id()}.zig"
            val fileText = "${BUILTIN_FN_PROTO_FILE_HEADER}\n${element.text()}"
            val virtualFile = LightVirtualFile(fileName, ZigBuiltinFnProtoLanguage, fileText)
            virtualFile.isWritable = false
            val psiFile = virtualFile.findPsiFile(project)!!
            val element = psiFile.lastChild as ZigBuiltinFnProto
            builtinMap[element.builtinElement.text] = element
        }
        element = element.nextElementSibling()
    }

    return builtinMap
}

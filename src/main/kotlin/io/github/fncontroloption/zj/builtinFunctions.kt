package io.github.fncontroloption.zj

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.InputStream

class ZigBuiltinFunctionsProjectActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        readAction {
            project.service<ZigBuiltinFunctionsService>().loadBuiltinFunctions()
        }
    }
}

@Service(Service.Level.PROJECT)
class ZigBuiltinFunctionsService(private val project: Project) {
    var builtinFunctions: Map<String, ZigBuiltinFnProto> = emptyMap()
        private set

    val modificationTracker = SimpleModificationTracker()

    @RequiresReadLock
    fun loadBuiltinFunctions() {
        val stream = javaClass.classLoader.getResourceAsStream("langref.html")!!
        builtinFunctions = stream.use { parseBuiltinsFromLangref(project, it) }
        modificationTracker.incModificationCount()
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

@RequiresReadLock
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
            val psiElement = psiFile.lastChild as ZigBuiltinFnProto
            psiElement.doc = getBuiltinDoc(element.nextElementSibling())
            builtinMap[psiElement.builtinElement.text] = psiElement
        }
        element = element.nextElementSibling()
    }

    return builtinMap
}

private fun getBuiltinDoc(sibling: Element?): HtmlChunk {
    val builder = HtmlBuilder()
    var sibling = sibling
    while (sibling != null && sibling.tagName() != "h2" && sibling.tagName() != "h3") {
        updateBuiltinDocLinks(sibling)
        builder.appendRaw(sibling.outerHtml())
        sibling = sibling.nextElementSibling()
    }
    return builder.toFragment()
}

private fun updateBuiltinDocLinks(element: Element) {
    for (link in element.select("a[href]")) {
        val href = link.attr("href")
        if (href.startsWith('#')) {
            link.attr("href", "https://ziglang.org/documentation/0.17.0/$href")
        }
    }
}

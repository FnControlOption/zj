package io.github.fncontroloption.zig

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import org.jsoup.Jsoup
import java.io.InputStream

@Service(Service.Level.APP)
class ZigBuiltinFunctionsService {
    private var cachedBuiltinFunctions: Map<String, ZigBuiltinFunction>? = null

    val builtinFunctions: Map<String, ZigBuiltinFunction>
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

    private fun loadBuiltinFunctions() {
        if (cachedBuiltinFunctions == null) {
            val stream = javaClass.classLoader.getResourceAsStream("langref.html")!!
            cachedBuiltinFunctions = stream.use { parseBuiltinsFromLangref(it) }
        }
    }
}

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

private fun parseBuiltinsFromLangref(stream: InputStream): Map<String, ZigBuiltinFunction> {
    val document = Jsoup.parse(stream, null, "")

    val builtinFunctionsElement = document.getElementById("Builtin-Functions")!!
    val builtinFunctionsTag = builtinFunctionsElement.tagName()
    check(builtinFunctionsTag == "h2")

    val builtinMap = mutableMapOf<String, ZigBuiltinFunction>()

    var element = builtinFunctionsElement.nextElementSibling()
    while (element != null && element.tagName() != builtinFunctionsTag) {
        if (element.tagName() == "pre" && element.previousElementSibling()?.tagName() == "h3") {
            val builtin = parseBuiltin(element.text())
            builtinMap[builtin.name] = builtin
        }
        element = element.nextElementSibling()
    }

    return builtinMap
}

private fun parseBuiltin(originalText: String): ZigBuiltinFunction {
    val text =
        originalText
            .replace("""\s*//.*""".toRegex(), "")
            .replace("""\s+""".toRegex(), " ")
            .replace("( ", "(")
            .replace(", )", ")")

    var index = text.indexOf('(')
    check(index > 0)

    val name = text.substring(0, index)

    index += 1
    var paramStart = index
    val parameters = mutableListOf<ZigBuiltinFunction.Parameter>()

    var parens = 1
    var brackets = 0
    var braces = 0

    while (true) {
        when (text[index]) {
            '(' -> {
                parens += 1
                index += 1
            }

            ')' -> {
                parens -= 1
                if (parens == 0) {
                    check(brackets == 0)
                    check(braces == 0)
                    if (index != paramStart) {
                        val paramText = text.substring(paramStart, index)
                        parameters.add(parseParameter(paramText))
                    }
                    break
                }
                index += 1
            }

            '[' -> {
                brackets += 1
                index += 1
            }

            ']' -> {
                brackets -= 1
                check(brackets >= 0)
                index += 1
            }

            '{' -> {
                braces += 1
                index += 1
            }

            '}' -> {
                braces -= 1
                check(braces >= 0)
                index += 1
            }

            ',' if parens == 1 && brackets == 0 && braces == 0 -> {
                val paramText = text.substring(paramStart, index)
                parameters.add(parseParameter(paramText))
                index += 1
                while (text[index].isWhitespace()) {
                    index += 1
                }
                paramStart = index
            }

            else -> index += 1
        }
    }

    check(text[index] == ')')
    index += 1
    while (text[index].isWhitespace()) {
        index += 1
    }

    val returnType = text.substring(index)
    return ZigBuiltinFunction(name, parameters, returnType)
}

private fun parseParameter(paramText: String): ZigBuiltinFunction.Parameter {
    var index = 0

    val comptime = paramText.startsWith("comptime ")
    if (comptime) index += "comptime ".length

    val noalias = paramText.startsWith("noalias ")
    if (noalias) index += "noalias ".length

    val name: String
    val type: String?
    val colon = paramText.indexOf(':', index)
    if (colon >= 0) {
        name = paramText.substring(index, colon)
        index = colon + 1
        while (paramText[index].isWhitespace()) {
            index += 1
        }
        type = paramText.substring(index)
    } else {
        name = paramText.substring(index)
        type = null
    }

    return ZigBuiltinFunction.Parameter(comptime, noalias, name, type)
}

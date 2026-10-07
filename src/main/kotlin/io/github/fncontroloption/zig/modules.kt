package io.github.fncontroloption.zig

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.findFile
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.util.concurrency.annotations.RequiresReadLock
import io.github.fncontroloption.zig.psi2.ZigBuiltinCall
import io.github.fncontroloption.zig.psi2.ZigStringLiteral
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ZigModulesProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val appService = ApplicationManager.getApplication().service<ZigModulesAppService>()
        appService.copyPrintModulesFile()

        project.workspaceModel.eventLog.collect {
            val projectService = project.service<ZigModulesProjectService>()
            withBackgroundProgress(project, "Fetching Zig modules\u2026") {
                projectService.fetchModules()
            }
            withBackgroundProgress(project, "Loading Zig modules\u2026") {
                smartReadAction(project) {
                    projectService.loadFileToModulesMap()
                }
            }
        }
    }
}

@Service(Service.Level.APP)
class ZigModulesAppService {
    private var copiedPrintModulesFile = false

    val printModulesFilePath: Path by lazy {
        val pluginId = PluginId.getId("io.github.fncontroloption.zig")
        val pluginDescriptor = PluginManagerCore.getPlugin(pluginId)!!
        pluginDescriptor.pluginPath.resolve("print_modules.zig")
    }

    fun copyPrintModulesFile() {
        if (copiedPrintModulesFile) return
        val outputPath = printModulesFilePath
        val inputStream = javaClass.classLoader.getResourceAsStream("print_modules.zig")!!
        val inputText = inputStream.reader().use { it.readText() }
        if (!outputPath.exists() || outputPath.readText() != inputText) {
            outputPath.writeText(inputText)
        }
        copiedPrintModulesFile = true
    }
}

@Serializable
data class ZigModule(
    val index: Int,

    /** @see ZigModulesProjectService.getRootSourceVirtualFile */
    @SerialName("root_source_file")
    val rootSourceFile: String?,

    @SerialName("import_table")
    val importTable: Map<String, Int>,
)

@Service(Service.Level.PROJECT)
class ZigModulesProjectService(private val project: Project) {
    private var cachedSdk: Sdk? = null
    private var cachedBuildFile: VirtualFile? = null
    private var cachedModules: Map<Int, ZigModule>? = null
    private var fileToModules = mutableMapOf<Int, MutableSet<Int>>()
    private val psiManager = PsiManager.getInstance(project)

    val modules: Map<Int, ZigModule>
        get() = cachedModules ?: emptyMap()

    val modificationTracker = SimpleModificationTracker()

    fun fetchModules() {
        val currentSdk = ProjectRootManager.getInstance(project).projectSdk
        if (cachedSdk == null || cachedSdk != currentSdk) {
            cachedSdk = null
            cachedBuildFile = null
            cachedModules = null
            if (currentSdk != null) {
                cachedSdk = currentSdk
                fetchModulesWithSdk(currentSdk)
                fileToModules.clear()
                modificationTracker.incModificationCount()
            }
        }
    }

    private fun fetchModulesWithSdk(sdk: Sdk) {
        val buildFile = findBuildZigFile() ?: return
        val buildFilePath = buildFile.toNioPath().toString()
        val printConfigArgs = arrayOf("build", "--build-file", buildFilePath, "--print-configuration-path")
        val configPath = getZigExeOutput(sdk, *printConfigArgs)?.trimEnd() ?: return

        val appService = ApplicationManager.getApplication().service<ZigModulesAppService>()
        val printModulesPath = appService.printModulesFilePath.toString()
        val printModulesArgs = arrayOf("run", printModulesPath, "--", configPath)
        val modulesText = getZigExeOutput(sdk, *printModulesArgs) ?: return

        val modules = Json.decodeFromString<List<ZigModule>>(modulesText)
        cachedModules = modules.associateBy { it.index }
        cachedBuildFile = buildFile

        val projectDir = buildFile.parent
        projectDir.refresh(false, true)
    }

    private fun findBuildZigFile(): VirtualFile? {
        val rootManager = ProjectRootManager.getInstance(project)
        return rootManager.contentRoots.firstNotNullOfOrNull { it.findFile("build.zig") }
    }

    fun getRootSourceVirtualFile(module: ZigModule): VirtualFile? {
        val projectDir = cachedBuildFile?.parent ?: return null
        val sourcePath = module.rootSourceFile ?: return null
        return projectDir.findFile(sourcePath)
    }

    @RequiresReadLock
    fun loadFileToModulesMap() {
        fileToModules.clear()
        for (module in modules.values) {
            getRootSourceVirtualFile(module)?.let { setFileModule(module.index, it) }
        }
        modificationTracker.incModificationCount()
        DaemonCodeAnalyzer.getInstance(project).restart("Zig modules loaded")
    }

    @RequiresReadLock
    private fun setFileModule(moduleIndex: Int, virtualFile: VirtualFile) {
        if (virtualFile !is VirtualFileWithId) return
        val modules = fileToModules.getOrPut(virtualFile.id) { mutableSetOf() }
        if (moduleIndex in modules) {
            return
        }
        modules.add(moduleIndex)
        val psiFile = psiManager.findFile(virtualFile) ?: return
        for (element in SyntaxTraverser.psiTraverser(psiFile)) {
            if (element !is ZigBuiltinCall) continue
            if (!element.builtinElement.textMatches("@import")) continue
            val arg = element.arguments.firstOrNull() ?: continue
            if (arg !is ZigStringLiteral) continue
            val value = arg.value ?: continue
            if (!value.endsWith(".zig")) continue
            virtualFile.parent.findFile(value)?.let { setFileModule(moduleIndex, it) }
        }
    }

    fun getFileModules(virtualFile: VirtualFile): Set<Int> {
        if (virtualFile !is VirtualFileWithId) return emptySet()
        return fileToModules[virtualFile.id] ?: emptySet()
    }

    companion object {
        fun getModificationTracker(project: Project): ModificationTracker {
            return project.service<ZigModulesProjectService>().modificationTracker
        }
    }
}

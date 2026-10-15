package io.github.fncontroloption.zig

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun findBuildZigFile(project: Project): VirtualFile? {
    val rootManager = ProjectRootManager.getInstance(project)
    return rootManager.contentRoots.firstNotNullOfOrNull { it.findFile("build.zig") }
}

class ZigModulesProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val appService = ApplicationManager.getApplication().service<ZigModulesAppService>()
        appService.copyPrintModulesFile()

        // TODO: detect deletion/creation of build.zig
        val buildFile = findBuildZigFile(project) ?: return
        val projectPath = buildFile.parent?.toNioPathOrNull() ?: return
        val projectAware = ZigExternalSystemProjectAware(project, projectPath)
        val projectTracker = ExternalSystemProjectTracker.getInstance(project)
        projectTracker.register(projectAware)
        projectTracker.activate(projectAware.projectId)
        projectTracker.markDirty(projectAware.projectId)
        projectTracker.scheduleProjectRefresh()

        project.workspaceModel.eventLog.collect {
            val projectService = project.service<ZigModulesProjectService>()
            projectService.fetchAndLoadModules(projectPath)
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

    @SerialName("is_root_module")
    val isRootModule: Boolean,

    /** @see ZigModulesProjectService.getRootSourceVirtualFile */
    @SerialName("root_source_file")
    val rootSourceFile: String?,

    @SerialName("import_table")
    val importTable: Map<String, Int>,
)

@Service(Service.Level.PROJECT)
class ZigModulesProjectService(private val project: Project) {
    private data class FetchSettings(
        val sdk: Sdk?,
        val buildArgs: List<String>,
        val projectPath: Path,
    )

    private var cachedFetchSettings: FetchSettings? = null
    private var cachedModules: Map<Int, ZigModule>? = null
    private var rootFileToModule: MutableMap<Path, Int> = ConcurrentHashMap()
    private var fileImports: MutableMap<Path, MutableSet<Path>> = ConcurrentHashMap()
    private val psiManager = PsiManager.getInstance(project)

    val modules: Map<Int, ZigModule>
        get() = cachedModules ?: emptyMap()

    val modificationTracker = SimpleModificationTracker()

    fun resetFetchSettings() {
        cachedFetchSettings = null
    }

    private suspend fun fetchModules(projectPath: Path): Boolean {
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val buildArgs = emptyList<String>() // TODO
        val currentFetchSettings = FetchSettings(sdk, buildArgs, projectPath)
        if (cachedFetchSettings != currentFetchSettings) {
            cachedFetchSettings = null
            cachedModules = null
            if (sdk != null) {
                cachedFetchSettings = currentFetchSettings
                fetchModulesWithSdk(sdk, buildArgs, projectPath)
                rootFileToModule.clear()
                fileImports.clear()
                return true
            }
        }
        return false
    }

    private suspend fun fetchModulesWithSdk(sdk: Sdk, buildArgs: List<String>, projectPath: Path) {
        val exe = ZigExe.find(sdk) ?: return
        val configPath = getConfigPath(exe, buildArgs, projectPath) ?: return
        val modulesText = getModulesJsonText(exe, configPath) ?: return

        val modules = Json.decodeFromString<List<ZigModule>>(modulesText)
        cachedModules = modules.associateBy { it.index }

        val projectDir = VirtualFileManager.getInstance().findFileByNioPath(projectPath)
        projectDir?.refresh(false, true)
    }

    private suspend fun getConfigPath(exe: ZigExe, buildArgs: List<String>, projectPath: Path): String? {
        val args = buildList {
            add("build")
            add("--print-configuration-path")
            addAll(buildArgs)
            add("--build-file")
            add(projectPath.resolve("build.zig").toString())
        }
        val printResult = exe.runInToolWindow(project, "Zig", args)
        printResult.logErrors()
        if (!printResult.success) return null
        return printResult.stdout.trimEnd()
    }

    private fun getModulesJsonText(exe: ZigExe, configPath: String): String? {
        val appService = ApplicationManager.getApplication().service<ZigModulesAppService>()
        val printModulesPath = appService.printModulesFilePath.toString()
        val modulesResult = exe.run("run", printModulesPath, "--", configPath)
        modulesResult.logErrors()
        if (!modulesResult.success) return null
        return modulesResult.stdout
    }

    /* TODO: delete
    private fun findBuildZigFile(): VirtualFile? {
        val rootManager = ProjectRootManager.getInstance(project)
        return rootManager.contentRoots.firstNotNullOfOrNull { it.findFile("build.zig") }
    }
     */

    fun getRootSourceVirtualFile(module: ZigModule): VirtualFile? {
        val projectPath = cachedFetchSettings?.projectPath ?: return null
        val sourcePathString = module.rootSourceFile ?: return null
        val sourcePath = projectPath.resolve(sourcePathString)
        return VirtualFileManager.getInstance().findFileByNioPath(sourcePath)
    }

    @RequiresReadLock
    private fun loadModules() {
        rootFileToModule.clear()
        fileImports.clear()
        for (module in modules.values) {
            val rootFile = getRootSourceVirtualFile(module) ?: continue
            val rootPath = rootFile.toNioPathOrNull() ?: continue
            rootFileToModule[rootPath] = module.index
            followImports(rootPath)
        }
        modificationTracker.incModificationCount()
        DaemonCodeAnalyzer.getInstance(project).restart("ZigModulesProjectService.loadModules")
    }

    @RequiresReadLock
    private fun followImports(path: Path) {
        if (fileImports.containsKey(path)) return
        val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(path) ?: return
        val psiFile = psiManager.findFile(virtualFile) ?: return
        if (psiFile !is ZigFile) return
        val imports = mutableSetOf<Path>()
        fileImports[path] = imports
        val targets = when (val stubTree = psiFile.stubTree) {
            null -> {
                SyntaxTraverser.psiTraverser(psiFile)
                    .asSequence()
                    .filterIsInstance<ZigBuiltinCall>()
                    .filter { it.builtinElement.textMatches("@import") }
                    .mapNotNull { it.arguments.firstOrNull() }
                    .filterIsInstance<ZigStringExpression>()
                    .mapNotNull { it.value }
            }
            else -> {
                stubTree.plainList
                    .asSequence()
                    .filterIsInstance<ZigImportStub>()
                    .mapNotNull { it.target }
            }
        }
        for (target in targets) {
            if (!target.endsWith(".zig")) continue
            val otherPath = path.resolveSibling(target).normalize()
            imports.add(otherPath)
            followImports(otherPath)
        }
    }

    fun getFileModules(virtualFile: VirtualFile): Set<Int> {
        val path = virtualFile.toNioPathOrNull() ?: return emptySet()
        val modules = mutableSetOf<Int>()
        findFileModules(path, mutableSetOf(), modules)
        return modules
    }

    private fun findFileModules(path: Path, visited: MutableSet<Path>, modules: MutableSet<Int>) {
        if (path in visited) return
        visited.add(path)
        val module = rootFileToModule[path]
        if (module != null) {
            modules.add(module)
            return
        }
        for ((otherPath, otherImports) in fileImports) {
            if (path in otherImports) {
                findFileModules(otherPath, visited, modules)
            }
        }
    }

    fun getFileRootModules(virtualFile: VirtualFile): Set<Int> {
        val visited = mutableSetOf<Int>()
        val roots = mutableSetOf<Int>()
        for (moduleIndex in getFileModules(virtualFile)) {
            val module = modules[moduleIndex] ?: continue
            findRootModules(module, visited, roots)
        }
        return roots
    }

    private fun findRootModules(module: ZigModule, visited: MutableSet<Int>, roots: MutableSet<Int>) {
        if (module.index in visited) return
        visited.add(module.index)
        if (module.isRootModule) roots.add(module.index)
        for (otherModule in modules.values) {
            if (otherModule.index in visited) continue
            if (otherModule.importTable.containsValue(module.index)) {
                findRootModules(otherModule, visited, roots)
            }
        }
    }

    fun processVfsChange(fileActions: MutableMap<Path, ZigModulesAsyncFileListener.Action>) {
        for ((path, action) in fileActions) {
            when (action) {
                ZigModulesAsyncFileListener.Action.CREATE,
                ZigModulesAsyncFileListener.Action.CHANGE -> {
                    fileImports.remove(path)
                    followImports(path)
                }
                ZigModulesAsyncFileListener.Action.DELETE -> {
                    fileImports.remove(path)
                }
            }
        }
        modificationTracker.incModificationCount()
        DaemonCodeAnalyzer.getInstance(project).restart("ZigModulesProjectService.processVfsChange")
    }

    suspend fun fetchAndLoadModules(projectPath: Path): Boolean {
        val changed =
            withBackgroundProgress(project, "Fetching Zig modules\u2026") {
                fetchModules(projectPath)
            }

        if (changed) {
            withBackgroundProgress(project, "Loading Zig modules\u2026") {
                smartReadAction(project) {
                    loadModules()
                }
            }
        }

        return changed
    }

    companion object {
        fun getModificationTracker(project: Project): ModificationTracker {
            return project.service<ZigModulesProjectService>().modificationTracker
        }
    }
}

class ZigModulesAsyncFileListener : AsyncFileListener {
    enum class Action { CREATE, CHANGE, DELETE }

    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier {
        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                val fileActions = mutableMapOf<Path, Action>()
                for (evt in events) {
                    val (file, action) = when (evt) {
                        is VFileCreateEvent -> Pair(evt.file, Action.CREATE)
                        is VFileContentChangeEvent -> Pair(evt.file, Action.CHANGE)
                        is VFileDeleteEvent -> Pair(evt.file, Action.DELETE)
                        is VFileCopyEvent -> Pair(evt.findCreatedFile(), Action.CREATE)
                        else -> continue
                    }
                    val path = file?.toNioPathOrNull() ?: continue
                    fileActions[path] = action
                }
                if (fileActions.isEmpty()) {
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().runWriteAction {
                        for (project in ProjectManager.getInstance().openProjects) {
                            project.service<ZigModulesProjectService>().processVfsChange(fileActions)
                        }
                    }
                }
            }
        }
    }
}

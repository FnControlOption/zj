package io.github.fncontroloption.zj

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

class ZigModulesProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ZigModulesProjectService>()
    }
}

@Service(Service.Level.PROJECT)
class ZigModulesProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val daemonCodeAnalyzer = DaemonCodeAnalyzer.getInstance(project)
    private val projectPathFlow = project.service<ZigExternalSystemProjectService>().projectPath
    private val deferredFetchFlow = project.service<ZigBuildProjectService>().deferredFetch

    @Volatile
    private var cachedModules: Map<Int, ZigBuildConfig.Module> = emptyMap()

    val modules: Map<Int, ZigBuildConfig.Module>
        get() = cachedModules

    @Volatile
    private var cachedRootFileToModule: Map<Path, Int> = emptyMap()

    @Volatile
    private var cachedFileImports: Map<Path, MutableSet<Path>> = emptyMap()

    init {
        combine(projectPathFlow, deferredFetchFlow) { projectPath, deferredFetch ->
            cachedModules = emptyMap()
            cachedRootFileToModule = emptyMap()
            cachedFileImports = emptyMap()
            val fetchResult = try {
                deferredFetch.await()
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                return@combine
            }
            val buildConfig = when (fetchResult) {
                ZigBuildProjectService.FetchResult.None -> return@combine
                is ZigBuildProjectService.FetchResult.Err -> return@combine
                is ZigBuildProjectService.FetchResult.Ok -> fetchResult.buildConfig
            }
            if (projectPath == null) return@combine
            withBackgroundProgress(project, "Loading Zig modules\u2026") {
                smartReadAction(project) {
                    val modules = buildConfig.modules + getBuildFileModules(projectPath)
                    cachedModules = modules.associateBy { it.index }
                    findRootModulesAndFileImports(projectPath, modules)
                }
            }
        }.launchIn(coroutineScope)
    }

    @RequiresReadLock
    private fun getBuildFileModules(projectPath: Path): List<ZigBuildConfig.Module> {
        val configurer = ZigAnalyzer.findZigLibFile(project, null, "compiler/configurer.zig")
        val configurerImportTable = mutableMapOf<String, Int>()
        val configurerModule = configurer?.toNioPathOrNull()?.let {
            ZigBuildConfig.Module(
                index = -1,
                isRootModule = true,
                rootSourceFile = it.toString(),
                importTable = configurerImportTable,
            )
        }

        val buildRootToDeps = mutableMapOf<Path, Map<String, Path>>()
        followBuildFileGraph(projectPath, projectPath, buildRootToDeps)

        var moduleIndex = -2
        val buildRootToModule = mutableMapOf<Path, ZigBuildConfig.Module>()
        val root = getBuildFileModule(projectPath, buildRootToDeps, buildRootToModule) { moduleIndex-- }
        configurerImportTable["@build"] = root.index

        return listOfNotNull(configurerModule) + buildRootToModule.values
    }

    @RequiresReadLock
    private fun followBuildFileGraph(
        projectPath: Path,
        buildRoot: Path,
        buildRootToDeps: MutableMap<Path, Map<String, Path>>,
    ) {
        if (buildRoot in buildRootToDeps) return
        val deps = getBuildFileDependencies(projectPath, buildRoot)
        buildRootToDeps[buildRoot] = deps
        for (depPath in deps.values) {
            followBuildFileGraph(projectPath, depPath, buildRootToDeps)
        }
    }

    @RequiresReadLock
    private fun getBuildFileDependencies(projectPath: Path, buildRoot: Path): Map<String, Path> {
        val zonPath = buildRoot.resolve("build.zig.zon")
        val zonVFile = VirtualFileManager.getInstance().findFileByNioPath(zonPath) ?: return emptyMap()
        val zonFile = PsiManager.getInstance(project).findFile(zonVFile) ?: return emptyMap()
        if (zonFile !is ZonFile) return emptyMap()
        val root = zonFile.expression as? ZigListInit ?: return emptyMap()
        val dependencies = root.getFieldExpression("dependencies") as? ZigListInit ?: return emptyMap()
        val result = mutableMapOf<String, Path>()
        for (item in dependencies.initItems) {
            val name = parseZigIdentifier(item.fieldNameIdentifier) ?: continue
            val dep = item.expression as? ZigListInit ?: continue
            val pathExpr = dep.getFieldExpression("path")
            if (pathExpr != null) {
                if (pathExpr !is ZigStringExpression) continue
                val path = pathExpr.value ?: continue
                result[name] = buildRoot.resolve(path).normalize()
            } else {
                val hashExpr = dep.getFieldExpression("hash") as? ZigStringExpression ?: continue
                val hash = hashExpr.value ?: continue
                result[name] = projectPath.resolve("zig-pkg").resolve(hash)
            }
        }
        return result
    }

    private fun getBuildFileModule(
        buildRoot: Path,
        buildRootToDeps: Map<Path, Map<String, Path>>,
        buildRootToModule: MutableMap<Path, ZigBuildConfig.Module>,
        generateModuleIndex: () -> Int,
    ): ZigBuildConfig.Module {
        var module = buildRootToModule[buildRoot]
        if (module != null) {
            return module
        }
        val importTable = mutableMapOf<String, Int>()
        module = ZigBuildConfig.Module(
            index = generateModuleIndex(),
            isRootModule = false,
            rootSourceFile = buildRoot.resolve("build.zig").toString(),
            importTable = importTable,
        )
        buildRootToModule[buildRoot] = module
        for ((depName, depPath) in buildRootToDeps[buildRoot].orEmpty()) {
            val depModule = getBuildFileModule(depPath, buildRootToDeps, buildRootToModule, generateModuleIndex)
            importTable[depName] = depModule.index
        }
        return module
    }

    val modificationTracker = SimpleModificationTracker()

    @RequiresReadLock
    private fun findRootModulesAndFileImports(projectPath: Path, modules: Collection<ZigBuildConfig.Module>) {
        val rootFileToModule = mutableMapOf<Path, Int>()
        val fileImports = mutableMapOf<Path, MutableSet<Path>>()
        for (module in modules) {
            val rootFile = module.rootSourceFile ?: continue
            val rootPath = projectPath.resolve(rootFile) ?: continue
            rootFileToModule[rootPath] = module.index
            followImports(rootPath, fileImports)
        }
        cachedRootFileToModule = rootFileToModule
        cachedFileImports = fileImports
        modificationTracker.incModificationCount()
        daemonCodeAnalyzer.restart("ZigModulesProjectService.loadModules")
    }

    @RequiresReadLock
    private fun followImports(path: Path, fileImports: MutableMap<Path, MutableSet<Path>>) {
        if (fileImports.containsKey(path)) return
        val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(path) ?: return
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
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
            followImports(otherPath, fileImports)
        }
    }

    fun getModuleFromRootFile(path: Path): ZigBuildConfig.Module? {
        val moduleIndex = cachedRootFileToModule[path] ?: return null
        return cachedModules[moduleIndex]
    }

    fun getFileModules(virtualFile: VirtualFile): Set<Int> {
        val path = virtualFile.toNioPathOrNull() ?: return emptySet()
        val result = mutableSetOf<Int>()
        findFileModules(path, mutableSetOf(), result)
        return result
    }

    private fun findFileModules(path: Path, visited: MutableSet<Path>, result: MutableSet<Int>) {
        if (path in visited) return
        visited.add(path)
        val module = cachedRootFileToModule[path]
        if (module != null) {
            result.add(module)
            return
        }
        for ((otherPath, otherImports) in cachedFileImports) {
            if (path in otherImports) {
                findFileModules(otherPath, visited, result)
            }
        }
    }

    fun getFileRootModules(virtualFile: VirtualFile): Set<Int> {
        val visited = mutableSetOf<Int>()
        val roots = mutableSetOf<Int>()
        for (moduleIndex in getFileModules(virtualFile)) {
            val module = cachedModules[moduleIndex] ?: continue
            findRootModules(module, visited, roots)
        }
        return roots
    }

    private fun findRootModules(module: ZigBuildConfig.Module, visited: MutableSet<Int>, roots: MutableSet<Int>) {
        if (module.index in visited) return
        visited.add(module.index)
        if (module.isRootModule) roots.add(module.index)
        for (otherModule in cachedModules.values) {
            if (otherModule.index in visited) continue
            if (otherModule.importTable.containsValue(module.index)) {
                findRootModules(otherModule, visited, roots)
            }
        }
    }

    @RequiresReadLock
    private fun processVfsChange(fileActions: Map<Path, ZigModulesAsyncFileListener.Action>) {
        val fileImports = cachedFileImports.toMutableMap()
        for ((path, action) in fileActions) {
            when (action) {
                ZigModulesAsyncFileListener.Action.CREATE,
                ZigModulesAsyncFileListener.Action.CHANGE -> {
                    fileImports.remove(path)
                    followImports(path, fileImports)
                }
                ZigModulesAsyncFileListener.Action.DELETE -> {
                    fileImports.remove(path)
                }
            }
        }
        cachedFileImports = fileImports
        modificationTracker.incModificationCount()
        daemonCodeAnalyzer.restart("ZigModulesProjectService.processVfsChange")
    }

    fun scheduleProcessVfsChange(fileActions: Map<Path, ZigModulesAsyncFileListener.Action>) {
        coroutineScope.launch {
            smartReadAction(project) {
                processVfsChange(fileActions)
            }
        }
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
                for (project in ProjectManager.getInstance().openProjects) {
                    val modulesService = project.service<ZigModulesProjectService>()
                    modulesService.scheduleProcessVfsChange(fileActions)
                }
            }
        }
    }
}

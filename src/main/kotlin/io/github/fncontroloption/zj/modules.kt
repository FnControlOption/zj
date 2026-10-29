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
import kotlinx.coroutines.flow.*
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

    private val cachedIdToModule = MutableStateFlow<Map<ZigBuildConfig.Module.Id, ZigBuildConfig.Module>>(emptyMap())
    private val cachedRootFileToId = MutableStateFlow<Map<Path, ZigBuildConfig.Module.Id>>(emptyMap())
    private val cachedFileImports = MutableStateFlow<Map<Path, Set<Path>>>(emptyMap())
    val cache =
        combine(cachedRootFileToId, cachedFileImports, ::Pair)
            .combine(cachedIdToModule) { (rootFileToId, fileImports), idToModule ->
                Cache(idToModule, rootFileToId, fileImports)
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, Cache(emptyMap(), emptyMap(), emptyMap()))

    init {
        combine(projectPathFlow, deferredFetchFlow) { projectPath, deferredFetch ->
            cachedIdToModule.value = emptyMap()
            cachedRootFileToId.value = emptyMap()
            cachedFileImports.value = emptyMap()
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
            withBackgroundProgress(project, "Loading modules\u2026") {
                smartReadAction(project) {
                    val modules = buildConfig.modules + getBuildFileModules(projectPath)
                    cachedIdToModule.value = modules.associateBy { it.id }
                    findRootModulesAndFileImports(projectPath, modules)
                }
            }
        }.launchIn(coroutineScope)
    }

    @RequiresReadLock
    private fun getBuildFileModules(projectPath: Path): List<ZigBuildConfig.Module> {
        val configurer = ZigAnalyzer.findZigLibFile(project, null, "compiler/configurer.zig")
        val configurerImportTable = mutableMapOf<String, ZigBuildConfig.Module.Id>()
        val configurerModule = configurer?.toNioPathOrNull()?.let {
            ZigBuildConfig.Module(
                id = ZigBuildConfig.Module.Id(-1),
                isRootModule = true,
                rootSourceFile = it.toString(),
                importTable = configurerImportTable,
            )
        }

        val buildRootToDeps = mutableMapOf<Path, Map<String, Path>>()
        followBuildFileGraph(projectPath, projectPath, buildRootToDeps)

        var rawModuleId = -2
        val buildRootToModule = mutableMapOf<Path, ZigBuildConfig.Module>()
        val root = getBuildFileModule(projectPath, buildRootToDeps, buildRootToModule) {
            ZigBuildConfig.Module.Id(rawModuleId--)
        }
        configurerImportTable["@build"] = root.id

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
        generateModuleId: () -> ZigBuildConfig.Module.Id,
    ): ZigBuildConfig.Module {
        var module = buildRootToModule[buildRoot]
        if (module != null) {
            return module
        }
        val importTable = mutableMapOf<String, ZigBuildConfig.Module.Id>()
        module = ZigBuildConfig.Module(
            id = generateModuleId(),
            isRootModule = false,
            rootSourceFile = buildRoot.resolve("build.zig").toString(),
            importTable = importTable,
        )
        buildRootToModule[buildRoot] = module
        for ((depName, depPath) in buildRootToDeps[buildRoot].orEmpty()) {
            val depModule = getBuildFileModule(depPath, buildRootToDeps, buildRootToModule, generateModuleId)
            importTable[depName] = depModule.id
        }
        return module
    }

    val modificationTracker = SimpleModificationTracker()

    @RequiresReadLock
    private fun findRootModulesAndFileImports(projectPath: Path, modules: Collection<ZigBuildConfig.Module>) {
        val rootFileToId = mutableMapOf<Path, ZigBuildConfig.Module.Id>()
        val fileImports = mutableMapOf<Path, Set<Path>>()
        for (module in modules) {
            val rootFile = module.rootSourceFile ?: continue
            val rootPath = projectPath.resolve(rootFile) ?: continue
            rootFileToId[rootPath] = module.id
            followImports(rootPath, fileImports)
        }
        cachedRootFileToId.value = rootFileToId
        cachedFileImports.value = fileImports
        modificationTracker.incModificationCount()
        daemonCodeAnalyzer.restart("ZigModulesProjectService.loadModules")
    }

    @RequiresReadLock
    private fun followImports(path: Path, fileImports: MutableMap<Path, Set<Path>>) {
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

    @RequiresReadLock
    private fun processVfsChange(fileActions: Map<Path, ZigModulesAsyncFileListener.Action>) {
        val fileImports = cachedFileImports.value.toMutableMap()
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
        cachedFileImports.value = fileImports
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

    class Cache(
        val idToModule: Map<ZigBuildConfig.Module.Id, ZigBuildConfig.Module>,
        val rootFileToId: Map<Path, ZigBuildConfig.Module.Id>,
        val fileImports: Map<Path, Set<Path>>,
    ) {
        fun getFileModules(virtualFile: VirtualFile): Set<ZigBuildConfig.Module.Id> {
            val path = virtualFile.toNioPathOrNull() ?: return emptySet()
            val result = mutableSetOf<ZigBuildConfig.Module.Id>()
            findFileModules(path, mutableSetOf(), result)
            return result
        }

        private fun findFileModules(
            path: Path,
            visited: MutableSet<Path>,
            result: MutableSet<ZigBuildConfig.Module.Id>,
        ) {
            if (path in visited) return
            visited.add(path)
            val module = rootFileToId[path]
            if (module != null) {
                result.add(module)
                return
            }
            for ((otherPath, otherImports) in fileImports) {
                if (path in otherImports) {
                    findFileModules(otherPath, visited, result)
                }
            }
        }

        fun getFileRootModules(virtualFile: VirtualFile): Set<ZigBuildConfig.Module.Id> {
            val visited = mutableSetOf<ZigBuildConfig.Module.Id>()
            val roots = mutableSetOf<ZigBuildConfig.Module.Id>()
            for (moduleId in getFileModules(virtualFile)) {
                val module = idToModule[moduleId] ?: continue
                findRootModules(module, visited, roots)
            }
            return roots
        }

        private fun findRootModules(
            module: ZigBuildConfig.Module,
            visited: MutableSet<ZigBuildConfig.Module.Id>,
            roots: MutableSet<ZigBuildConfig.Module.Id>,
        ) {
            if (module.id in visited) return
            visited.add(module.id)
            if (module.isRootModule) roots.add(module.id)
            for (otherModule in idToModule.values) {
                if (otherModule.id in visited) continue
                if (otherModule.importTable.containsValue(module.id)) {
                    findRootModules(otherModule, visited, roots)
                }
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

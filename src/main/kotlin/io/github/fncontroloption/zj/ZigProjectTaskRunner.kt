package io.github.fncontroloption.zj

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.task.*
import com.intellij.util.progress.sleepCancellable
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

class ZigProjectTaskRunner : ProjectTaskRunner() {
    override fun canRun(
        project: Project,
        projectTask: ProjectTask,
        context: ProjectTaskContext?,
    ): Boolean {
        println(projectTask::class.simpleName)
        return projectTask is ModuleBuildTask && ModuleRootManager.getInstance(projectTask.module).sdk?.sdkType is ZigSdkType
    }

    override fun run(
        project: Project,
        context: ProjectTaskContext,
        vararg tasks: ProjectTask,
    ): Promise<Result> {
        println("run")
        val promise = AsyncPromise<Result>()
        object : Task.Backgroundable(project, "Foobar", true) {
            override fun run(indicator: ProgressIndicator) {
                val buildViewManager = project.service<BuildViewManager>()
                val buildId = Any()
                val buildDescriptor = DefaultBuildDescriptor(buildId, "Fizzbuzz", "", System.currentTimeMillis())
                buildDescriptor.isActivateToolWindowWhenAdded = true
                buildViewManager.onEvent(buildId, StartBuildEvent.builder("Start build", buildDescriptor).build())
                sleepCancellable(1000)
                buildViewManager.onEvent(buildId, OutputBuildEvent.builder("Output").build())
                sleepCancellable(1000)
                buildViewManager.onEvent(buildId, MessageEvent.builder("Error", MessageEvent.Kind.ERROR).build())
                sleepCancellable(1000)
                val result = if (false) SuccessResultImpl() else FailureResultImpl()
                buildViewManager.onEvent(buildId, FinishBuildEvent.builder(buildId, "Finish build", result).build())
            }

            override fun onCancel() {
                promise.setResult(TaskRunnerResults.ABORTED)
            }

            override fun onSuccess() {
                promise.setResult(TaskRunnerResults.SUCCESS)
            }

            override fun onThrowable(error: Throwable) {
                super.onThrowable(error)
                promise.setResult(TaskRunnerResults.FAILURE)
            }
        }.queue()
        return promise
    }
}

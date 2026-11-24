package dev.mcbg.rider.launchsettingview

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.impl.EditConfigurationsDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.jetbrains.rider.run.configurations.IProjectBasedRunConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 启动时实例化应用级服务 [RunConfigurationsDialogPatcher]，安装 AWT 钩子。
 *
 * 自测模式（-Dlsplugin.selftest=true -Dlsplugin.testCsproj=<csproj 路径>）：
 * 项目打开 15 秒后自动创建一个 LaunchSettings 配置并打开"运行/调试配置"对话框；
 * 19 秒后自动打开表单编辑对话框，用于无人工介入验证表单交互。
 * 未指定 testCsproj 时跳过自测。
 */
class PatcherStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(PatcherStartupActivity::class.java)

    private val testCsproj: String? = System.getProperty("lsplugin.testCsproj")?.takeIf { it.isNotBlank() }

    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().getService(RunConfigurationsDialogPatcher::class.java)
        if (System.getProperty("lsplugin.selftest") != "true" || testCsproj == null) return
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler.schedule({
            ApplicationManager.getApplication().invokeLater(
                { selfTest(project) },
                ModalityState.any()
            )
        }, 15, TimeUnit.SECONDS)
        scheduler.schedule({
            ApplicationManager.getApplication().invokeLater(
                { openFormDialogSelfTest(project) },
                ModalityState.any()
            )
        }, 19, TimeUnit.SECONDS)
    }

    private fun selfTest(project: Project) {
        val csproj = testCsproj ?: return
        try {
            val type = ConfigurationTypeUtil.findConfigurationType("LaunchSettings")
                ?: error("找不到 LaunchSettings 配置类型")
            val factory = type.configurationFactories.first()
            val runManager = RunManager.getInstance(project)
            val settings = runManager.createConfiguration("LSPluginSelfTest", factory)
            (settings.configuration as? IProjectBasedRunConfiguration)
                ?.setProjectFilePath(csproj)
            runManager.addConfiguration(settings)
            runManager.selectedConfiguration = settings
            log.info("[LSPlugin] 自测: 已创建配置并打开对话框")
            EditConfigurationsDialog(project).show()
        } catch (t: Throwable) {
            log.error("[LSPlugin] 自测失败", t)
        }
    }

    private fun openFormDialogSelfTest(project: Project) {
        val csproj = testCsproj ?: return
        try {
            val path = LaunchSettingsFileIo.launchSettingsPath(csproj)
            val exists = Files.isRegularFile(path)
            val root = if (exists) LaunchSettingsJsonEditor.load(path) else LaunchSettingsJsonEditor.newDocument()
            val profiles = LaunchSettingsJsonEditor.profilesOf(root)
                ?: error("launchSettings.json 缺少 profiles 对象")
            LaunchSettingsFormDialog(project, path, root, profiles, null, exists).show()
            log.info("[LSPlugin] 自测: 已打开表单对话框 (exists=$exists)")
        } catch (t: Throwable) {
            log.error("[LSPlugin] 自测打开表单失败", t)
        }
    }
}

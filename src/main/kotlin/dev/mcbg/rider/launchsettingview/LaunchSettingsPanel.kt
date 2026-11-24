package dev.mcbg.rider.launchsettingview

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.impl.ConfigurationSettingsEditorWrapper
import com.intellij.ide.DataManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBPanel
import com.jetbrains.rider.run.configurations.IProjectBasedRunConfiguration
import com.jetbrains.rider.run.configurations.controls.LaunchProfile
import tools.jackson.databind.JsonNode
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

/**
 * 注入到运行/调试配置编辑器上的包裹面板（透明直通）。
 *
 * 入口策略（阶段二）：
 * 1. 首选：接管 Rider 自带提示行链接（"Profile parameters are set by … open in editor" /
 *    中文"配置文件形参通过 … 在编辑器中打开"），点击打开我们的表单编辑窗口，界面零新增元素；
 * 2. 兜底：找不到提示行时（通常是文件尚不存在），把按钮挂到"启动配置文件"下拉框行的
 *    最右（LabeledComponent BorderLayout 的空闲 EAST 槽位）。
 *
 * 行结构（沙箱组件树 dump 验证）：
 *   RevertableLabeledComponent (MigLayout)
 *     LabeledComponent (BorderLayout: WEST=JBLabel, CENTER=ComboBox, EAST=空闲)
 *       ComboBox  —— items 为 LaunchProfile 类型（全树唯一）
 *
 * 配置解析说明（2026.3 字节码验证）：对话框不提供 RunConfiguration.DATA_KEY，
 * 唯一可靠来源是 CONFIGURATION_EDITOR_KEY（挂在 original 的 UiDataProvider 上，
 * 必须以 original 为上下文根查询）→ wrapper.getSnapshot()（快照语义，不动活配置）。
 */
class LaunchSettingsPanel(val original: JComponent) : JBPanel<LaunchSettingsPanel>(BorderLayout()) {

    private data class Resolved(val project: Project, val typeId: String, val csprojPath: String?)

    private val log = Logger.getInstance(LaunchSettingsPanel::class.java)

    /** 兜底按钮：仅在提示行链接缺失时显示 */
    private val actionButton: JButton = JButton().apply { isVisible = false }

    init {
        isOpaque = false
        add(original, BorderLayout.CENTER)
        addAncestorListener(object : AncestorListener {
            override fun ancestorAdded(event: AncestorEvent) = updateState()
            override fun ancestorRemoved(event: AncestorEvent) {}
            override fun ancestorMoved(event: AncestorEvent) {}
        })
    }

    /**
     * 接管提示行链接 / 刷新兜底按钮状态。
     */
    fun updateState() {
        val hintLink = findHintLink()
        if (hintLink != null) {
            if (hintLink.getClientProperty(HOOK_KEY) == null) {
                replaceWithOurLink(hintLink)
            }
            actionButton.isVisible = false
            return
        }

        // 兜底：Launch profile 行 EAST 按钮（阶段一机制）
        val row = findProfileRow()
        if (row == null) {
            actionButton.isVisible = false
            log.info("[LSPlugin] updateState: 未找到启动配置文件行，按钮不挂载")
            return
        }
        val east = (row.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.EAST)
        if (east == null) {
            val holder = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            holder.isOpaque = false
            holder.add(actionButton)
            row.add(holder, BorderLayout.EAST)
            row.revalidate()
            row.repaint()
        }

        val resolved = resolveCurrent()
        when {
            resolved == null || resolved.typeId != LAUNCH_SETTINGS_TYPE_ID -> {
                actionButton.isVisible = false
                actionButton.toolTipText = null
                log.info("[LSPlugin] updateState: hidden (type=${resolved?.typeId ?: "无"})")
            }
            resolved.csprojPath == null -> {
                actionButton.isVisible = true
                actionButton.isEnabled = false
                actionButton.text = "创建启动配置文件…"
                actionButton.toolTipText = "请先在上方选择项目"
                log.info("[LSPlugin] updateState: disabled (未选项目)")
            }
            else -> {
                val path = LaunchSettingsFileIo.launchSettingsPath(resolved.csprojPath)
                val exists = Files.isRegularFile(path)
                actionButton.isVisible = true
                actionButton.isEnabled = true
                actionButton.text = if (exists) "编辑启动配置文件…" else "创建启动配置文件…"
                actionButton.toolTipText = path.toString()
                log.info("[LSPlugin] updateState: visible (path=$path exists=$exists)")
            }
        }
    }

    /**
     * 替换法接管：不移除原链接的监听器（会破坏 Rider 的内部绑定，导致链接文本丢失），
     * 而是隐藏并移除原链接，插入我们自己的同名链接指向表单编辑窗口。
     * 文本优先取原链接文本，缺省回落到 Rider 资源键（自动适配语言）。
     */
    private fun replaceWithOurLink(original: ActionLink) {
        val parent = original.parent ?: return
        val text = original.text?.takeIf { it.isNotBlank() }
            ?: runCatching { com.jetbrains.rider.run.RiderRunBundle.message("open.in.editor") }.getOrNull()
            ?: "open in editor"
        val replacement = ActionLink(text) { onButtonClick() }
        replacement.putClientProperty(HOOK_KEY, true)
        parent.remove(original)
        parent.add(replacement)
        parent.revalidate()
        parent.repaint()
        log.info("[LSPlugin] 链接已接管（替换法）: text=$text")
    }

    /**
     * 定位提示行链接：ActionLink 与"Profile parameters are set by …" /
     * "配置文件形参通过 …" 标签同容器，或链接自身文本为 open in editor / 在编辑器中打开。
     */
    private fun findHintLink(): ActionLink? {
        var found: ActionLink? = null
        walk(original) { c ->
            if (found == null && c is ActionLink) {
                val hintSibling = c.parent?.components?.any {
                    it is JLabel && (it.text?.startsWith("Profile parameters are set by") == true ||
                        it.text?.startsWith("配置文件形参通过") == true)
                } ?: false
                if (hintSibling || c.text == "open in editor" || c.text == "在编辑器中打开") {
                    found = c
                }
            }
        }
        return found
    }

    /**
     * 定位"启动配置文件"行的 LabeledComponent：
     * 1. 首选：CENTER 为 JComboBox 且条目含 LaunchProfile 类型（唯一可靠）；
     * 2. 回退：标签文本为 "Launch profile:"（英文）或 "启动配置文件"（中文），
     *    覆盖 profiles 异步加载导致下拉框暂空的情况。
     */
    private fun findProfileRow(): LabeledComponent<*>? {
        var byItems: LabeledComponent<*>? = null
        var byLabel: LabeledComponent<*>? = null
        walk(original) { c ->
            if (c is LabeledComponent<*>) {
                val widget = c.component
                if (widget is JComboBox<*> && (0 until widget.itemCount).any { widget.getItemAt(it) is LaunchProfile }) {
                    if (byItems == null) byItems = c
                }
                val labelText = findLabelText(c)
                if (labelText?.startsWith("Launch profile") == true ||
                    labelText?.startsWith("启动配置文件") == true
                ) {
                    if (byLabel == null) byLabel = c
                }
            }
        }
        return byItems ?: byLabel
    }

    private fun selectedProfileName(): String? =
        (findProfileRow()?.component as? JComboBox<*>)?.selectedItem?.let {
            (it as? LaunchProfile)?.name
        }

    private fun findLabelText(container: Container): String? {
        var text: String? = null
        walk(container) { c ->
            if (text == null && c is JLabel && c.text != null && c.text != "null") {
                text = c.text
            }
        }
        return text
    }

    private inline fun walk(root: Component, action: (Component) -> Unit) {
        val queue = ArrayDeque<Component>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            action(c)
            if (c is Container) {
                for (child in c.components) queue.add(child)
            }
        }
    }

    private fun resolveCurrent(): Resolved? {
        val ctx = DataManager.getInstance().getDataContext(original)
        val wrapper = ConfigurationSettingsEditorWrapper.CONFIGURATION_EDITOR_KEY.getData(ctx)
            ?: return null
        val config = runCatching { wrapper.getSnapshot().configuration }.getOrNull()
            ?: return null
        val project = config.project ?: return null
        val typeId = config.type?.id ?: return null
        val csproj = (config as? IProjectBasedRunConfiguration)?.getProjectFilePath()
        return Resolved(project, typeId, csproj?.takeUnless { it.isBlank() })
    }

    private fun onButtonClick() {
        val resolved = resolveCurrent()?.takeIf { it.typeId == LAUNCH_SETTINGS_TYPE_ID } ?: return
        val csproj = resolved.csprojPath ?: return
        val path = LaunchSettingsFileIo.launchSettingsPath(csproj)
        val exists = Files.isRegularFile(path)
        log.info("[LSPlugin] click: mode=${if (exists) "编辑" else "创建"} path=$path")

        val root: JsonNode = if (exists) {
            try {
                LaunchSettingsJsonEditor.load(path)
            } catch (e: Exception) {
                log.warn("[LSPlugin] JSON 解析失败", e)
                Messages.showErrorDialog(
                    resolved.project,
                    "解析 launchSettings.json 失败：${e.message}\n可先用文本编辑器修复格式。",
                    "错误"
                )
                return
            }
        } else {
            LaunchSettingsJsonEditor.newDocument()
        }
        val profiles = LaunchSettingsJsonEditor.profilesOf(root)
        if (profiles == null) {
            Messages.showErrorDialog(resolved.project, "launchSettings.json 缺少 profiles 对象，请先修复文件结构。", "错误")
            return
        }
        LaunchSettingsFormDialog(resolved.project, path, root, profiles, selectedProfileName(), exists).show()
        // 保存创建后翻转按钮文案
        updateState()
    }

    /**
     * 自测模式（-Dlsplugin.selftest=true -Dlsplugin.dump=true）下 dump 编辑器组件树，
     * 用于定位"启动配置文件"下拉框所在行。立即 dump 一次 + 3 秒后再 dump 一次
     * （profiles 列表异步填充）。
     */
    fun dumpComponentTreeForSelfTest() {
        if (System.getProperty("lsplugin.selftest") != "true") return
        if (System.getProperty("lsplugin.dump") != "true") return
        for (delayMs in intArrayOf(0, 3000)) {
            val timer = javax.swing.Timer(delayMs) { walkAndDump(original, 0) }
            timer.isRepeats = false
            timer.start()
        }
    }

    private fun walkAndDump(c: Component, depth: Int) {
        val sb = StringBuilder("[LSPluginDump] ")
        repeat(depth) { sb.append("  ") }
        sb.append(c.javaClass.simpleName)
        if (c.name != null) sb.append(" name='").append(c.name).append("'")
        when (c) {
            is JLabel -> sb.append(" text='").append(c.text).append("'")
            is JButton -> sb.append(" btnText='").append(c.text).append("'")
            is JComboBox<*> -> sb.append(" items=${c.itemCount}")
                .append(" first=[")
                .append((0 until minOf(3, c.itemCount)).joinToString { c.getItemAt(it)?.toString() ?: "null" })
                .append("]")
            is Container -> sb.append(" layout=${c.layout?.javaClass?.simpleName}")
        }
        log.info(sb.toString())
        if (c is Container) {
            for (child in c.components) walkAndDump(child, depth + 1)
        }
    }

    companion object {
        /** LaunchSettingsConfigurationType.RUN_CONFIG_ID（不直接引用该类，避免类路径问题） */
        private const val LAUNCH_SETTINGS_TYPE_ID = "LaunchSettings"
        private const val HOOK_KEY = "dev.mcbg.rider.launchsettingview.linkHooked"
    }
}

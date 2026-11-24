package dev.mcbg.rider.launchsettings

import com.intellij.execution.impl.ConfigurationSettingsEditorWrapper
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ide.DataManager
import com.intellij.openapi.util.Disposer
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Window
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 应用级服务：监视 AWT 事件，在"运行/调试配置"对话框出现时，
 * 把 [LaunchSettingsPanel] 包裹到当前选中配置的编辑面板上。
 *
 * 背景：Rider 的运行配置编辑器不经过平台的 RunConfigurationExtension
 * 扩展机制（RiderRunConfigurationBase.getConfigurationEditor 直接返回编辑器），
 * 没有官方扩展点可加 UI，因此走 Swing 层注入。
 *
 * 对话框接线（2026.3 字节码核实）：
 * RunConfigurable.rightPanel(BorderLayout) --add(comp,"Center")--> 选中配置的
 * SingleConfigurationConfigurable 组件（经 UiDataProvider.wrapComponent 包裹，
 * 对外提供 ConfigurationSettingsEditorWrapper.CONFIGURATION_EDITOR_KEY）。
 * 切换选中项时 rightPanel 不变，只 removeAll() 再 add 新组件。
 */
@Service(Service.Level.APP)
class RunConfigurationsDialogPatcher {

    private val log = Logger.getInstance(RunConfigurationsDialogPatcher::class.java)

    private class TrackedDialog(val window: Window) {
        val scanScheduled = AtomicBoolean(false)
        var timer: Timer? = null
        var retriesLeft = RETRY_COUNT
    }

    private val tracked = LinkedHashMap<Window, TrackedDialog>()

    /** 挂在 rightPanel 上，感知选中配置切换（组件换入） */
    private val containerListener = object : ContainerListener {
        override fun componentAdded(e: ContainerEvent) {
            val window = SwingUtilities.getWindowAncestor(e.container) ?: return
            val state = synchronized(tracked) { tracked[window] } ?: return
            scheduleScan(state)
        }

        override fun componentRemoved(e: ContainerEvent) {}
    }

    init {
        val disposable: Disposable = Disposer.newDisposable("LaunchSettingsDialogPatcher")
        Disposer.register(ApplicationManager.getApplication(), disposable)
        IdeEventQueue.getInstance().addDispatcher(
            IdeEventQueue.EventDispatcher { e ->
                try {
                    handleEvent(e)
                } catch (t: Throwable) {
                    log.error("launchSettings patcher dispatcher failed", t)
                }
                false // 永不消费事件
            },
            disposable
        )
    }

    private fun handleEvent(e: AWTEvent) {
        when (e) {
            is WindowEvent -> when (e.id) {
                WindowEvent.WINDOW_OPENED ->
                    if (e.window is Dialog) {
                        log.info("[LSPlugin] 对话框打开: ${e.window.javaClass.name}")
                        val state = synchronized(tracked) {
                            tracked.getOrPut(e.window) { TrackedDialog(e.window) }
                        }
                        scheduleScan(state)
                    }
                WindowEvent.WINDOW_CLOSED -> synchronized(tracked) {
                    tracked.remove(e.window)?.timer?.stop()
                }
            }
            is ContainerEvent ->
                if (e.id == ContainerEvent.COMPONENT_ADDED) {
                    val window = SwingUtilities.getWindowAncestor(e.child)
                    if (window != null) {
                        val state = synchronized(tracked) { tracked[window] } ?: return
                        scheduleScan(state)
                    }
                }
        }
    }

    private fun scheduleScan(state: TrackedDialog) {
        if (!state.scanScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            state.scanScheduled.set(false)
            scanWindow(state)
        }, ModalityState.any())
    }

    private fun scanWindow(state: TrackedDialog) {
        if (state.window !in tracked) return
        val target = findProviderComponent(state.window)
        if (target == null) {
            // 对话框内容可能懒构建：定时重试
            if (state.retriesLeft > 0) {
                state.retriesLeft--
                val timer = Timer(RETRY_DELAY_MS) { scanWindow(state) }
                timer.isRepeats = false
                state.timer?.stop()
                state.timer = timer
                timer.start()
            } else {
                log.info("[LSPlugin] 扫描放弃: ${state.window.javaClass.name} 中未找到配置编辑面板")
            }
            return
        }
        state.timer?.stop()
        log.info("[LSPlugin] 找到编辑面板: target=${target.javaClass.name} parent=${target.parent?.javaClass?.name}")
        wrapIfNeeded(target)
    }

    /**
     * BFS 找窗口内"最浅"的、数据上下文提供 CONFIGURATION_EDITOR_KEY 且父组件不提供的组件
     * —— 即 rightPanel 的直接子组件（选中配置的编辑面板）。
     */
    private fun findProviderComponent(root: Container): JComponent? {
        val queue = ArrayDeque<Component>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c is JComponent && hasEditorKey(c) && (c.parent == null || !hasEditorKey(c.parent))) {
                return c
            }
            if (c is Container) {
                for (child in c.components) queue.add(child)
            }
        }
        return null
    }

    private fun hasEditorKey(component: Component): Boolean =
        ConfigurationSettingsEditorWrapper.CONFIGURATION_EDITOR_KEY.getData(
            DataManager.getInstance().getDataContext(component)
        ) != null

    private fun wrapIfNeeded(target: JComponent) {
        val parent = target.parent as? JComponent ?: return
        if (parent is LaunchSettingsPanel) return // 已包裹

        // 记录原布局位置：BorderLayout 用区域探测，其余布局退化为索引
        val region: String? = (parent.layout as? BorderLayout)?.let { layout ->
            REGIONS.firstOrNull { layout.getLayoutComponent(it) === target }
        }
        val index = parent.components.indexOf(target)

        val panel = LaunchSettingsPanel(target)
        parent.remove(target)
        when {
            region != null -> parent.add(panel, region)
            index >= 0 -> parent.add(panel, index)
            else -> parent.add(panel)
        }
        parent.revalidate()
        parent.repaint()
        panel.updateState()
        panel.dumpComponentTreeForSelfTest()

        if (parent.getClientProperty(LISTENER_INSTALLED) == null) {
            parent.putClientProperty(LISTENER_INSTALLED, true)
            parent.addContainerListener(containerListener)
        }
        log.info("[LSPlugin] 注入完成: parent=${parent.javaClass.name}")
    }

    companion object {
        private const val RETRY_COUNT = 10
        private const val RETRY_DELAY_MS = 200
        private const val LISTENER_INSTALLED = "dev.mcbg.rider.launchsettings.listenerInstalled"

        private val REGIONS = listOf(
            BorderLayout.CENTER, BorderLayout.NORTH, BorderLayout.SOUTH,
            BorderLayout.WEST, BorderLayout.EAST, BorderLayout.LINE_START, BorderLayout.LINE_END
        )
    }
}

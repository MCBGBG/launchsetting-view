package imizuki.launchsettingview

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.util.ui.JBUI
import com.jetbrains.rider.run.configurations.controls.LaunchProfile
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * launchSettings.json 表单化编辑窗口：左侧 profile 列表（+/− 增删，同运行配置树），
 * 右侧所选 profile 的表单；环境变量为键值表格（外观同"执行前"区域）。
 *
 * 数据模型：内存中 LinkedHashMap<名称, JsonNode>（保持文件原顺序），
 * 列表切换时先把当前表单写回内存，确定时统一序列化保存。
 * 表单只写已知字段，profile 节点上的未知字段原样保留。
 */
class LaunchSettingsFormDialog(
    private val dialogProject: Project,
    private val file: Path,
    private val root: JsonNode,
    private val profiles: ObjectNode,
    preselectedProfile: String?,
    editMode: Boolean,
) : DialogWrapper(dialogProject, /* canBeParent = */ true) {

    private val log = Logger.getInstance(LaunchSettingsFormDialog::class.java)

    /** 内存模型：profile 键 → 节点（LinkedHashMap 保持顺序） */
    private val model = LinkedHashMap<String, JsonNode>()
    private var currentKey: String? = null

    private val listModel = DefaultListModel<String>()
    private val profileList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        border = JBUI.Borders.empty(4, 10, 4, 4)
    }

    private val nameField = com.intellij.ui.components.JBTextField()
    private val commandNameCombo = ComboBox<String>(16).apply {
        isEditable = true
        addItem("Project")
        addItem("Executable")
        addItem("IISExpress")
    }
    private val argsField = ExpandableTextField()
    private val exePathField = TextFieldWithBrowseButton()
    private val workDirField = TextFieldWithBrowseButton()
    private val appUrlField = com.intellij.ui.components.JBTextField()
    private val launchBrowserCheck = JBCheckBox("启动浏览器")
    private val launchUrlField = com.intellij.ui.components.JBTextField()
    private val nativeDebugCheck = JBCheckBox("使用本机调试")

    // 按命令类型动态禁用的字段标签
    private val labelArgs = JBLabel("程序实参：")
    private val labelExe = JBLabel("可执行文件路径：")
    private val labelWorkDir = JBLabel("工作目录：")

    private val envModel = EnvironmentVariablesTableModel()
    private val envTable = EnvironmentVariablesTable(envModel)

    init {
        profiles.properties().forEach { (k, v) -> model[k] = v }
        model.keys.forEach { listModel.addElement(it) }

        title = if (editMode) "编辑启动配置文件" else "新建启动配置文件"
        setOKButtonText("保存")
        setCancelButtonText("取消")

        exePathField.addBrowseFolderListener(dialogProject, FileChooserDescriptorFactory.singleFile())
        workDirField.addBrowseFolderListener(dialogProject, FileChooserDescriptorFactory.singleDir())

        launchBrowserCheck.addActionListener { launchUrlField.isEnabled = launchBrowserCheck.isSelected }

        // 空值占位提示
        nameField.getEmptyText().setText("profile 名称，如 MyProfile")
        argsField.getEmptyText().setText("传递给应用程序的命令行参数")
        exePathField.childComponent.let { it as? com.intellij.ui.components.JBTextField }
            ?.getEmptyText()?.setText("可执行文件的完整路径，如 /usr/local/share/dotnet/dotnet")
        workDirField.childComponent.let { it as? com.intellij.ui.components.JBTextField }
            ?.getEmptyText()?.setText("应用程序启动时的工作目录")
        appUrlField.getEmptyText().setText("以 http:// 或 https:// 开头，多个用 ; 拼接")
        launchUrlField.getEmptyText().setText("路由，将拼接在应用程序 URL 之后，如 swagger")

        // 命令类型变化 → 动态禁用不可用字段
        (commandNameCombo.editor.editorComponent as? javax.swing.text.JTextComponent)?.document
            ?.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateFieldStates()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateFieldStates()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateFieldStates()
            })
        commandNameCombo.addItemListener { updateFieldStates() }

        profileList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val sel = profileList.selectedValue ?: return@addListSelectionListener
            if (sel == currentKey) return@addListSelectionListener
            saveCurrentToModel()
            loadFromModel(sel)
        }

        val initial = preselectedProfile?.takeIf { model.containsKey(it) }
            ?: listModel.firstElement()
        if (initial != null) {
            profileList.setSelectedValue(initial, true)
        } else {
            loadFromModel(null)
        }
        init()
    }

    // ---------- 内存模型 <-> 表单 ----------

    private fun loadFromModel(key: String?) {
        currentKey = key
        val node = key?.let { model[it] as? ObjectNode }
        nameField.text = key ?: ""
        commandNameCombo.selectedItem = node?.path(LaunchSettingsJsonEditor.KEY_COMMAND_NAME)?.asString("Project")?.ifEmpty { "Project" } ?: "Project"
        argsField.text = node?.path(LaunchSettingsJsonEditor.KEY_COMMAND_LINE_ARGS)?.asString("") ?: ""
        exePathField.text = node?.path(LaunchSettingsJsonEditor.KEY_EXECUTABLE_PATH)?.asString("") ?: ""
        workDirField.text = node?.path(LaunchSettingsJsonEditor.KEY_WORKING_DIRECTORY)?.asString("") ?: ""
        appUrlField.text = node?.path(LaunchSettingsJsonEditor.KEY_APPLICATION_URL)?.asString("") ?: ""
        launchBrowserCheck.isSelected = node?.path(LaunchSettingsJsonEditor.KEY_LAUNCH_BROWSER)?.asBoolean(false) ?: false
        launchUrlField.text = node?.path(LaunchSettingsJsonEditor.KEY_LAUNCH_URL)?.asString("") ?: ""
        launchUrlField.isEnabled = launchBrowserCheck.isSelected
        nativeDebugCheck.isSelected = node?.path(LaunchSettingsJsonEditor.KEY_NATIVE_DEBUGGING)?.asBoolean(false) ?: false

        envModel.items = mutableListOf()
        node?.path(LaunchSettingsJsonEditor.KEY_ENVIRONMENT_VARIABLES)
            ?.takeIf { it.isObject }
            ?.properties()
            ?.forEach { (k, v) -> envModel.addRow(EnvironmentVariablesTableModel.Row(k, v.asString(""))) }

        updateFieldStates()
    }

    /**
     * 依据命令类型启用/禁用字段：
     * Project / IISExpress 使用项目自身输出与 IIS 设置，程序实参、
     * 可执行文件路径、工作目录无意义 → 禁用；Executable 及自定义命令全部可用。
     */
    private fun updateFieldStates() {
        val cmd = commandText()
        val noArgsCommand = cmd.equals("Project", ignoreCase = true) ||
            cmd.equals("IISExpress", ignoreCase = true) ||
            cmd.equals("IIS Express", ignoreCase = true)
        val enabled = !noArgsCommand
        listOf(labelArgs, labelExe, labelWorkDir).forEach { it.isEnabled = enabled }
        argsField.isEnabled = enabled
        workDirField.isEnabled = enabled
        setBrowseFieldEnabled(exePathField, enabled)
    }

    private fun setBrowseFieldEnabled(f: TextFieldWithBrowseButton, enabled: Boolean) {
        f.isEnabled = enabled
        f.childComponent.isEnabled = enabled
        f.setButtonEnabled(enabled)
    }

    private fun saveCurrentToModel() {
        val originalKey = currentKey ?: return
        var node = model[originalKey] as? ObjectNode ?: return

        // 处理改名（重名时保持原名）
        val newName = nameField.text.trim()
        val key = if (newName.isNotEmpty() && newName != originalKey && !model.containsKey(newName)) {
            model.remove(originalKey)
            model[newName] = node
            val idx = listModel.indexOf(originalKey)
            if (idx >= 0) listModel.set(idx, newName)
            newName
        } else {
            originalKey
        }
        node = model[key] as? ObjectNode ?: return

        node.put(LaunchSettingsJsonEditor.KEY_COMMAND_NAME, commandText())
        setOrRemoveString(node, LaunchSettingsJsonEditor.KEY_COMMAND_LINE_ARGS, argsField.text)
        setOrRemoveString(node, LaunchSettingsJsonEditor.KEY_EXECUTABLE_PATH, exePathField.text)
        setOrRemoveString(node, LaunchSettingsJsonEditor.KEY_WORKING_DIRECTORY, workDirField.text)
        setOrRemoveString(node, LaunchSettingsJsonEditor.KEY_APPLICATION_URL, appUrlField.text)

        if (launchBrowserCheck.isSelected) {
            node.put(LaunchSettingsJsonEditor.KEY_LAUNCH_BROWSER, true)
        } else if (node.has(LaunchSettingsJsonEditor.KEY_LAUNCH_BROWSER)) {
            node.put(LaunchSettingsJsonEditor.KEY_LAUNCH_BROWSER, false)
        }
        setOrRemoveString(node, LaunchSettingsJsonEditor.KEY_LAUNCH_URL, launchUrlField.text.takeIf { launchBrowserCheck.isSelected })

        if (nativeDebugCheck.isSelected) {
            node.put(LaunchSettingsJsonEditor.KEY_NATIVE_DEBUGGING, true)
        } else if (node.has(LaunchSettingsJsonEditor.KEY_NATIVE_DEBUGGING)) {
            node.put(LaunchSettingsJsonEditor.KEY_NATIVE_DEBUGGING, false)
        }

        saveEnvTo(node)
    }

    private fun commandText(): String =
        (commandNameCombo.editor.item as? String)?.trim()
            ?: (commandNameCombo.selectedItem as? String)?.trim()
            ?: "Project"

    private fun setOrRemoveString(node: ObjectNode, key: String, value: String?) {
        if (value.isNullOrBlank()) node.remove(key) else node.put(key, value)
    }

    private fun saveEnvTo(node: ObjectNode) {
        val rows = envModel.items.mapNotNull { row ->
            val k = row.name.trim()
            if (k.isEmpty()) null else k to row.value
        }
        if (rows.isEmpty()) {
            node.remove(LaunchSettingsJsonEditor.KEY_ENVIRONMENT_VARIABLES)
        } else {
            val obj = JsonNodeFactory.instance.objectNode()
            rows.forEach { (k, v) -> obj.put(k, v) }
            node.set(LaunchSettingsJsonEditor.KEY_ENVIRONMENT_VARIABLES, obj)
        }
    }

    // ---------- 列表增删 ----------

    private fun addProfile() {
        saveCurrentToModel()
        var i = 1
        while (model.containsKey("Profile$i")) i++
        val name = "Profile$i"
        val node = JsonNodeFactory.instance.objectNode()
        node.put(LaunchSettingsJsonEditor.KEY_COMMAND_NAME, "Project")
        model[name] = node
        listModel.addElement(name)
        profileList.setSelectedValue(name, true)
    }

    private fun removeProfile() {
        val key = profileList.selectedValue ?: return
        val idx = listModel.indexOf(key)
        model.remove(key)
        listModel.removeElement(key)
        currentKey = null
        if (listModel.isEmpty) {
            loadFromModel(null)
        } else {
            profileList.setSelectedIndex(minOf(idx, listModel.size - 1).coerceAtLeast(0))
        }
    }

    // ---------- UI 构建 ----------

    override fun createNorthPanel(): JComponent =
        JBLabel(file.toString()).apply { border = JBUI.Borders.empty(2, 20, 8, 20) }

    override fun createCenterPanel(): JComponent {
        val decoratedList = ToolbarDecorator.createDecorator(profileList)
            .disableUpDownActions()
            .setAddAction { addProfile() }
            .setRemoveAction { removeProfile() }
            .createPanel()

        val leftPanel = JBPanel<JBPanel<*>>(BorderLayout())
        leftPanel.add(decoratedList, BorderLayout.CENTER)
        val escape = ActionLink("以 JSON 文本编辑…") { openTextEditor() }
        val escapeWrap = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply { isOpaque = false; add(escape) }
        leftPanel.add(escapeWrap, BorderLayout.SOUTH)

        val rightPanel = buildFormPanel()

        val splitter = Splitter(/* vertical = */ false, 0.2f)
        splitter.firstComponent = JBScrollPane(leftPanel).apply { preferredSize = Dimension(190, 480) }
        splitter.secondComponent = JBScrollPane(rightPanel)
        splitter.preferredSize = Dimension(920, 560)

        val wrap = JBPanel<JBPanel<*>>(BorderLayout())
        wrap.border = JBUI.Borders.empty(16, 20, 16, 20)
        wrap.add(splitter, BorderLayout.CENTER)
        return wrap
    }

    private fun buildFormPanel(): JComponent {
        val form = JPanel(GridBagLayout())
        val g = GridBagConstraints()
        g.insets = Insets(6, 8, 6, 8)
        g.anchor = GridBagConstraints.WEST

        // IDE 表单对齐：所有字段行 fill=HORIZONTAL + weightx=0，
        // 列宽由最宽字段（FIELD_WIDTH）决定，其余字段自动拉伸对齐，
        // 不依赖各组件自身 preferredSize 的计算行为。
        fun row(label: JComponent, comp: JComponent, stretch: Boolean = true) {
            g.gridy++
            g.gridx = 0
            g.gridwidth = 1
            g.weightx = 0.0
            g.fill = GridBagConstraints.NONE
            form.add(label, g)
            g.gridx = 1
            if (stretch) {
                comp.preferredSize = Dimension(JBUI.scale(FIELD_WIDTH), comp.preferredSize.height)
                g.fill = GridBagConstraints.HORIZONTAL
            } else {
                g.fill = GridBagConstraints.NONE
            }
            g.weightx = 0.0
            form.add(comp, g)
        }

        row(JBLabel("名称："), nameField)
        row(JBLabel("命令："), commandNameCombo)
        row(labelArgs, argsField)
        row(labelExe, exePathField)
        row(labelWorkDir, workDirField)
        row(JBLabel("应用程序 URL："), appUrlField)
        row(JBLabel("启动 URL："), launchUrlField)
        row(JBLabel(""), launchBrowserCheck, stretch = false)
        row(JBLabel(""), nativeDebugCheck, stretch = false)

        envTable.rowHeight = JBUI.scale(28)
        envTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(200)
        val envDecorated = ToolbarDecorator.createDecorator(envTable)
            .disableUpDownActions()
            .setAddAction { envModel.addRow(EnvironmentVariablesTableModel.Row("", "")) }
            .setRemoveAction {
                val r = envTable.selectedRow
                if (r >= 0) envModel.removeRow(r)
            }
            .createPanel()
        envDecorated.preferredSize = Dimension(480, 180)

        val envSection = JBPanel<JBPanel<*>>(BorderLayout())
        envSection.border = JBUI.Borders.emptyTop(8)
        val envTitle = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply { isOpaque = false; add(JBLabel("环境变量：")) }
        envSection.add(envTitle, BorderLayout.NORTH)
        envSection.add(envDecorated, BorderLayout.CENTER)

        val outer = JBPanel<JBPanel<*>>(BorderLayout())
        outer.add(form, BorderLayout.NORTH)
        outer.add(envSection, BorderLayout.CENTER)
        return outer
    }

    // ---------- 保存 ----------

    private fun writeBackProfiles() {
        profiles.removeAll()
        model.forEach { (k, v) -> profiles.set(k, v) }
    }

    private fun openTextEditor() {
        saveCurrentToModel()
        writeBackProfiles()
        val text = LaunchSettingsJsonEditor.serialize(root)
        doCancelAction()
        LaunchSettingsTextDialog(dialogProject, file, text, Files.exists(file)).show()
    }

    override fun doOKAction() {
        saveCurrentToModel()
        writeBackProfiles()
        try {
            LaunchSettingsFileIo.save(file, LaunchSettingsJsonEditor.serialize(root))
        } catch (e: IOException) {
            log.warn("[LSPlugin] 表单保存失败", e)
            Messages.showErrorDialog(dialogProject, "保存启动配置文件失败：${e.message}", "错误")
            return
        }
        log.info("[LSPlugin] 表单已保存: file=$file profiles=${model.keys}")
        super.doOKAction()
    }

    companion object {
        /** 字段列统一首选宽度（px），列宽由最宽字段决定 */
        private const val FIELD_WIDTH = 460
    }
}

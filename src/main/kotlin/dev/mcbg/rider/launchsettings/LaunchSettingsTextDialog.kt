package dev.mcbg.rider.launchsettings

import com.intellij.json.JsonFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import java.io.IOException
import java.nio.file.Path
import javax.swing.JComponent

/**
 * launchSettings.json 文本编辑窗口。编辑与创建共用，仅标题与初始内容不同。
 */
class LaunchSettingsTextDialog(
    project: Project,
    private val file: Path,
    initialText: String,
    editMode: Boolean,
) : DialogWrapper(project, /* canBeParent = */ true) {

    private val dialogProject: Project = project

    private val editor: EditorTextField = EditorTextField(initialText, project, JsonFileType.INSTANCE).apply {
        setOneLineMode(false)
    }

    init {
        title = if (editMode) "编辑启动配置文件" else "新建启动配置文件"
        setOKButtonText("保存")
        setCancelButtonText("取消")
        init()
    }

    override fun createNorthPanel(): JComponent =
        JBLabel(file.toString())

    override fun createCenterPanel(): JComponent =
        JBScrollPane(editor).apply {
            preferredSize = Dimension(760, 520)
        }

    private fun currentText(): String = editor.document.text

    override fun doOKAction() {
        try {
            LaunchSettingsFileIo.save(file, currentText())
        } catch (e: IOException) {
            Messages.showErrorDialog(dialogProject, "保存启动配置文件失败：${e.message}", "错误")
            return
        }
        super.doOKAction()
    }
}

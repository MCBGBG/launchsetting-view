package imizuki.launchsettingview

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.DefaultCellEditor
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * 环境变量表格模型：名称列带"常用变量"下拉，值列按名称给出固定选项下拉，
 * 两列均可手动输入（可编辑 ComboBox 编辑器）。
 *
 * 编辑器由 [EnvironmentVariablesTable.getCellEditor] 按行列动态提供：
 * 值列的选项取决于同一行名称列当前的值。
 */
class EnvironmentVariablesTableModel : ListTableModel<EnvironmentVariablesTableModel.Row>(
    NameColumn(), ValueColumn()
) {
    class Row(var name: String, var value: String)

    /** 常用环境变量：名称 → 固定选项（null 表示无固定选项，仅提示） */
    class CommonVar(val name: String, val options: List<String>?, val placeholder: String?)

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

    private class NameColumn : ColumnInfo<Row, String>("名称") {
        override fun valueOf(item: Row): String = item.name

        override fun setValue(item: Row, value: String?) {
            item.name = value?.trim() ?: ""
            // 若该常用变量有固定选项且当前值不在其中，自动填入首个选项
            val common = optionsFor(item.name)
            if (common != null && item.value !in common) item.value = common.first()
        }

        override fun isCellEditable(item: Row): Boolean = true
    }

    private class ValueColumn : ColumnInfo<Row, String>("值") {
        override fun valueOf(item: Row): String = item.value

        override fun setValue(item: Row, value: String?) {
            item.value = value ?: ""
        }

        override fun isCellEditable(item: Row): Boolean = true
    }

    companion object {
        /** 常用环境变量速查（ASP.NET Core / .NET 场景） */
        val COMMON_VARS: List<CommonVar> = listOf(
            CommonVar("ASPNETCORE_ENVIRONMENT", listOf("Development", "Staging", "Production"), null),
            CommonVar("DOTNET_ENVIRONMENT", listOf("Development", "Staging", "Production"), null),
            CommonVar("ASPNETCORE_URLS", null, "以 http:// 或 https:// 开头，多个用 ; 拼接"),
            CommonVar("DOTNET_ROOT", null, "如 /usr/local/share/dotnet"),
            CommonVar("DOTNET_MODIFIABLE_ASSEMBLIES", listOf("debug"), null),
            CommonVar("COREHOST_TRACE", listOf("0", "1"), null),
            CommonVar("ASPNETCORE_HOSTINGSTARTUPASSEMBLIES", null, "程序集名，多个用 ; 拼接"),
            CommonVar("Logging__LogLevel__Default", listOf("Trace", "Debug", "Information", "Warning", "Error", "Critical", "None"), null),
            CommonVar("ConnectionStrings__Default", null, "数据库连接字符串"),
        )

        /** 全部固定选项的去重集合（值列名称无匹配时给通用建议） */
        val ALL_VALUE_OPTIONS: List<String> = COMMON_VARS.flatMap { it.options ?: emptyList() }.distinct()

        /** 按名称找固定选项（忽略大小写） */
        fun optionsFor(name: String): List<String>? =
            COMMON_VARS.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.options

        fun placeholderFor(name: String): String? =
            COMMON_VARS.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.placeholder
    }
}

/**
 * 环境变量表格：名称列 / 值列均为"可编辑下拉框"编辑器
 * （下拉给建议，文本可自由输入）。值列选项随同行名称联动。
 */
class EnvironmentVariablesTable(model: EnvironmentVariablesTableModel) : JBTable(model) {

    private val nameCombo: ComboBox<String> = ComboBox<String>(16).apply {
        isEditable = true
        putClientProperty(ComboBox.TABLE_CELL_EDITOR_PROPERTY, true)
        putClientProperty("JComboBox.isTableCellEditor", true)
    }
    private val valueCombo: ComboBox<String> = ComboBox<String>(16).apply {
        isEditable = true
        putClientProperty(ComboBox.TABLE_CELL_EDITOR_PROPERTY, true)
        putClientProperty("JComboBox.isTableCellEditor", true)
    }
    private val nameEditor = DefaultCellEditor(nameCombo)
    private val valueEditor = DefaultCellEditor(valueCombo)

    init {
        autoCreateRowSorter = false
    }

    override fun getCellEditor(row: Int, column: Int): TableCellEditor {
        val item = model.let { (it as? EnvironmentVariablesTableModel)?.getRowValue(row) }
        return when (column) {
            0 -> {
                nameCombo.removeAllItems()
                EnvironmentVariablesTableModel.COMMON_VARS.forEach { nameCombo.addItem(it.name) }
                nameEditor
            }
            else -> {
                valueCombo.removeAllItems()
                val opts = item?.let { EnvironmentVariablesTableModel.optionsFor(it.name) }
                    ?: EnvironmentVariablesTableModel.ALL_VALUE_OPTIONS
                opts.forEach { valueCombo.addItem(it) }
                valueEditor
            }
        }
    }

    override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): java.awt.Component =
        super.prepareRenderer(renderer, row, column).apply {
            val m = model as? EnvironmentVariablesTableModel ?: return@apply
            val item = m.getRowValue(convertRowIndexToModel(row))
            // 空值单元格显示该变量的占位提示
            if (column == 1 && item != null && item.value.isEmpty()) {
                val ph = EnvironmentVariablesTableModel.placeholderFor(item.name)
                if (ph != null) foreground = com.intellij.ui.JBColor.GRAY
                toolTipText = ph
            }
        }
}

package io.github.tgeng.firviewer

import com.google.common.primitives.Primitives
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.ui.table.JBTable
import org.jetbrains.kotlin.fir.utils.ArrayMap
import org.jetbrains.kotlin.fir.utils.AttributeArrayOwner
import org.jetbrains.kotlin.fir.utils.TypeRegistry
import org.jetbrains.kotlin.idea.frontend.api.*
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtSymbol
import org.jetbrains.kotlin.idea.frontend.api.types.KtType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible


class TableObjectViewer(
        project: Project,
        state: ObjectViewerUiState,
        index: Int,
        obj: Any,
        elementToAnalyze: KtElement?
) :
        ObjectViewer(project, state, index, elementToAnalyze) {
    private val _model = ObjectTableModel(obj, state, elementToAnalyze)
    private val table = FittingTable(_model).apply {
        setDefaultRenderer(Any::class.java, TableObjectRenderer)
        rowSelectionAllowed = true
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val row = selectedRow
            val name = _model.rows[row].name
            select(name.text)
        }
    }

    override val view: JComponent = TableWrapper()

    override fun selectAndGetObject(name: String): Any? {
        val index = _model.rows.indexOfFirst { it.name.text == name }
        if (index == -1) return null
        table.setRowSelectionInterval(index, index)
        return _model.rows[index].value?.also {
            highlightInEditor(it, project)
        }
    }

    private inner class TableWrapper :
            JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(table.tableHeader)
            add(table)
        }

        override fun getPreferredSize(): Dimension =
                Dimension(1, table.preferredSize.height + table.tableHeader.preferredSize.height)
    }
}

private class FittingTable(model: TableModel) : JBTable(model) {

    init {
        setMaxItemsForSizeCalculation(20)
        setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN)
        getTableHeader().foreground = getTableHeader().background
        getTableHeader().background = Color.GRAY
    }

    private val measured: Array<BooleanArray> = Array(model.columnCount) { BooleanArray(model.rowCount) }

    override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
        val component = super.prepareRenderer(renderer, row, column)
        if (measured[column][row]) return component
        measured[column][row] = true
        val rendererWidth = component.preferredSize.width
        val tableColumn = getColumnModel().getColumn(column)
        tableColumn.preferredWidth =
                Math.max(rendererWidth + intercellSpacing.width, tableColumn.preferredWidth)
        if (column == 2) {
            // set row height according to the last column
            val rendererHeight = component.preferredSize.height
            if (rendererHeight > 1) {
                setRowHeight(row, rendererHeight)
            }
        }
        return component
    }
}

private class ObjectTableModel(
        private val obj: Any,
        val state: ObjectViewerUiState,
        private val elementToAnalyze: KtElement?
) :
        AbstractTableModel() {
    data class RowData(val name: JLabel, val type: String?, val value: Any?)

    val rows: List<RowData> = when (obj) {
        is Iterable<*> -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value?.getTypeAndId(), value)
        }
        is Map<*, *> -> obj.map { (k, v) -> RowData(label(k?.getValueAndId() ?: ""), v?.getTypeAndId(), v) }
        is AttributeArrayOwner<*, *> -> getAttributesBasedRows()
        is PsiElement, is KtType, is KtSymbol -> getKtAnalysisSessionBasedRows() + getObjectPropertyMembersBasedRows()
        else -> getObjectPropertyMembersBasedRows()
    }.sortedBy { it.name.text }

    private fun getAttributesBasedRows(): List<RowData> {
        val arrayMap =
                obj::class.memberProperties.first { it.name == "arrayMap" }.apply { isAccessible = true }
                        .call(obj) as ArrayMap<*>
        val typeRegistry =
                obj::class.memberProperties.first { it.name == "typeRegistry" }
                        .apply { isAccessible = true }
                        .call(obj) as TypeRegistry<*, *>
        val idPerType =
                TypeRegistry::class.members.first { it.name == "idPerType" }.apply { isAccessible = true }
                        .call(typeRegistry) as ConcurrentHashMap<KClass<*>, Int>
        return idPerType.mapNotNull { (key, id) ->
            val value = arrayMap[id] ?: return@mapNotNull null
            RowData(label(key.simpleName ?: key.toString()), value.getTypeAndId(), value)
        }
    }

    private fun getObjectPropertyMembersBasedRows() = try {
        val result = mutableListOf<RowData>()
        obj.traverseObjectProperty { name, value ->
            if (value == null ||
                    value is Collection<*> && value.isEmpty() ||
                    value is Map<*, *> && value.isEmpty()
            ) {
                return@traverseObjectProperty
            }
            result += RowData(label(name), value?.getTypeAndId(), value)
        }
        result
    } catch (e: Throwable) {
        listOf(RowData(label(""), e?.getTypeAndId(), e))
    }

    @OptIn(HackToForceAllowRunningAnalyzeOnEDT::class)
    private fun getKtAnalysisSessionBasedRows(): List<RowData> {
        if (elementToAnalyze == null) return emptyList()
        return hackyAllowRunningOnEdt {
            analyzeWithReadAction(elementToAnalyze) {
                KtAnalysisSession::class.members.filter { it.visibility == KVisibility.PUBLIC && it.parameters.size == 2 && it.name != "equals" }
                        .mapNotNull { prop ->
                            try {
                                val value = prop.call(this, obj)
                                RowData(label(prop.name, icon = AllIcons.Nodes.Favorite), value?.getTypeAndId(), value)
                            } catch (e: Throwable) {
                                null
                            }
                        }
            }
        }
    }

    private fun Any.getTypeAndId(): String {
        return when {
            isData() -> this::class.simpleName
                    ?: this::class.toString()
            else -> this::class.simpleName + " @" + Integer.toHexString(System.identityHashCode(this))
        }
    }

    private fun Any.getValueAndId(): String {
        return when {
            isData() -> this::class.simpleName
                    ?: this.toString()
            else -> this::class.simpleName + " @" + Integer.toHexString(System.identityHashCode(this))
        }
    }

    private fun Any.isData() =
            this is Iterable<*> || this is Map<*, *> || this is AttributeArrayOwner<*, *> ||
                    this is Enum<*> || this::class.objectInstance != null ||
                    this::class.java.isPrimitive || Primitives.isWrapperType(this::class.java) ||
                    this::class.java == String::class.java || this::class.java == Name::class.java ||
                    this::class.isData

    override fun getColumnName(column: Int): String = when (column) {
        0 -> when (obj) {
            is Iterable<*> -> "index"
            is Map<*, *> -> "key"
            else -> "property"
        }
        1 -> "type"
        2 -> "value"
        else -> throw IllegalStateException()
    }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = 3

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        return when (columnIndex) {
            0 -> rows[rowIndex].name
            1 -> rows[rowIndex].type
            2 -> rows[rowIndex].value
            else -> throw IllegalStateException()
        }
    }
}


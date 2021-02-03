package io.github.tgeng.firviewer

import com.google.common.primitives.Primitives
import com.intellij.ui.table.JBTable
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.render
import org.jetbrains.kotlin.fir.utils.ArrayMap
import org.jetbrains.kotlin.fir.utils.AttributeArrayOwner
import org.jetbrains.kotlin.fir.utils.TypeRegistry
import org.jetbrains.kotlin.name.Name
import java.awt.Component
import java.awt.Dimension
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class TableObjectViewer(state: ObjectViewerUiState, index: Int, obj: Any) :
  ObjectViewer(state, index) {
  private val _model = ObjectTableModel(obj)
  private val table = FittingTable(_model).apply {
    rowSelectionAllowed = true
    setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    selectionModel.addListSelectionListener { e ->
      if (e.valueIsAdjusting) return@addListSelectionListener
      val row = selectedRow
      val name = _model.members[row].name
      select(name)
    }
  }

  override val view: JComponent = TableWrapper()

  override fun selectAndGetObject(name: String): Any? {
    val index = _model.members.indexOfFirst { it.name == name }
    if (index == -1) return null
    table.setRowSelectionInterval(index, index)
    return _model.members[index].value
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

private open class FittingTable(model: TableModel) : JBTable(model) {
  init {
    setDefaultRenderer(Any::class.java, ObjectRenderer)
    setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN)
  }

  override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
    val component = super.prepareRenderer(renderer, row, column)
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

private class ObjectTableModel(private val obj: Any) : AbstractTableModel() {
  data class RowData(val name: String, val type: String?, val value: Any?)

  val members: List<RowData> = when (obj) {
    is Iterable<*> -> obj.mapIndexed { index, value ->
      RowData(index.toString(), value?.getTypeAndId(), value)
    }
    is Map<*, *> -> obj.map { (k, v) -> RowData(k?.getValueAndId() ?: "", v?.getTypeAndId(), v) }
    is AttributeArrayOwner<*, *> -> {
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
      idPerType.mapNotNull { (key, id) ->
        val value = arrayMap[id] ?: return@mapNotNull null
        RowData(key.simpleName ?: key.toString(), value.getTypeAndId(), value)
      }
    }
    else -> try {
      obj::class.memberProperties
        .filter { it.visibility == KVisibility.PUBLIC && it.parameters.size == 1 }
        .map {
          val value = try {
            it.call(obj)
          } catch (e: Throwable) {
            e
          }
          RowData(it.name, value?.getTypeAndId(), value)
        }
    } catch (e: Throwable) {
      listOf(RowData("", e?.getTypeAndId(), e))
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

  override fun getRowCount(): Int = members.size

  override fun getColumnCount(): Int = 3

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
    return when (columnIndex) {
      0 -> members[rowIndex].name
      1 -> members[rowIndex].type
      2 -> members[rowIndex].value
      else -> throw IllegalStateException()
    }
  }
}

object ObjectRenderer : TableCellRenderer {
  override fun getTableCellRendererComponent(
    table: JTable,
    value: Any?,
    isSelected: Boolean,
    hasFocus: Boolean,
    row: Int,
    column: Int
  ): Component = when (value) {
    is Collection<*> -> label("size = " + value.size)
    is Map<*, *> -> label("size =" + value.size)
    is CFGNode<*> -> label(value.render())
    is FirElement -> label(value.render(), multiline = true)
    is AttributeArrayOwner<*, *> -> {
      val arrayMap =
        value::class.memberProperties.first { it.name == "arrayMap" }.apply { isAccessible = true }
          .call(value) as ArrayMap<*>
      label("${arrayMap.size} attributes")
    }
    else -> label(value.toString())
  }.apply {
    if (table.isRowSelected(row)) {
      isOpaque = true
      background = table.selectionBackground
    }
  }
}

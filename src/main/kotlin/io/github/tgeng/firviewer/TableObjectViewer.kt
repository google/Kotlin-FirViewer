// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.github.tgeng.firviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.ui.table.JBTable
import org.jetbrains.kotlin.fir.declarations.FirControlFlowGraphOwner
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.dataFlowInfo
import org.jetbrains.kotlin.fir.utils.AbstractArrayMapOwner
import org.jetbrains.kotlin.fir.utils.ArrayMap
import org.jetbrains.kotlin.fir.utils.TypeRegistry
import org.jetbrains.kotlin.idea.caches.project.IdeaModuleInfo
import org.jetbrains.kotlin.idea.caches.project.getModuleInfos
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirModuleResolveState
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getOrBuildFir
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getResolveState
import org.jetbrains.kotlin.idea.frontend.api.*
import org.jetbrains.kotlin.idea.frontend.api.components.KtDiagnosticCheckerFilter
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtSymbol
import org.jetbrains.kotlin.idea.frontend.api.tokens.AlwaysAccessibleValidityTokenFactory
import org.jetbrains.kotlin.idea.frontend.api.tokens.HackToForceAllowRunningAnalyzeOnEDT
import org.jetbrains.kotlin.idea.frontend.api.tokens.hackyAllowRunningOnEdt
import org.jetbrains.kotlin.idea.frontend.api.types.KtType
import org.jetbrains.kotlin.idea.references.KtFirReference
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible


class TableObjectViewer(
        project: Project,
        state: ObjectViewerUiState,
        index: Int,
        obj: Any,
        ktFile: KtFile,
        elementToAnalyze: KtElement?
) :
        ObjectViewer(project, state, index, ktFile, elementToAnalyze) {
    private val _model = ObjectTableModel(obj, elementToAnalyze)
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
        val mouseListener = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val point = e.point
                updateToolTip(point)
            }

            override fun mousePressed(e: MouseEvent) {
                val row = rowAtPoint(e.point)
                if (row == -1) return
                if (row != selectedRow) {
                    updateToolTip(e.point)
                    return // Only do the triggering if the row is already selected.
                }
                val valueProvider = _model.rows[row].valueProvider ?: return
                val newValue = valueProvider.invoke()
                _model.rows[row].type = newValue?.getTypeAndId()
                _model.rows[row].value = newValue
                repaint()
            }

            private fun updateToolTip(point: Point) {
                val row = rowAtPoint(point)
                // Only do the triggering if the row is already selected.
                if (row != -1 && row == selectedRow && _model.rows[row].valueProvider != null) {
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    toolTipText = "Click left button to trigger re-computation of this data."
                } else {
                    cursor = Cursor.getDefaultCursor()
                    toolTipText = ""
                }
            }

        }

        addMouseMotionListener(mouseListener)
        addMouseListener(mouseListener)
    }

    override val view: JComponent = when (obj) {
        else -> TableWrapper()
    }

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
        val obj: Any,
        private val elementToAnalyze: KtElement?
) :
        AbstractTableModel() {
    class RowData(val name: JLabel, var type: String?, var value: Any?, val valueProvider: (() -> Any?)? = null)

    @OptIn(HackToForceAllowRunningAnalyzeOnEDT::class)
    val rows: List<RowData> = when (obj) {
        is Array<*> -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value?.getTypeAndId(), value)
        }
        is BooleanArray -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value.getTypeAndId(), value)
        }
        is ByteArray -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value.getTypeAndId(), value)
        }
        is ShortArray -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value.getTypeAndId(), value)
        }
        is IntArray -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value.getTypeAndId(), value)
        }
        is LongArray -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value.getTypeAndId(), value)
        }
        is FloatArray -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value.getTypeAndId(), value)
        }
        is DoubleArray -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value.getTypeAndId(), value)
        }
        is CharArray -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value.getTypeAndId(), value)
        }
        is AbstractArrayMapOwner<*, *> -> getArrayMapOwnerBasedRows().sortedBy { it.name.text }
        is Iterable<*> -> obj.mapIndexed { index, value ->
            RowData(label(index.toString()), value?.getTypeAndId(), value)
        }
        is Sequence<*> -> hackyAllowRunningOnEdt {
            obj.mapIndexed { index, value ->
                RowData(label(index.toString()), value?.getTypeAndId(), value)
            }.toList()
        }
        is Map<*, *> -> obj.map { (k, v) ->
            RowData(label(k?.getForMapKey() ?: ""), v?.getTypeAndId(), v)
        }.sortedBy { it.name.text }
        is PsiElement, is KtType, is KtSymbol, is KtFirReference -> (getKtAnalysisSessionBasedRows() + getObjectPropertyMembersBasedRows() + getOtherExtensionProperties()).sortedBy { it.name.text }
        is CFGNode<*> -> (getObjectPropertyMembersBasedRows() + getCfgNodeProperties(obj)).sortedBy { it.name.text }
        else -> getObjectPropertyMembersBasedRows().sortedBy { it.name.text }
    }

    private fun getArrayMapOwnerBasedRows(): List<RowData> {
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

    @OptIn(HackToForceAllowRunningAnalyzeOnEDT::class)
    private fun getObjectPropertyMembersBasedRows() = try {
        val result = mutableListOf<RowData>()
        obj.traverseObjectProperty { name, value, valueProvider ->
            if (value == null ||
                    value is Collection<*> && value.isEmpty() ||
                    value is Map<*, *> && value.isEmpty()
            ) {
                return@traverseObjectProperty
            }
            result += RowData(label(name), value.getTypeAndId(), value, valueProvider)
        }
        result
    } catch (e: Throwable) {
        listOf(RowData(label("<error>"), e.getTypeAndId(), e))
    }

    @OptIn(HackToForceAllowRunningAnalyzeOnEDT::class)
    private fun getKtAnalysisSessionBasedRows(): List<RowData> {
        if (elementToAnalyze == null) return emptyList()
        return hackyAllowRunningOnEdt {
            runReadAction {
                analyseWithCustomToken(elementToAnalyze, AlwaysAccessibleValidityTokenFactory) {
                    KtAnalysisSession::class.members.filter { it.visibility == KVisibility.PUBLIC && it.parameters.size == 2 && it.name != "equals" }
                            .mapNotNull { prop ->
                                try {
                                    val value = prop.call(this, obj)
                                    RowData(label(prop.name, icon = AllIcons.Nodes.Favorite), value?.getTypeAndId(), value) {
                                        hackyAllowRunningOnEdt {
                                            prop.call(this, obj)
                                        }
                                    }
                                } catch (e: Throwable) {
                                    null
                                }
                            }
                }
            }
        }
    }

    @OptIn(HackToForceAllowRunningAnalyzeOnEDT::class)
    private fun getCfgNodeProperties(node: CFGNode<*>): List<RowData> {
        var cfg = node.owner
        while (true) {
            cfg = cfg.owner ?: break
        }
        val dataFlowInfos = (cfg.declaration as? FirControlFlowGraphOwner)?.controlFlowGraphReference?.dataFlowInfo
                ?: return emptyList()
        val flow = dataFlowInfos.flowOnNodes[node] ?: return emptyList()
        return listOf(RowData(label("flow", icon = AllIcons.Nodes.Favorite), flow.getTypeAndId(), flow))
    }

    @OptIn(HackToForceAllowRunningAnalyzeOnEDT::class)
    private fun getOtherExtensionProperties(): List<RowData> {
        val result = mutableListOf<RowData>()
        when (obj) {
            is KtElement -> {
                fun getScopeContextForPosition() = try {
                    hackyAllowRunningOnEdt {
                        runReadAction {
                            analyseWithCustomToken(obj.containingKtFile, AlwaysAccessibleValidityTokenFactory) {
                                obj.containingKtFile.getScopeContextForPosition(obj)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    e
                }

                var value = getScopeContextForPosition()
                result += RowData(label("scopeAtPosition"), value.getTypeAndId(), value, ::getScopeContextForPosition)

                fun collectDiagnosticsForFile() = try {
                    hackyAllowRunningOnEdt {
                        runReadAction {
                            analyseWithCustomToken(obj.containingKtFile, AlwaysAccessibleValidityTokenFactory) {
                                obj.containingKtFile.collectDiagnosticsForFile(KtDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    e
                }
                value = collectDiagnosticsForFile()
                result += RowData(label("collectDiagnosticsForFile"), value.getTypeAndId(), value, ::collectDiagnosticsForFile)

                fun getDiagnostics() = try {
                    hackyAllowRunningOnEdt {
                        runReadAction {
                            analyseWithCustomToken(obj, AlwaysAccessibleValidityTokenFactory) {
                                obj.getDiagnostics(KtDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    e
                }
                value = getDiagnostics()
                result += RowData(label("getDiagnostics"), value.getTypeAndId(), value, ::getDiagnostics)

                fun getOrBuildFir() = try {
                    hackyAllowRunningOnEdt {
                        runReadAction {
                            analyseWithCustomToken(obj, AlwaysAccessibleValidityTokenFactory) {
                                val property = this::class.memberProperties.first { it.name == "firResolveState" } as KProperty1<KtAnalysisSession, FirModuleResolveState>
                                obj.getOrBuildFir(property.get(this))
                            }
                        }
                    }
                } catch (e: Throwable) {
                    e
                }
                value = getOrBuildFir()
                result += RowData(label("getOrBuildFir"), value.getTypeAndId(), value, ::getOrBuildFir)

                result += obj::getModuleInfos.toRowData()
                result += obj::getResolveState.toRowData()
            }
            is IdeaModuleInfo -> {
                obj::getResolveState.toRowData()
            }
        }
        return result.onEach { it.name.icon = AllIcons.Nodes.Favorite }
    }

    fun KCallable<*>.toRowData(): RowData {
        fun getValue() = try {
            this.call()
        } catch (e: Throwable) {
            e
        }

        val value = getValue()
        return RowData(label(name), value?.getTypeAndId(), value, ::getValue)
    }

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


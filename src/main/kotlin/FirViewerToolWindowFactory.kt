package io.github.tgeng.firviewer

import com.google.common.cache.CacheBuilder
import com.google.common.primitives.Primitives
import com.intellij.AppTopics
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.JBTable
import com.intellij.ui.tree.BaseTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil.collectExpandedPaths
import org.graphstream.graph.implementations.SingleGraph
import org.graphstream.ui.swing_viewer.SwingViewer
import org.graphstream.ui.view.Viewer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirLabel
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.fir.contracts.FirContractDescription
import org.jetbrains.kotlin.fir.contracts.FirEffectDeclaration
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirStubStatement
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraph
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.Edge
import org.jetbrains.kotlin.fir.types.FirTypeProjection
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.utils.ArrayMap
import org.jetbrains.kotlin.fir.utils.AttributeArrayOwner
import org.jetbrains.kotlin.fir.utils.TypeRegistry
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getFirFile
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getResolveState
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class FirViewerToolWindowFactory : ToolWindowFactory, DumbAware {

  private val cache = CacheBuilder.newBuilder().weakKeys().build<PsiFile, TreeUiState>()

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    System.setProperty("org.graphstream.ui", "swing")
    toolWindow.title = "FirViewer"
    toolWindow.setIcon(AllIcons.Toolwindows.ToolWindowHierarchy)
    toolWindow.setTitleActions(listOf(object : AnAction(), DumbAware {
      override fun update(e: AnActionEvent) {
        e.presentation.icon = AllIcons.Actions.Refresh
      }

      override fun actionPerformed(e: AnActionEvent) {
        refresh(project, toolWindow)
      }
    }))

    fun refresh() = ApplicationManager.getApplication().invokeLater {
      refresh(project, toolWindow)
    }
    project.messageBus.connect().apply {
      subscribe(AppTopics.FILE_DOCUMENT_SYNC, object : FileDocumentManagerListener {
        override fun fileContentLoaded(file: VirtualFile, document: Document) {
          refresh()
          document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
              refresh()
            }

            override fun bulkUpdateFinished(document: Document) {
              refresh()
            }
          })
        }
      })
      subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refresh()
        override fun fileClosed(source: FileEditorManager, file: VirtualFile) = refresh()
        override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
      })
    }
  }

  fun refresh(project: Project, toolWindow: ToolWindow) {
    val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
    val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return
    val treeUiState = cache.get(ktFile) {
      val treeModel = FirTreeModel(ktFile)
      val tree = Tree(treeModel)
      tree.cellRenderer = FirRenderer()
      val jbSplitter = JBSplitter(true).apply {
        firstComponent = JBScrollPane(tree).apply {
          horizontalScrollBar = null
        }
      }
      val tablePane = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
      }
      jbSplitter.secondComponent = JBScrollPane(tablePane)
      val state = TreeUiState(
        jbSplitter,
        tree,
        treeModel,
        tablePane
      )

      tree.addTreeSelectionListener { e ->
        if (e.newLeadSelectionPath != null) state.selectedTreePath =
          e.newLeadSelectionPath.getNamePath()
        ApplicationManager.getApplication().runWriteAction {
          val editorManager = FileEditorManager.getInstance(project) ?: return@runWriteAction
          val editor: Editor = editorManager.selectedTextEditor ?: return@runWriteAction
          editor.markupModel.removeAllHighlighters()
          val node = tree.lastSelectedPathComponent as? FirTreeNode ?: return@runWriteAction
          tablePane.removeAll()
          state.selectedTablePath.clear()
          tablePane.add(ObjectViewer.createObjectViewer(node.firElement, state, 0).view)
          tablePane.repaint()
          val source = node.firElement.source ?: return@runWriteAction
          val textAttributes =
            TextAttributes(null, null, Color.GRAY, EffectType.LINE_UNDERSCORE, Font.PLAIN)
          editor.markupModel
            .addRangeHighlighter(
              source.startOffset,
              source.endOffset,
              HighlighterLayer.CARET_ROW,
              textAttributes,
              HighlighterTargetArea.EXACT_RANGE
            )
        }
      }
      tree.addMouseListener(object : MouseListener {
        override fun mouseClicked(e: MouseEvent) {}

        override fun mousePressed(e: MouseEvent?) {}

        override fun mouseReleased(e: MouseEvent?) {
          state.expandedTreePaths.clear()
          state.expandedTreePaths += collectExpandedPaths(tree).map { path -> path.getNamePath() }
        }

        override fun mouseEntered(e: MouseEvent?) {}

        override fun mouseExited(e: MouseEvent?) {}

      })
      state
    }
    refreshTree(treeUiState)

    if (toolWindow.contentManager.contents.firstOrNull() != treeUiState.pane) {
      toolWindow.contentManager.removeAllContents(true)
      toolWindow.contentManager.addContent(
        toolWindow.contentManager.factory.createContent(
          treeUiState.pane,
          "Current File",
          true
        )
      )
    }
  }

  private fun refreshTree(treeUiState: TreeUiState) {
    val selectedTablePath = treeUiState.selectedTablePath.toList()
    val tree = treeUiState.tree
    treeUiState.model.refresh()
    treeUiState.expandedTreePaths.forEach { tree.expandPath(it.adaptPath(treeUiState.model)) }
    tree.selectionPath = treeUiState.selectedTreePath?.adaptPath(treeUiState.model)
    for (name in selectedTablePath) {
      val objectViewer = treeUiState.tablePane.components.last() as? ObjectViewer ?: break
      if (!objectViewer.select(name)) break
    }
    treeUiState.tablePane.repaint()
  }

  private fun TreePath.getNamePath(): List<String> {
    val result = mutableListOf<String>()
    for (i in 0 until pathCount) {
      result += (getPathComponent(i) as FirTreeNode).name
    }
    return result
  }

  private fun List<String>.adaptPath(model: FirTreeModel): TreePath {
    var current = model.root
    val adaptedPathComponents = mutableListOf(current)
    // Skip first path, which is root.
    for (name in subList(1, size)) {
      current = current.currentChildren.firstOrNull { it.name == name } ?: break
      adaptedPathComponents += current
    }
    val treePath = TreePath(adaptedPathComponents.toTypedArray())
    return treePath
  }

  class FirRenderer : TreeCellRenderer {
    override fun getTreeCellRendererComponent(
      tree: JTree,
      value: Any,
      selected: Boolean,
      expanded: Boolean,
      leaf: Boolean,
      row: Int,
      hasFocus: Boolean
    ): Component {
      val node = value as? FirTreeNode ?: return label("nothing to show")
      return when (val e = node.firElement) {
        is FirAnonymousInitializer -> type(node) + render(node)
        is FirArgumentList -> type(node) + render(node)
        is FirAssignmentOperatorStatement -> type(node) + render(node)
        is FirAugmentedArraySetCall -> type(node) + render(node)
        is FirCatch -> type(node) + render(node)
        is FirConstructor -> type(node) + label("<init>").apply { icon = AllIcons.Nodes.Function }
        is FirContractDescription -> type(node) + render(node)
        is FirDeclarationStatusImpl -> type(node) + render(node)
        is FirDelegatedConstructorCall -> type(node) + render(node)
        is FirEffectDeclaration -> type(node) + render(node)
        is FirErrorFunction -> type(node) + render(node)
        is FirExpression -> type(node) + render(node)
        is FirFile -> type(node) + label(e.name)
        is FirImport -> type(node) + render(node)
        is FirLabel -> type(node) + render(node)
        is FirLoop -> type(node) + render(node)
        is FirPropertyAccessor -> type(node) + render(node)
        is FirReference -> type(node) + render(node)
        is FirRegularClass -> type(node) + label(e.name.asString()).apply {
          icon = AllIcons.Nodes.Class
        }
        is FirSimpleFunction -> type(node) + label(e.name.asString()).apply {
          icon = AllIcons.Nodes.Function
        }
        is FirStubStatement -> type(node) + render(node)
        is FirTypeAlias -> type(node) + render(node)
        is FirTypeParameter -> type(node) + render(node)
        is FirTypeProjection -> type(node) + render(node)
        is FirTypeRef -> type(node) + render(node)
        is FirProperty -> type(node) + label(e.name.asString()).apply {
          icon = AllIcons.Nodes.Property
        }
        is FirVariable<*> -> type(node) + label(e.name.asString()).apply {
          icon = AllIcons.Nodes.Variable
        }
        is FirVariableAssignment -> type(node) + render(node)
        is FirWhenBranch -> type(node) + render(node)
        // is FirConstructedClassTypeParameterRef,
        // is FirOuterClassTypeParameterRef,
        is FirTypeParameterRef -> type(node) + render(node)
        else -> throw IllegalArgumentException("unknown type of FIR element $node")
      }
    }

  }
}

class FirTreeModel(private val ktFile: KtFile) : BaseTreeModel<FirTreeNode>() {
  private var root: FirTreeNode? = null

  init {
    refresh()
  }

  override fun getRoot(): FirTreeNode {
    return root!!
  }

  override fun getChildren(parent: Any?): List<FirTreeNode> {
    val parent = parent as? FirTreeNode ?: return emptyList()
    return parent.currentChildren
  }

  fun refresh() {
    val firFile = ktFile.getFirFile(ktFile.getResolveState())
    if (firFile != root?.firElement) {
      val newFileNode = FirTreeNode("", firFile)
      root = newFileNode
    }
    root!!.refresh(listOf(root!!))
    treeStructureChanged(null, null, null)
  }

  private fun FirTreeNode.refresh(path: List<FirTreeNode>) {
    val newChildren = mutableListOf<FirTreeNode>()
    val childFirElements = firElement::class.memberProperties.flatMap { member ->
      try {
        if (member.parameters.size != 1 || member.visibility != KVisibility.PUBLIC) return@flatMap emptyList()
        when (val f = member.call(firElement)) {
          is FirPureAbstractElement -> listOf(f to member.name)
          is Collection<*> -> f.mapNotNull { it as? FirPureAbstractElement }
            .mapIndexed { index, value -> value to member.name + "[$index]" }
          else -> emptyList()
        }
      } catch (e: Throwable) {
        emptyList()
      }
    }.toMap()
    firElement.acceptChildren(object : FirVisitorVoid() {
      override fun visitElement(element: FirElement) {
        newChildren.add(
          FirTreeNode(
            childFirElements[element]
              ?: throw IllegalStateException("element $element does not correspond to any members!"),
            element as? FirPureAbstractElement
              ?: throw java.lang.IllegalArgumentException("unknown type of FIR element $element")
          )
        )
      }
    })
    currentChildren = newChildren
    currentChildren.forEach { it.refresh(path + it) }
  }
}

data class FirTreeNode(val name: String = "", val firElement: FirPureAbstractElement) {
  var currentChildren = mutableListOf<FirTreeNode>()
}

sealed class ObjectViewer(val state: TreeUiState, val index: Int) {
  fun select(name: String): Boolean {
    val nextObject = selectAndGetObject(name) ?: return false
    val nextViewer = createObjectViewer(nextObject, state, index + 1)

    val tablePane = state.tablePane
    // Remove all tables below this one
    while (tablePane.components.size > index + 1) {
      tablePane.remove(tablePane.components.size - 1)
    }
    while (state.selectedTablePath.size > index) {
      state.selectedTablePath.removeLast()
    }
    tablePane.add(nextViewer.view)
    state.selectedTablePath.add(name)
    tablePane.repaint()
    return true
  }

  abstract val view: JComponent

  protected abstract fun selectAndGetObject(name: String): Any?

  companion object {
    fun createObjectViewer(obj: Any, state: TreeUiState, index: Int): ObjectViewer = when (obj) {
//      is ControlFlowGraph -> CfgGraphViewer(state, index, obj)
      else -> TableObjectViewer(state, index, obj)
    }
  }
}

private class CfgGraphViewer(state: TreeUiState, index: Int, graph: ControlFlowGraph) :
  ObjectViewer(state, index) {

  private val nodeNameMap = mutableMapOf<CFGNode<*>, String>()
  private val nodeClassCounter = mutableMapOf<String, AtomicInteger>()
  val CFGNode<*>.name:String get() = nodeNameMap.computeIfAbsent(this) { node ->
    val nodeClassName = (node::class.simpleName?:node::class.toString()).removeSuffix("Node")
    nodeClassName + nodeClassCounter.computeIfAbsent(nodeClassName) { AtomicInteger() }.getAndIncrement()
  }

  private val graph = SingleGraph("foo").apply {
    graph.nodes.forEach { node ->
      addNode(node.name)
    }
    val edgeCounter = AtomicInteger()
    val edgeNameMap = mutableMapOf<String, EdgeData>()
    graph.nodes.forEach { node ->
      node.followingNodes.forEach { to ->
        val edgeId = edgeCounter.getAndIncrement().toString()
        addEdge(edgeId, node.name, to.name)
      }
//      node.incomingEdges.forEach { from ->
//        val edgeId = edgeCounter.getAndIncrement().toString()
//        addEdge(edgeId, from.name, node.name)
//      }
    }
  }

  data class EdgeData(val from:CFGNode<*>, val to: CFGNode<*>, val edge: Edge?)

  val viewer = SwingViewer(this.graph, Viewer.ThreadingModel.GRAPH_IN_ANOTHER_THREAD).apply {
    enableAutoLayout()
  }
  override val view: JComponent = viewer.addDefaultView(false) as JComponent

  override fun selectAndGetObject(name: String): Any? {
    return null
  }
}

private class TableObjectViewer(state: TreeUiState, index: Int, obj: Any) :
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

  override val view = TableWrapper()

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

private fun label(
  s: String,
  bold: Boolean = false,
  italic: Boolean = false,
  multiline: Boolean = false
) = JBLabel(
  if (multiline) ("<html>" + s.replace("\n", "<br/>").replace(" ", "&nbsp;") + "</html>") else s
).apply {
  font =
    font.deriveFont((if (bold) Font.BOLD else Font.PLAIN) + if (italic) Font.ITALIC else Font.PLAIN)
}

private fun render(e: FirTreeNode) = JBLabel(e.firElement.render())
private fun type(e: FirTreeNode): JComponent {
  val nameAndType = label(
    if (e.name == "") {
      ""
    } else {
      e.name + ": "
    } + e.firElement::class.simpleName,
    bold = true
  )
  val address = label("@" + Integer.toHexString(System.identityHashCode(e.firElement)))
  val nameTypeAndAddress = nameAndType + address
  return if (e.firElement is FirDeclaration) {
    nameTypeAndAddress + label(e.firElement.resolvePhase.toString(), italic = true)
  } else {
    nameTypeAndAddress
  }
}

private val twoPoint = JBUIScale.scale(2)
private operator fun JComponent.plus(that: JComponent): JPanel {
  return if (this is JPanel) {
    add(that)
    this
  } else {
    JPanel(FlowLayout(FlowLayout.LEFT).apply {
      vgap = twoPoint
    }).apply {
      add(this@plus)
      add(that)
      isOpaque = false
    }
  }
}

class TreeUiState(
  val pane: JComponent,
  val tree: Tree,
  val model: FirTreeModel,
  val tablePane: JPanel
) {
  val expandedTreePaths = mutableSetOf<List<String>>()
  var selectedTreePath: List<String>? = null
  val selectedTablePath: MutableList<String> = mutableListOf()
}

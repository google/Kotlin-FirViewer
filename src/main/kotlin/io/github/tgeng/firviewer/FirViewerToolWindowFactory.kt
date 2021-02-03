package io.github.tgeng.firviewer

import com.google.common.cache.CacheBuilder
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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tree.BaseTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil.collectExpandedPaths
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
import org.jetbrains.kotlin.fir.types.FirTypeProjection
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getFirFile
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getResolveState
import org.jetbrains.kotlin.psi.KtFile
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.*
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties

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
        ObjectViewerUiState(tablePane)
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
          state.objectViewerState.selectedTablePath.clear()
          tablePane.add(ObjectViewer.createObjectViewer(node.firElement, state.objectViewerState, 0).view)
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
    val selectedTablePath = treeUiState.objectViewerState.selectedTablePath.toList()
    val tree = treeUiState.tree
    treeUiState.model.refresh()
    treeUiState.expandedTreePaths.forEach { tree.expandPath(it.adaptPath(treeUiState.model)) }
    tree.selectionPath = treeUiState.selectedTreePath?.adaptPath(treeUiState.model)
    for (name in selectedTablePath) {
      val objectViewer =
        treeUiState.objectViewerState.tablePane.components.last() as? ObjectViewer ?: break
      if (!objectViewer.select(name)) break
    }
    treeUiState.objectViewerState.tablePane.repaint()
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


//private class CfgGraphViewer(state: TreeUiState, index: Int, graph: ControlFlowGraph) :
//  ObjectViewer(state, index) {
//
//  private val nodeNameMap = mutableMapOf<CFGNode<*>, String>()
//  private val nodeClassCounter = mutableMapOf<String, AtomicInteger>()
//  val CFGNode<*>.name:String get() = nodeNameMap.computeIfAbsent(this) { node ->
//    val nodeClassName = (node::class.simpleName?:node::class.toString()).removeSuffix("Node")
//    nodeClassName + nodeClassCounter.computeIfAbsent(nodeClassName) { AtomicInteger() }.getAndIncrement()
//  }
//
//  private val graph = SingleGraph("foo").apply {
//    graph.nodes.forEach { node ->
//      addNode(node.name)
//    }
//    val edgeCounter = AtomicInteger()
//    val edgeNameMap = mutableMapOf<String, EdgeData>()
//    graph.nodes.forEach { node ->
//      node.followingNodes.forEach { to ->
//        val edgeId = edgeCounter.getAndIncrement().toString()
//        addEdge(edgeId, node.name, to.name)
//      }
//    }
//  }
//
//  data class EdgeData(val from:CFGNode<*>, val to: CFGNode<*>, val edge: Edge?)
//
//  val viewer = SwingViewer(this.graph, Viewer.ThreadingModel.GRAPH_IN_ANOTHER_THREAD).apply {
//    enableAutoLayout()
//  }
//  override val view: JComponent = viewer.addDefaultView(false) as JComponent
//
//  override fun selectAndGetObject(name: String): Any? {
//    return null
//  }
//}

class TreeUiState(
  val pane: JComponent,
  val tree: Tree,
  val model: FirTreeModel,
  val objectViewerState: ObjectViewerUiState
) {
  val expandedTreePaths = mutableSetOf<List<String>>()
  var selectedTreePath: List<String>? = null
}


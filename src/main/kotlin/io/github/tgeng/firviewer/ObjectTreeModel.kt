package io.github.tgeng.firviewer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tree.BaseTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.psi.KtFile
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.tree.TreePath
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType

class ObjectTreeModel<T : Any>(
        private val ktFile: KtFile,
        private val tClass: KClass<T>,
        val ktFileToT: (KtFile) -> T,
        val acceptChildren: T.((T) -> Unit) -> Unit
) : BaseTreeModel<TreeNode<T>>() {
    private var root: TreeNode<T>? = null

    init {
        refresh()
    }

    override fun getRoot(): TreeNode<T> {
        return root!!
    }

    override fun getChildren(parent: Any?): List<TreeNode<T>> {
        val parent = parent as? TreeNode<*> ?: return emptyList()
        return parent.currentChildren as List<TreeNode<T>>
    }

    fun refresh() {
        val firFile = ktFileToT(ktFile)
        if (firFile != root?.t) {
            val newFileNode = TreeNode("", firFile)
            root = newFileNode
        }
        root!!.refresh(listOf(root!!))
        treeStructureChanged(null, null, null)
    }

    private fun TreeNode<T>.refresh(path: List<TreeNode<T>>) {
        val newChildren = mutableListOf<TreeNode<T>>()
        val childFirElements = t::class.java.methods
                .filter { it.name !in skipMethodNames && it.parameterCount == 0 && it.modifiers and Modifier.PUBLIC != 0 && it.returnType.simpleName != "void" }
                .flatMap { method ->
                    try {
                        val f = method.invoke(t)
                        when {
                            tClass.isInstance(f) -> listOf(f to method.name)
                            f is Collection<*> -> f.mapNotNull { it as? FirPureAbstractElement }
                                    .mapIndexed { index, value -> value to method.name + "[$index]" }
                            else -> emptyList()
                        }
                    } catch (e: Throwable) {
                        emptyList()
                    }
                }.toMap()
        val fieldCounter = AtomicInteger()
        t.acceptChildren { element ->
            newChildren += TreeNode(
                    childFirElements[element] ?: "<prop${fieldCounter.getAndIncrement()}>",
                    element
            )
        }
        currentChildren = newChildren
        currentChildren.forEach { it.refresh(path + it) }
    }

    fun setupTreeUi(project: Project): TreeUiState {
        val tree = Tree(this)
        tree.cellRenderer = TreeObjectRenderer()
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
                this,
                ObjectViewerUiState(tablePane)
        )

        tree.addTreeSelectionListener { e ->
            if (e.newLeadSelectionPath != null) state.selectedTreePath =
                    e.newLeadSelectionPath.getNamePath()
            ApplicationManager.getApplication().runWriteAction {
                val node = tree.lastSelectedPathComponent as? TreeNode<*> ?: return@runWriteAction
                tablePane.removeAll()
                state.objectViewerState.selectedTablePath.clear()
                tablePane.add(
                        ObjectViewer.createObjectViewer(
                                project,
                                node.t,
                                state.objectViewerState,
                                0,
                                null
                        ).view
                )
                tablePane.repaint()
                highlightInEditor(node.t, project)
            }
        }
        tree.addMouseListener(object : MouseListener {
            override fun mouseClicked(e: MouseEvent) {}

            override fun mousePressed(e: MouseEvent?) {}

            override fun mouseReleased(e: MouseEvent?) {
                state.expandedTreePaths.clear()
                state.expandedTreePaths += TreeUtil.collectExpandedPaths(tree).map { path -> path.getNamePath() }
            }

            override fun mouseEntered(e: MouseEvent?) {}

            override fun mouseExited(e: MouseEvent?) {}

        })
        return state
    }

    private fun TreePath.getNamePath(): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until pathCount) {
            result += (getPathComponent(i) as TreeNode<*>).name
        }
        return result
    }
}

data class TreeNode<T : Any>(val name: String = "", val t: T) {
    var currentChildren = mutableListOf<TreeNode<T>>()

}

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

import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tree.BaseTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.tree.TreePath
import kotlin.reflect.KClass

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
        val childNameAndValues = mutableListOf<Pair<Any?, String>>()
        t.traverseObjectProperty { name, value, _ ->
            when {
                tClass.isInstance(value) -> childNameAndValues += (value to name)
                value is Collection<*> -> value.filter { tClass.isInstance(it) }
                        .forEachIndexed { index, value -> childNameAndValues += value to "$name[$index]" }
                else -> {
                }
            }
        }
        val childMap = childNameAndValues.toMap()
        val fieldCounter = AtomicInteger()
        t.acceptChildren { element ->
            newChildren += TreeNode(
                    childMap[element] ?: "<prop${fieldCounter.getAndIncrement()}>",
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
            val node = tree.lastSelectedPathComponent as? TreeNode<*> ?: return@addTreeSelectionListener
            tablePane.removeAll()
            state.objectViewerState.objectViewers.clear()
            val objectViewer = ObjectViewer.createObjectViewer(
                    project,
                    node.t,
                    state.objectViewerState,
                    0,
                    ktFile,
                    node.t as? KtElement
            )
            state.objectViewerState.objectViewers.add(objectViewer)
            tablePane.add(objectViewer.view)
            state.objectViewerState.selectedTablePath.firstOrNull()?.let { objectViewer.select(it) }
            highlightInEditor(node.t, project)
            tablePane.revalidate()
            tablePane.repaint()
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

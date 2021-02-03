package io.github.tgeng.firviewer

import com.intellij.ui.treeStructure.Tree
import javax.swing.JComponent
import javax.swing.tree.TreePath

class TreeUiState(
        val pane: JComponent,
        val tree: Tree,
        val model: ObjectTreeModel<*>,
        val objectViewerState: ObjectViewerUiState
) {
    val expandedTreePaths = mutableSetOf<List<String>>()
    var selectedTreePath: List<String>? = null

    fun refreshTree() {
        val selectedTablePath = objectViewerState.selectedTablePath.toList()
        val tree = tree
        model.refresh()
        expandedTreePaths.forEach { tree.expandPath(it.adaptPath(model)) }
        tree.selectionPath = selectedTreePath?.adaptPath(model)
        for (name in selectedTablePath) {
            val objectViewer = objectViewerState.objectViewers.last()
            if (!objectViewer.select(name)) break
        }
        objectViewerState.tablePane.revalidate()
        objectViewerState.tablePane.repaint()
    }

    private fun List<String>.adaptPath(model: ObjectTreeModel<*>): TreePath {
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
}
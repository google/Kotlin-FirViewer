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
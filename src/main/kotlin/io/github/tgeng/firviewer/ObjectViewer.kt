package io.github.tgeng.firviewer

import javax.swing.JComponent
import javax.swing.JPanel

class ObjectViewerUiState(
  val tablePane: JPanel
) {
  val selectedTablePath: MutableList<String> = mutableListOf()
}

abstract class ObjectViewer(val state: ObjectViewerUiState, val index: Int) {
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
    fun createObjectViewer(obj: Any, state: ObjectViewerUiState, index: Int): ObjectViewer =
      when (obj) {
//      is ControlFlowGraph -> CfgGraphViewer(state, index, obj)
        else -> TableObjectViewer(state, index, obj)
      }
  }
}

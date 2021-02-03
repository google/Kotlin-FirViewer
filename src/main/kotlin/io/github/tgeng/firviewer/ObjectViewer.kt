package io.github.tgeng.firviewer

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.psi.KtElement
import javax.swing.JComponent
import javax.swing.JPanel

class ObjectViewerUiState(
    val tablePane: JPanel,
) {
    val selectedTablePath: MutableList<String> = mutableListOf()
}

abstract class ObjectViewer(
    val project: Project,
    val state: ObjectViewerUiState,
    val index: Int,
    val elementToAnalyze: KtElement?
) {
    fun select(name: String): Boolean {
        val nextObject = selectAndGetObject(name) ?: return false
        val nextViewer =
            createObjectViewer(project, nextObject, state, index + 1, nextObject as? KtElement? ?: elementToAnalyze)

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
        tablePane.revalidate()
        tablePane.repaint()
        return true
    }

    abstract val view: JComponent

    protected abstract fun selectAndGetObject(name: String): Any?

    companion object {
        fun createObjectViewer(
            project: Project,
            obj: Any,
            state: ObjectViewerUiState,
            index: Int,
            elementToAnalyze: KtElement?
        ): ObjectViewer =
            when (obj) {
//      is ControlFlowGraph -> CfgGraphViewer(state, index, obj)
                else -> TableObjectViewer(project, state, index, obj, elementToAnalyze)
            }
    }
}

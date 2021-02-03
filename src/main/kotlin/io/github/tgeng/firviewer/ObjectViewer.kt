package io.github.tgeng.firviewer

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.psi.KtElement
import javax.swing.JComponent
import javax.swing.JPanel

class ObjectViewerUiState(
        val tablePane: JPanel,
) {
    val objectViewers: MutableList<ObjectViewer> = mutableListOf()
    val selectedTablePath: MutableList<String> = mutableListOf()
}

abstract class ObjectViewer(
        val project: Project,
        private val state: ObjectViewerUiState,
        private val index: Int,
        private val elementToAnalyze: KtElement?
) {
    fun select(name: String): Boolean {
        val nextObject = selectAndGetObject(name) ?: return false
        val nextViewer =
                createObjectViewer(project, nextObject, state, index + 1, nextObject as? KtElement? ?: elementToAnalyze)

        // Remove all tables below this one
        while (state.tablePane.components.size > index + 1) {
            state.tablePane.remove(state.tablePane.components.size - 1)
            state.objectViewers.removeLast()
        }
        while (state.selectedTablePath.size > index) {
            state.selectedTablePath.removeLast()
        }
        state.tablePane.add(nextViewer.view)
        state.objectViewers.add(nextViewer)
        state.selectedTablePath.add(name)
        state.tablePane.revalidate()
        state.tablePane.repaint()
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

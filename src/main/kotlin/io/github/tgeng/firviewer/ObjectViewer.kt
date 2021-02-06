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
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
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
    private val ktFile: KtFile,
    private val elementToAnalyze: KtElement?
) {
    fun select(name: String): Boolean {
        val nextObject = selectAndGetObject(name) ?: return false
        val nextViewer =
            createObjectViewer(
                project,
                nextObject,
                state,
                index + 1,
                ktFile,
                nextObject as? KtElement? ?: elementToAnalyze
            )

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
            ktFile: KtFile,
            elementToAnalyze: KtElement?
        ): ObjectViewer =
            when (obj) {
                else -> TableObjectViewer(project, state, index, obj, ktFile, elementToAnalyze)
            }
    }
}

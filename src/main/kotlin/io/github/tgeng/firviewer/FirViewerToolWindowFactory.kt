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

import com.google.common.cache.CacheBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getFirFile
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getResolveState
import org.jetbrains.kotlin.psi.KtFile
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

class FirViewerToolWindowFactory : ToolWindowFactory, DumbAware {

    private val cache = CacheBuilder.newBuilder().weakKeys().build<PsiFile, TreeUiState>()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.title = "FirViewer"
        toolWindow.setIcon(AllIcons.Toolwindows.ToolWindowHierarchy)
        refresh(project, toolWindow)

        toolWindow.setTitleActions(listOf(object : AnAction(), DumbAware {
            override fun update(e: AnActionEvent) {
                e.presentation.icon = AllIcons.Actions.Refresh
            }

            override fun actionPerformed(e: AnActionEvent) {
                refresh(project, toolWindow)
            }
        }))

        refresh(project, toolWindow)
        project.messageBus.connect()
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    refresh(project, toolWindow)
                }
            })

        project.messageBus.connect().subscribe(EVENT_TOPIC, Runnable { refresh(project, toolWindow) })
    }

    private fun refresh(project: Project, toolWindow: ToolWindow) {
        if (!toolWindow.isVisible) return
        val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return
        val treeUiState = cache.get(ktFile) {
            val treeModel = ObjectTreeModel(
                ktFile,
                FirPureAbstractElement::class,
                { it.getFirFile(it.getResolveState()) }) { consumer ->
                acceptChildren(object : FirVisitorVoid() {
                    override fun visitElement(element: FirElement) {
                        if (element is FirPureAbstractElement) consumer(element)
                    }
                })
            }

            treeModel.setupTreeUi(project).apply {
                pane.addFocusListener(object : FocusAdapter() {
                    override fun focusGained(e: FocusEvent?) {
                        refresh(project, toolWindow)
                    }
                })
            }
        }
        treeUiState.refreshTree()

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
}


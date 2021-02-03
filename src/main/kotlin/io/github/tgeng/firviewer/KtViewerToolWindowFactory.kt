package io.github.tgeng.firviewer

import com.google.common.cache.CacheBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtVisitorVoid

class KtViewerToolWindowFactory : ToolWindowFactory {

    private val cache = CacheBuilder.newBuilder().weakKeys().build<PsiFile, TreeUiState>()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.title = "KtViewer"
        toolWindow.setIcon(AllIcons.Toolwindows.ToolWindowStructure)
        refresh(project, toolWindow)

        toolWindow.setTitleActions(listOf(object : AnAction(), DumbAware {
            override fun update(e: AnActionEvent) {
                e.presentation.icon = AllIcons.Actions.Refresh
            }

            override fun actionPerformed(e: AnActionEvent) {
                refresh(project, toolWindow)
            }
        }))

        fun refresh() = ApplicationManager.getApplication().invokeLater {
            if (!toolWindow.isVisible) return@invokeLater
            refresh(project, toolWindow)
        }

        val docListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                refresh()
            }

            override fun bulkUpdateFinished(document: Document) {
                refresh()
            }
        }
        project.messageBus.connect().apply {
            subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    refresh()
                    FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(docListener)
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {}

                override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
            })
        }
    }

    private fun refresh(project: Project, toolWindow: ToolWindow) {
        val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return
        val treeUiState = cache.get(ktFile) {
            val treeModel = ObjectTreeModel(
                    ktFile,
                    KtElement::class,
                    { it }) { consumer ->
                ApplicationManager.getApplication().runReadAction {
                    this.takeIf { it.isValid }?.acceptChildren(object : KtVisitorVoid() {
                        override fun visitKtElement(element: KtElement) {
                            consumer(element)
                        }
                    })
                }
            }

            treeModel.setupTreeUi(project)
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
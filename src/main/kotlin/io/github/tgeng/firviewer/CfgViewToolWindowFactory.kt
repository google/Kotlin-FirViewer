package io.github.tgeng.firviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.fir.declarations.FirControlFlowGraphOwner
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirModuleResolveState
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getOrBuildFirSafe
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getResolveState
import org.jetbrains.kotlin.psi.KtElement


class CfgViewToolWindowFactory : ToolWindowFactory {

    val logger = Logger.getInstance(CfgViewToolWindowFactory::class.java)
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.title = "CfgViewer"
        toolWindow.setIcon(AllIcons.Toolwindows.ToolWindowAnt)
        val declarationView = SvgView()
        val fileView = SvgView()

        toolWindow.contentManager.addContent(
            toolWindow.contentManager.factory.createContent(
                declarationView,
                "Current Declaration",
                false
            )
        )
        toolWindow.contentManager.addContent(
            toolWindow.contentManager.factory.createContent(
                fileView,
                "Current File",
                false
            )
        )

        var currentGraphKey: CfgRenderService.GraphKey? = null
        fun refresh() {
            DumbService.getInstance(project).runWhenSmart {
                project.refreshFileView(toolWindow, fileView)
                (project.getCurrentGraphKey() ?: currentGraphKey)?.let {
                    project.refreshDeclarationView(toolWindow, declarationView, it)
                }
            }
        }

        val caretListener = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val key = project.getCurrentGraphKey()
                if (key != currentGraphKey && key != null) {
                    currentGraphKey = key
                    project.refreshDeclarationView(toolWindow, declarationView, key)
                }
            }
        }

        project.messageBus.connect()
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    refresh()
                    source.selectedTextEditor?.let { editor ->
                        editor.caretModel.addCaretListener(caretListener)
                    }
                }
            })

        project.messageBus.connect().subscribe(EVENT_TOPIC, Runnable { refresh() })
        toolWindow.setTitleActions(listOf(object :
            AnAction("Reset", "Reset pan and zoom", AllIcons.General.ActualZoom), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                when (toolWindow.contentManager.selectedContent?.toolwindowTitle) {
                    "Current Declaration" -> declarationView.reset()
                    "Current File" -> fileView.reset()
                }
            }
        },
            object : AnAction(), DumbAware {
                override fun update(e: AnActionEvent) {
                    e.presentation.icon = AllIcons.Actions.Refresh
                }

                override fun actionPerformed(e: AnActionEvent) = refresh()
            }
        ))

        refresh()
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.selectedTextEditor?.let { editor ->
            editor.caretModel.addCaretListener(caretListener)
        }
    }

    private fun Project.getCurrentGraphKey(): CfgRenderService.GraphKey? {
        if (DumbService.isDumb(this)) return null
        val editor = FileEditorManager.getInstance(this).selectedTextEditor ?: return null
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val offset: Int = editor.caretModel.offset
        var element = PsiManager.getInstance(this).findFile(vf)?.findElementAt(offset)
        while (element != null && element !is KtElement) {
            element = element.parent
        }
        if (element == null) return null
        var ktElement = element as KtElement?
        val resolveState = ktElement!!.getResolveState()
        var topLevelCfgEnterNode: CFGNode<*>? = null
        try {

            while (ktElement != null) {
                topLevelCfgEnterNode = ktElement.getTopLevelCfgEnterNode(resolveState)
                if (topLevelCfgEnterNode != null) break
                ktElement = ktElement.parentKtElement
            }
        } catch (e: Throwable) {
            logger.warn(e)
            return null
        }
        if (topLevelCfgEnterNode == null) return null
        return CfgRenderService.getInstance(this).getGraphKey(vf, topLevelCfgEnterNode)
    }

    private fun KtElement.getTopLevelCfgEnterNode(resolveState: FirModuleResolveState): CFGNode<*>? {
        val cfg = getOrBuildFirSafe<FirControlFlowGraphOwner>(resolveState)?.controlFlowGraphReference?.controlFlowGraph
            ?: return null
        if (cfg.owner != null) return null // Not a top level CFG
        return cfg.enterNode
    }

    private val KtElement.parentKtElement: KtElement?
        get() {
            var current = parent
            while (current != null && current !is KtElement) {
                current = current.parent
            }
            return current as KtElement?
        }

    private fun Project.refreshDeclarationView(
        toolWindow: ToolWindow,
        view: SvgView,
        graphKey: CfgRenderService.GraphKey
    ) {
        if (!toolWindow.isVisible) return
        CfgRenderService.getInstance(this).getSvg(graphKey).thenAccept {
            if (it != null) view.setSvg(it)
        }
    }

    private fun Project.refreshFileView(
        toolWindow: ToolWindow,
        view: SvgView,
    ) {
        if (!toolWindow.isVisible) return
        val editor = FileEditorManager.getInstance(this).selectedTextEditor ?: return
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val cfgRenderService = CfgRenderService.getInstance(this)
        cfgRenderService.getSvg(cfgRenderService.getGraphKey(vf)).thenAccept {
            if (it != null) view.setSvg(it)
        }
    }
}
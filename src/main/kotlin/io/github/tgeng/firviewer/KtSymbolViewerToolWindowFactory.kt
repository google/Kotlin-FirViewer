package io.github.tgeng.firviewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class KtSymbolViewerToolWindowFactory : ToolWindowFactory {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    toolWindow.title = "FirViewer"
    toolWindow.setIcon(AllIcons.Toolwindows.ToolWindowStructure)
    refresh(project, toolWindow)
  }

  private fun refresh(project: Project, toolWindow: ToolWindow) {

  }
}
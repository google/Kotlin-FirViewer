package io.github.tgeng.firviewer

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiFile
import com.intellij.util.messages.Topic
import org.jetbrains.kotlin.idea.highlighter.AbstractHighlightingPassBase

val EVENT_TOPIC = Topic.create("FIR_VIEWER_UPDATE_TOPIC", Runnable::class.java)

class NotificationInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor?>? {
        ApplicationManager.getApplication().invokeLater {
            file.project.messageBus.syncPublisher(EVENT_TOPIC).run()
        }
        return arrayOf()
    }
}


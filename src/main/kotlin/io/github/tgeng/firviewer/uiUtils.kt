package io.github.tgeng.firviewer

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.render
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

fun label(
    s: String,
    bold: Boolean = false,
    italic: Boolean = false,
    multiline: Boolean = false
) = JBLabel(
    if (multiline) ("<html>" + s.replace("\n", "<br/>").replace(" ", "&nbsp;") + "</html>") else s
).apply {
    font =
        font.deriveFont((if (bold) Font.BOLD else Font.PLAIN) + if (italic) Font.ITALIC else Font.PLAIN)
}

fun render(e: FirPureAbstractElement) = JBLabel(e.render())
fun type(e: TreeNode<*>): JComponent {
    val nameAndType = label(
        if (e.name == "") {
            ""
        } else {
            e.name + ": "
        } + e.t::class.simpleName,
        bold = true
    )
    val address = label("@" + Integer.toHexString(System.identityHashCode(e.t)))
    val nameTypeAndAddress = nameAndType + address
    return if (e.t is FirDeclaration) {
        nameTypeAndAddress + label(e.t.resolvePhase.toString(), italic = true)
    } else {
        nameTypeAndAddress
    }
}

private val twoPoint = JBUIScale.scale(2)

operator fun JComponent.plus(that: JComponent): JPanel {
    return if (this is JPanel) {
        add(that)
        this
    } else {
        JPanel(FlowLayout(FlowLayout.LEFT).apply {
            vgap = twoPoint
        }).apply {
            add(this@plus)
            add(that)
            isOpaque = false
        }
    }
}

fun highlightInEditor(obj: Any, project: Project) {
    val (startOffset, endOffset) = when (obj) {
        is FirPureAbstractElement -> obj.source?.let { it.startOffset to it.endOffset }
        else -> null
    } ?: return
    val editorManager = FileEditorManager.getInstance(project) ?: return
    val editor: Editor = editorManager.selectedTextEditor ?: return
    editor.markupModel.removeAllHighlighters()
    val textAttributes =
        TextAttributes(null, null, Color.GRAY, EffectType.LINE_UNDERSCORE, Font.PLAIN)
    editor.markupModel.addRangeHighlighter(
        startOffset,
        endOffset,
        HighlighterLayer.CARET_ROW,
        textAttributes,
        HighlighterTargetArea.EXACT_RANGE
    )
}
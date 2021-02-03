package io.github.tgeng.firviewer

import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import io.github.tgeng.firviewer.FirTreeNode
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.render
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

fun render(e: FirTreeNode) = JBLabel(e.firElement.render())
fun type(e: FirTreeNode): JComponent {
  val nameAndType = label(
    if (e.name == "") {
      ""
    } else {
      e.name + ": "
    } + e.firElement::class.simpleName,
    bold = true
  )
  val address = label("@" + Integer.toHexString(System.identityHashCode(e.firElement)))
  val nameTypeAndAddress = nameAndType + address
  return if (e.firElement is FirDeclaration) {
    nameTypeAndAddress + label(e.firElement.resolvePhase.toString(), italic = true)
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
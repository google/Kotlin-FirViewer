package io.github.tgeng.firviewer

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.render
import org.jetbrains.kotlin.fir.utils.ArrayMap
import org.jetbrains.kotlin.fir.utils.AttributeArrayOwner
import org.jetbrains.kotlin.idea.frontend.api.types.KtType
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.TableCellRenderer
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

object TableObjectRenderer : TableCellRenderer {
  override fun getTableCellRendererComponent(
    table: JTable,
    value: Any?,
    isSelected: Boolean,
    hasFocus: Boolean,
    row: Int,
    column: Int
  ): Component = when (value) {
    is Collection<*> -> label("size = " + value.size)
    is Map<*, *> -> label("size =" + value.size)
    is CFGNode<*> -> label(value.render())
    is PsiElement -> label(value.text, multiline = true)
    is KtType -> label(value.asStringForDebugging())
    is FirElement -> label(value.render(), multiline = true)
    is AttributeArrayOwner<*, *> -> {
      val arrayMap =
        value::class.memberProperties.first { it.name == "arrayMap" }.apply { isAccessible = true }
          .call(value) as ArrayMap<*>
      label("${arrayMap.size} attributes")
    }
    else -> label(value.toString())
  }.apply {
    if (table.isRowSelected(row)) {
      isOpaque = true
      background = table.selectionBackground
    }
  }
}
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

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.module.impl.scopes.ModuleWithDependenciesScope
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.stubs.ObjectStubTree
import com.intellij.psi.stubs.StubElement
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.render
import org.jetbrains.kotlin.util.ArrayMap
import org.jetbrains.kotlin.util.AttributeArrayOwner
import org.jetbrains.kotlin.analysis.api.ValidityTokenOwner
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtNamedSymbol
import org.jetbrains.kotlin.analysis.api.tokens.HackToForceAllowRunningAnalyzeOnEDT
import org.jetbrains.kotlin.analysis.api.tokens.hackyAllowRunningOnEdt
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.psi.KtDeclaration
import java.awt.Component
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellRenderer
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

@OptIn(HackToForceAllowRunningAnalyzeOnEDT::class)
object TableObjectRenderer : TableCellRenderer {
    override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
    ): Component = hackyAllowRunningOnEdt {
        if (value is ValidityTokenOwner && !value.token.isValid() || value is PsiElement && !value.isValid) {
            return@hackyAllowRunningOnEdt label(value.getTypeAndId() + " is no longer valid", italic = true)
        }
        render(value).apply {
            if (table.isRowSelected(row)) {
                isOpaque = true
                background = table.selectionBackground
            } else {
                isOpaque = false
                background = table.background
            }
        }
    }

    private fun render(value: Any?, renderBracket: Boolean = false): JComponent {
        fun myLabel(
                s: String,
                bold: Boolean = false,
                italic: Boolean = false,
                multiline: Boolean = false,
                icon: Icon? = null,
                tooltipText: String? = null
        ) = label(if (renderBracket) "{ $s }" else s, bold, italic, multiline, icon, tooltipText)
        return when (value) {
            is JComponent -> value
            is Collection<*> -> {
                if (value.size == 1) {
                    render(value.single(), renderBracket = true)
                } else {
                    myLabel("size = " + value.size)
                }
            }
            is Map<*, *> -> {
                if (value.size == 1) {
                    render(value.keys.single()) + label("->") + render(value.values.single())
                } else {
                    myLabel("size =" + value.size)
                }
            }
            is Array<*> -> myLabel("size = " + value.size)
            is BooleanArray -> myLabel("size = " + value.size)
            is ByteArray -> myLabel("size = " + value.size)
            is CharArray -> myLabel(String(value))
            is ShortArray -> myLabel("size = " + value.size)
            is IntArray -> myLabel("size = " + value.size)
            is LongArray -> myLabel("size = " + value.size)
            is DoubleArray -> myLabel("size = " + value.size)
            is FloatArray -> myLabel("size = " + value.size)
            is CFGNode<*> -> myLabel(value.render())
            is ItemPresentation -> myLabel(value.presentableText ?: "")
            is SingleRootFileViewProvider -> myLabel(value.virtualFile.toString())
            is ObjectStubTree<*>, is StubElement<*>, is ModuleWithDependenciesScope -> myLabel(
                    value.toString().replace(' ', '\n'), multiline = true
            )
            is Project -> myLabel("Project: " + value.name)
            is PsiFile -> myLabel(value.name)
            is KtDeclaration -> myLabel(value.text.takeWhile { it != '\n' })
            is PsiElement -> myLabel(value.text, multiline = true)
            is KtType -> myLabel(value.asStringForDebugging())
            is KtNamedSymbol -> myLabel(value.name.asString())
            is KtSymbol -> myLabel(value.psi?.text ?: "", multiline = true)
            is FirElement -> myLabel(value.render(), multiline = true)
            is AttributeArrayOwner<*, *> -> {
                val arrayMap =
                        value::class.memberProperties.first { it.name == "arrayMap" }.apply { isAccessible = true }
                                .call(value) as ArrayMap<*>
                myLabel("${arrayMap.size} attributes")
            }
            else -> myLabel(value.toString())
        }
    }
}
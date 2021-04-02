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
import org.jetbrains.kotlin.fir.utils.ArrayMap
import org.jetbrains.kotlin.fir.utils.AttributeArrayOwner
import org.jetbrains.kotlin.idea.frontend.api.ValidityTokenOwner
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtSymbol
import org.jetbrains.kotlin.idea.frontend.api.tokens.HackToForceAllowRunningAnalyzeOnEDT
import org.jetbrains.kotlin.idea.frontend.api.tokens.hackyAllowRunningOnEdt
import org.jetbrains.kotlin.idea.frontend.api.types.KtType
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.stubs.KotlinClassStub
import java.awt.Component
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
        when (value) {
            is JComponent -> value
            is Collection<*> -> label("size = " + value.size)
            is Map<*, *> -> label("size =" + value.size)
            is Array<*> -> label("size = " + value.size)
            is BooleanArray -> label("size = " + value.size)
            is ByteArray -> label("size = " + value.size)
            is CharArray -> label(String(value))
            is ShortArray -> label("size = " + value.size)
            is IntArray -> label("size = " + value.size)
            is LongArray -> label("size = " + value.size)
            is DoubleArray -> label("size = " + value.size)
            is FloatArray -> label("size = " + value.size)
            is CFGNode<*> -> label(value.render())
            is ItemPresentation -> label(value.presentableText ?: "")
            is SingleRootFileViewProvider -> label(value.virtualFile.toString())
            is ObjectStubTree<*>, is StubElement<*>, is ModuleWithDependenciesScope -> label(
                value.toString().replace(' ', '\n'), multiline = true
            )
            is Project -> label("Project: " + value.name)
            is PsiFile -> label(value.name)
            is KtDeclaration -> label(value.text.takeWhile { it != '\n' })
            is PsiElement -> label(value.text, multiline = true)
            is KtType -> label(value.asStringForDebugging())
            is KtSymbol -> label(value.psi?.text ?: "", multiline = true)
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
            } else {
                isOpaque = false
                background = table.background
            }
        }
    }
}
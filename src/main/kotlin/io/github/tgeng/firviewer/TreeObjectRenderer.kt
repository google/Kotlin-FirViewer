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

import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import org.jetbrains.kotlin.fir.FirLabel
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.fir.contracts.FirContractDescription
import org.jetbrains.kotlin.fir.contracts.FirEffectDeclaration
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirErrorFunction
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirImport
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.fir.expressions.FirArgumentList
import org.jetbrains.kotlin.fir.expressions.FirAssignmentOperatorStatement
import org.jetbrains.kotlin.fir.expressions.FirAugmentedArraySetCall
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirDelegatedConstructorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.impl.FirStubStatement
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.types.FirTypeProjection
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.psi.*
import java.awt.Component
import javax.swing.JTree
import javax.swing.tree.TreeCellRenderer

class TreeObjectRenderer : TreeCellRenderer {
    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        val node = value as? TreeNode<*> ?: return label("nothing to show")
        return when (val e = node.t) {
            is FirAnonymousInitializer -> type(node) + render(e)
            is FirArgumentList -> type(node) + render(e)
            is FirAssignmentOperatorStatement -> type(node) + render(e)
            is FirAugmentedArraySetCall -> type(node) + render(e)
            is FirCatch -> type(node) + render(e)
            is FirConstructor -> type(node) + label("<init>", icon = AllIcons.Nodes.Function)
            is FirContractDescription -> type(node) + render(e)
            is FirDeclarationStatusImpl -> type(node) + render(e)
            is FirDelegatedConstructorCall -> type(node) + render(e)
            is FirEffectDeclaration -> type(node) + render(e)
            is FirErrorFunction -> type(node) + render(e)
            is FirExpression -> type(node) + render(e)
            is FirFile -> type(node) + label(e.name)
            is FirImport -> type(node) + render(e)
            is FirLabel -> type(node) + render(e)
            is FirLoop -> type(node) + render(e)
            is FirPropertyAccessor -> type(node) + render(e)
            is FirReference -> type(node) + render(e)
            is FirRegularClass -> type(node) + label(e.name.asString(), icon = AllIcons.Nodes.Class)
            is FirSimpleFunction -> type(node) + label(e.name.asString(), icon = AllIcons.Nodes.Function)
            is FirStubStatement -> type(node) + render(e)
            is FirTypeAlias -> type(node) + render(e)
            is FirTypeParameter -> type(node) + render(e)
            is FirTypeProjection -> type(node) + render(e)
            is FirTypeRef -> type(node) + render(e)
            is FirProperty -> type(node) + label(e.name.asString(), icon = AllIcons.Nodes.Property)
            is FirVariable<*> -> type(node) + label(e.name.asString(), icon = AllIcons.Nodes.Variable)
            is FirVariableAssignment -> type(node) + render(e)
            is FirWhenBranch -> type(node) + render(e)
            // is FirConstructedClassTypeParameterRef,
            // is FirOuterClassTypeParameterRef,
            is FirTypeParameterRef -> type(node) + render(e as FirPureAbstractElement)
            is PsiFile -> type(node) + label(e.name)
            is PsiElement -> type(node) +
                    e.elementType?.let { label("[$it]", italic = true) } +
                    when (e) {
                        is KtDeclaration -> label(
                            e.name ?: "<anonymous>", icon = when (e) {
                                is KtClassOrObject -> AllIcons.Nodes.Class
                                is KtFunction -> AllIcons.Nodes.Function
                                is KtProperty -> AllIcons.Nodes.Property
                                is KtVariableDeclaration -> AllIcons.Nodes.Variable
                                else -> null
                            }
                        )
                        else -> label(e.text)
                    }
            else -> label(e.toString())
        }
    }
}
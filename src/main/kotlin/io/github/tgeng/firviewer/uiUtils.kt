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

import com.google.common.base.CaseFormat
import com.google.common.primitives.Primitives
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import org.jetbrains.kotlin.fir.FirPsiSourceElement
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.utils.AttributeArrayOwner
import org.jetbrains.kotlin.idea.frontend.api.tokens.HackToForceAllowRunningAnalyzeOnEDT
import org.jetbrains.kotlin.idea.frontend.api.tokens.hackyAllowRunningOnEdt
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod

fun label(
        s: String,
        bold: Boolean = false,
        italic: Boolean = false,
        multiline: Boolean = false,
        icon: Icon? = null,
        tooltipText: String? = null
) = JBLabel(
        if (multiline) ("<html>" + s.replace("\n", "<br/>").replace(" ", "&nbsp;") + "</html>") else s
).apply {
    this.icon = icon
    this.toolTipText = toolTipText
    font = font.deriveFont((if (bold) Font.BOLD else Font.PLAIN) + if (italic) Font.ITALIC else Font.PLAIN)
}

fun render(e: FirPureAbstractElement) = JBLabel(e.render())
fun type(e: TreeNode<*>): JComponent {
    val nameAndType = label(
            if (e.name == "" || e.name.startsWith('<')) {
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

operator fun JComponent.plus(that: JComponent?): JPanel {
    return if (this is JPanel) {
        add(that)
        this
    } else {
        JPanel(FlowLayout(FlowLayout.LEFT).apply {
            vgap = twoPoint
        }).apply {
            add(this@plus)
            if (that != null) add(that)
            isOpaque = false
        }
    }
}

fun highlightInEditor(obj: Any, project: Project) {
    val editorManager = FileEditorManager.getInstance(project) ?: return
    val editor: Editor = editorManager.selectedTextEditor ?: return
    editor.markupModel.removeAllHighlighters()
    val (vf, startOffset, endOffset) = when (obj) {
        is FirPureAbstractElement -> obj.source?.let {
            val source = it as? FirPsiSourceElement<*> ?: return@let null
            FileLocation(source.psi.containingFile.virtualFile, it.startOffset, it.endOffset)
        }
        is PsiElement -> obj.textRange?.let {
            FileLocation(
                    obj.containingFile.virtualFile,
                    it.startOffset,
                    it.endOffset
            )
        }
        is CFGNode<*> -> obj.fir.source?.let {
            val source = it as? FirPsiSourceElement<*> ?: return@let null
            FileLocation(source.psi.containingFile.virtualFile, it.startOffset, it.endOffset)
        }
        else -> null
    } ?: return
    if (vf != FileEditorManager.getInstance(project).selectedFiles.firstOrNull()) return

    val textAttributes =
            TextAttributes(null, null, Color.GRAY, EffectType.BOXED, Font.PLAIN)
    editor.markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.CARET_ROW,
            textAttributes,
            HighlighterTargetArea.EXACT_RANGE
    )
}

private data class FileLocation(val vf: VirtualFile, val startIndex: Int, val endIndex: Int)

val unitType = Unit::class.createType()
val skipMethodNames = setOf(
        "copy",
        "toString",
        "delete",
        "clone",
        "getUserDataString",
        "hashCode",
        "getClass",
        "component1",
        "component2",
        "component3",
        "component4",
        "component5"
)
val psiElementMethods = PsiElement::class.java.methods.map { it.name }.toSet() - setOf(
        "getTextRange",
        "getTextRangeInParent",
        "getTextLength",
        "getText",
        "getResolveScope",
        "getUseScope"
)

@OptIn(HackToForceAllowRunningAnalyzeOnEDT::class)
fun Any.traverseObjectProperty(
        propFilter: (KCallable<*>) -> Boolean = { true }, methodFilter: (Method) -> Boolean = { true },
        fn: (name: String, value: Any?, () -> Any?) -> Unit
) {
    try {
        this::class.members
                .filter { propFilter(it) && it.parameters.size == 1 && it.visibility == KVisibility.PUBLIC && it.returnType != unitType && it.name !in skipMethodNames && (this !is PsiElement || it.name !in psiElementMethods) }
                .sortedWith { m1, m2 ->
                    fun KCallable<*>.declaringClass() = when (this) {
                        is KFunction<*> -> javaMethod?.declaringClass
                        is KProperty<*> -> javaGetter?.declaringClass
                        else -> null
                    }

                    val m1Class = m1.declaringClass()
                    val m2Class = m2.declaringClass()
                    when {
                        m1Class == m2Class -> 0
                        m1Class == null -> 1
                        m2Class == null -> -1
                        m1Class.isAssignableFrom(m2Class) -> -1
                        else -> 1
                    }
                }
                .forEach { prop ->
                    val value = try {
                        prop.isAccessible = true
                        hackyAllowRunningOnEdt {
                            prop.call(this)
                        }
                    } catch (e: Throwable) {
                        return@forEach
                    }
                    fn(prop.name, value) {
                        hackyAllowRunningOnEdt {
                            prop.call(this)
                        }
                    }
                }
    } catch (e: Throwable) {
        // fallback to traverse with Java reflection
        this::class.java.methods
                .filter { methodFilter(it) && it.name !in skipMethodNames && it.parameterCount == 0 && it.modifiers and Modifier.PUBLIC != 0 && it.returnType.simpleName != "void" && (this !is PsiElement || it.name !in psiElementMethods) }
                // methods in super class is at the beginning
                .sortedWith { m1, m2 ->
                    when {
                        m1.declaringClass == m2.declaringClass -> 0
                        m1.declaringClass.isAssignableFrom(m2.declaringClass) -> -1
                        else -> 1
                    }
                }
                .distinctBy { it.name }
                .forEach { method ->
                    val value = try {
                        hackyAllowRunningOnEdt {
                            method.invoke(this)
                        }
                    } catch (e: Throwable) {
                        return@forEach
                    }
                    fn(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, method.name.removePrefix("get")), value) {
                        hackyAllowRunningOnEdt {
                            method.invoke(this)
                        }
                    }
                }
    }
}

fun Any.getTypeAndId(): String {
    return when {
        isData() -> this::class.simpleName
            ?: this::class.toString()
        else -> this::class.simpleName + " @" + Integer.toHexString(System.identityHashCode(this))
    }
}

fun Any.getValueAndId(): String {
    return when {
        isData() -> toString()
        else -> this::class.simpleName + " @" + Integer.toHexString(System.identityHashCode(this))
    }
}

fun Any.isData(): Boolean = try {
    this is Iterable<*> || this is Map<*, *> || this is AttributeArrayOwner<*, *> ||
            this is Enum<*> || this::class.java.isPrimitive || Primitives.isWrapperType(this::class.java) ||
            this::class.java == String::class.java || this::class.java == Name::class.java ||
            this::class.isData || this::class.objectInstance != null
} catch (e: Throwable) {
    false
}


//private class CfgGraphViewer(state: TreeUiState, index: Int, graph: ControlFlowGraph) :
//  ObjectViewer(state, index) {
//
//  private val nodeNameMap = mutableMapOf<CFGNode<*>, String>()
//  private val nodeClassCounter = mutableMapOf<String, AtomicInteger>()
//  val CFGNode<*>.name:String get() = nodeNameMap.computeIfAbsent(this) { node ->
//    val nodeClassName = (node::class.simpleName?:node::class.toString()).removeSuffix("Node")
//    nodeClassName + nodeClassCounter.computeIfAbsent(nodeClassName) { AtomicInteger() }.getAndIncrement()
//  }
//
//  private val graph = SingleGraph("foo").apply {
//    graph.nodes.forEach { node ->
//      addNode(node.name)
//    }
//    val edgeCounter = AtomicInteger()
//    val edgeNameMap = mutableMapOf<String, EdgeData>()
//    graph.nodes.forEach { node ->
//      node.followingNodes.forEach { to ->
//        val edgeId = edgeCounter.getAndIncrement().toString()
//        addEdge(edgeId, node.name, to.name)
//      }
//    }
//  }
//
//  data class EdgeData(val from:CFGNode<*>, val to: CFGNode<*>, val edge: Edge?)
//
//  val viewer = SwingViewer(this.graph, Viewer.ThreadingModel.GRAPH_IN_ANOTHER_THREAD).apply {
//    enableAutoLayout()
//  }
//  override val view: JComponent = viewer.addDefaultView(false) as JComponent
//
//  override fun selectAndGetObject(name: String): Any? {
//    return null
//  }
//}

package io.github.tgeng.firviewer

import com.google.common.base.CaseFormat
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBLabel
import com.intellij.ui.scale.JBUIScale
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.render
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
        is PsiElement -> obj.startOffset to obj.endOffset
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

val unitType = Unit::class.createType()
val skipMethodNames = setOf("copy", "toString", "delete", "clone", "getUserDataString", "hashCode") + PsiElement::class.java.methods.map { it.name }

fun Any.traverseObjectProperty(propFilter: (KCallable<*>) -> Boolean = { true }, methodFilter: (Method) -> Boolean = { true },
                               fn: (name: String, value: Any?) -> Unit) {
    try {
        this::class.members
                .filter { propFilter(it) && it.parameters.size == 1 && it.visibility == KVisibility.PUBLIC && it.returnType != unitType && it.name !in skipMethodNames }
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
                        prop.call(this)
                    } catch (e: Throwable) {
                        return@forEach
                    }
                    fn(prop.name, value)
                }
    } catch (e: Throwable) {
        // fallback to traverse with Java reflection
        this::class.java.methods
                .filter { methodFilter(it) && it.name !in skipMethodNames && it.parameterCount == 0 && it.modifiers and Modifier.PUBLIC != 0 && it.returnType.simpleName != "void" }
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
                        method.invoke(this)
                    } catch (e: Throwable) {
                        return@forEach
                    }
                    fn(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, method.name.removePrefix("get")), value)
                }
    }
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


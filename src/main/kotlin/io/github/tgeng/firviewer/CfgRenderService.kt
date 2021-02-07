package io.github.tgeng.firviewer

import com.google.common.cache.CacheBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.kitfox.svg.SVGDiagram
import com.kitfox.svg.SVGUniverse
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.FirControlFlowGraphRenderVisitor
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.render
import org.jetbrains.kotlin.idea.debugger.readAction
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getFirFile
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getResolveState
import org.jetbrains.kotlin.psi.KtFile
import java.awt.Color
import java.io.File
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@Service
class CfgRenderService(private val project: Project) {
    private val universe = SVGUniverse()
    private val logger = getInstance(CfgRenderService::class.java)
    private val cache = CacheBuilder.newBuilder().weakKeys().build<VirtualFile, Int>()

    fun getSvg(key: GraphKey): CompletableFuture<SVGDiagram?> {
        val graph = getGraphString(key.virtualFile)
        return getGraphSvg(key, graph)
    }

    private fun getGraphString(vf: VirtualFile): String {
        val sb = StringBuilder()
        ApplicationManager.getApplication().readAction {
            val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return@readAction
            val firFile = ktFile.getFirFile(ktFile.getResolveState())
            firFile.accept(FirControlFlowGraphRenderVisitor(sb))
        }
        return sb.toString()
    }

    private fun getGraphSvg(key: GraphKey, graph: String): CompletableFuture<SVGDiagram?> {
        if (!isDotAvailable()) return CompletableFuture.completedFuture(null)
        return CompletableFuture.supplyAsync({
            val vf = key.virtualFile
            val storedHash = cache.get(vf) { 0 }
            if (storedHash != graph.hashCode()) {
                cache.put(vf, graph.hashCode())
                vf.getScratchDir().deleteRecursively()
                val processes = mutableListOf<Pair<GraphKey, Process>>()
                for ((name, graph) in splitGraphs(graph)) {
                    val graphKey = GraphKey(vf, name)
                    graphKey.getDotFile().apply {
                        parentFile.mkdirs()
                        writeText(graph)
                        processes += graphKey to ProcessBuilder(
                            "dot",
                            this.absolutePath,
                            "-Tsvg",
                            "-o",
                            graphKey.getSvgFile().absolutePath
                        ).start()
                    }
                }
                processes.forEach { (key, process) ->
                    process.waitFor()
                    if (process.exitValue() == 0) {
                        key.getSvgFile().writeText(key.getSvgFile().readText().replace("stroke=\"transparent\"", ""))
                    } else {
                        logger.error("Failed to convert ${key.getDotFile()} to PNG with dot.")
                        key.getSvgFile().delete()
                    }
                }
            }
            if (!key.getSvgFile().exists()) {
                return@supplyAsync null
            }
            key.getSvgFile().inputStream().use { it ->
                universe.getDiagram(universe.loadSVG(it, UUID.randomUUID().toString()))
            }
        }, executorService)
    }

    fun getGraphKey(virtualFile: VirtualFile) = GraphKey(virtualFile, "")

    fun getGraphKey(virtualFile: VirtualFile, node: CFGNode<*>) =
        GraphKey(virtualFile, node.render().replace("\"", ""))

    data class GraphKey(val virtualFile: VirtualFile, val name: String)

    private fun GraphKey.getDotFile(): File = virtualFile.getScratchDir().resolve(name + ".dot")
    private fun GraphKey.getSvgFile(): File = virtualFile.getScratchDir().resolve(name + ".svg")
    private fun VirtualFile.getScratchDir(): File =
        rendersRoot.resolve("cfg" + VfsUtil.virtualToIoFile(this).absolutePath)

    companion object {
        private val rendersRoot = File(System.getProperty("java.io.tmpdir")).resolve("firviewer")
        private val executorService = Executors.newSingleThreadExecutor()

        fun isDotAvailable(): Boolean {
            val process = ProcessBuilder("dot", "-V").start()
            process.waitFor()
            return process.exitValue() == 0
        }

        fun getInstance(project: Project): CfgRenderService = project.getService(CfgRenderService::class.java)
    }
}

private val labelNameRegex = Regex(""".*\[label="([^"]*)".*""")

fun splitGraphs(graph: String): Map<String, String> {
    val lines = graph.split(System.lineSeparator())
    val firstSubGraphIndex = lines.indexOfFirst { it.startsWith("    subgraph cluster") }
    if (firstSubGraphIndex == -1) return emptyMap() // graph is not initialized yet
    val dark = JBColor.PanelBackground.brightness() < 0.5
    val header = if (dark) """
digraph atLeastOnce_kt {
    graph [nodesep=3 fontname="Arial" fontsize=24 bgcolor="${JBColor.PanelBackground.hex()}" color=white]
    node [shape=box margin="0.15,0.05" width=0 height=0 fontname="Arial" fontsize=24 color=white fontcolor=white]
    edge [penwidth=2 fontname="Arial" fontsize=24 len=0.5 color=white]
    """.trimIndent().split("\n") else
        """
digraph atLeastOnce_kt {
    graph [nodesep=3 fontname="Arial" fontsize=24 bgcolor="${JBColor.PanelBackground.hex()}"]
    node [shape=box margin="0.15,0.05" width=0 height=0 fontname="Arial" fontsize=24]
    edge [penwidth=2 fontname="Arial" fontsize=24 len=0.5]
    """.trimIndent().split("\n")


    val subGraphs = mutableListOf<List<String>>()
    var currentSubGraphIndex = firstSubGraphIndex
    while (true) {
        var nextSubGraphIndex = -1
        for (i in currentSubGraphIndex + 1 until lines.size) {
            if (lines[i].startsWith("    subgraph cluster")) {
                nextSubGraphIndex = i
                break
            }
        }
        if (nextSubGraphIndex == -1) {
            // no more
            subGraphs.add(lines.subList(currentSubGraphIndex, lines.indexOfLast { it.startsWith("}") }))
            break
        } else {
            subGraphs.add(lines.subList(currentSubGraphIndex, nextSubGraphIndex))
        }
        currentSubGraphIndex = nextSubGraphIndex
    }

    fun List<String>.replaceGraphColors(): String =
        (header + this + listOf("}")).joinToString(System.lineSeparator()) {
            if (dark) {
                it.replace("=blue", "=\"#2abbd1\"")
                    .replace("=gray", "=\"#7a7a7a\"")
                    .replace("=green", "=\"#44ff3d\"")
            } else {
                it.replace("=green", "=\"#3acf61\"")
                    .replace("=gray", "=\"#bdbdbd\"")
            }
        }

    return (subGraphs.map { subGraph ->
        val key = subGraph.asSequence().mapNotNull { labelNameRegex.matchEntire(it) }.first().groupValues[1]
        val value = subGraph.replaceGraphColors()
        key to value
    } + ("" to subGraphs.flatten().replaceGraphColors())).toMap()
}


private fun Color.hex(): String = String.format("#%02x%02x%02x", red, green, blue);

private fun Color.brightness(): Double = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255
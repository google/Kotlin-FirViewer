package io.github.tgeng.firviewer

import com.google.common.collect.ImmutableMap
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.kitfox.svg.SVGDiagram
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.RenderingHints.KEY_INTERPOLATION
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import javax.swing.JPanel
import kotlin.math.pow


class SvgView : JPanel() {

    private var _svg: SVGDiagram? = null
    private val transform: AffineTransform = AffineTransform()
    private val baseTransform = AffineTransform()

    init {
        var startX = 0
        var startY = 0
        val startTransform = AffineTransform()
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                startX = e.x
                startY = e.y
                startTransform.setTransform(transform)
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                transform.setTransform(startTransform)
                transform.translate(
                    (e.x.toDouble() - startX) / (transform.scaleX * baseTransform.scaleX),
                    (e.y.toDouble() - startY) / (transform.scaleY * baseTransform.scaleY)
                )
                repaint()
            }
        })
        addMouseWheelListener { e ->
            val scale = 2.0.pow(-e.preciseWheelRotation * 0.2)
            val mouseP = Point2D.Double(e.x.toDouble(), e.y.toDouble())
            transform.preConcatenate(baseTransform)
            transform.scaleAt(mouseP, scale)
            transform.preConcatenate(baseTransform.createInverse())
            repaint()
        }
    }

    fun setSvg(svg: SVGDiagram) {
        ApplicationManager.getApplication().invokeLater {
            _svg = svg
            repaint()
        }
    }

    fun reset() {
        ApplicationManager.getApplication().invokeLater {
            transform.setToIdentity()
            repaint()
        }
    }

    override fun paintComponent(g: Graphics) {
        val svg = this._svg
        val width = size.width
        val height = size.height
        g.color = JBColor.PanelBackground
        g.fillRect(0, 0, width, height)
        if (g !is Graphics2D || svg == null) {
            return
        }
        baseTransform.setToIdentity()
        val x = (width - svg.width) / 2.0
        val y = (height - svg.height) / 2.0
        baseTransform.translate(x, y)
        baseTransform.scaleAt(Point2D.Double(width / 2.0, height / 2.0), 0.5)
        g.transform(baseTransform)
        g.transform(this.transform)
        g.setRenderingHints(renderHints)
        svg.render(g)
    }
}

private val renderHints: Map<RenderingHints.Key, Any> = ImmutableMap.builder<RenderingHints.Key, Any>()
    .put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    .put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
    .put(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE)
    .put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    .put(KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    .put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    .put(
        RenderingHints.KEY_ALPHA_INTERPOLATION,
        RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY
    )
    .build()


fun AffineTransform.scaleAt(pInScreenSpace: Point2D.Double, scale: Double) {
    val pInDrawSpace = this.createInverse().transform(pInScreenSpace)
    scale(scale, scale)
    val transformedPInScreenSpace = this.transform(pInDrawSpace)
    translate(
        (pInScreenSpace.x - transformedPInScreenSpace.x) / scaleX,
        (pInScreenSpace.y - transformedPInScreenSpace.y) / scaleY
    )
}

private fun AffineTransform.transform(p: Point2D.Double): Point2D.Double {
    val result = Point2D.Double()
    transform(p, result)
    return result
}


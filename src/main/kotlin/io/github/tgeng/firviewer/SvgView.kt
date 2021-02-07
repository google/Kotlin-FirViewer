package io.github.tgeng.firviewer

import com.google.common.collect.ImmutableMap
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.kitfox.svg.SVGDiagram
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.RenderingHints.KEY_INTERPOLATION
import java.awt.event.*
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import javax.swing.JPanel
import kotlin.math.pow


class SvgView : JPanel() {

    private var _svg: SVGDiagram? = null
    private val transform: AffineTransform = AffineTransform()

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
                    (e.x.toDouble() - startX) / transform.scaleX,
                    (e.y.toDouble() - startY) / transform.scaleY
                )
                repaint()
            }
        })
        addMouseWheelListener { e ->
            val scale = 2.0.pow(-e.preciseWheelRotation * 0.2)
            val mouseP = Point2D.Double(e.x.toDouble(), e.y.toDouble())
            transform.scaleAt(mouseP, scale)
            repaint()
        }
        val size = size
        var prevWidth = size.width
        var prevHeight = size.height
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(componentEvent: ComponentEvent) {
                val newSize = this@SvgView.size
                transform.translate(
                    (newSize.width - prevWidth) / transform.scaleX / 2,
                    (newSize.height - prevHeight) / transform.scaleY / 2
                )
                prevWidth = newSize.width
                prevHeight = newSize.height
            }
        })
    }

    fun setSvg(svg: SVGDiagram) {
        ApplicationManager.getApplication().invokeLater {
            val oldSvg = _svg
            if (oldSvg == null) {
                resetTransform(svg)
            } else {
                transform.translate((oldSvg.width - svg.width) / 2.0, (oldSvg.height - svg.height) / 2.0)
            }
            _svg = svg
            repaint()
        }
    }

    fun reset() {
        val svg = _svg ?: return
        ApplicationManager.getApplication().invokeLater {
            resetTransform(svg)
            repaint()
        }
    }

    private fun resetTransform(svg: SVGDiagram) {
        val scale = if (SystemInfo.isMac) {
            // The SVG library seems to have a bug on Mac where the width and height is doubled.
            0.25
        } else {
            0.5
        }
        transform.setTransform(
            scale,
            0.0,
            0.0,
            scale,
            (size.width - svg.width * scale) / 2,
            (size.height - svg.height * scale) / 2
        )
    }

    override fun paintComponent(g: Graphics) {
        val svg = this._svg
        g.color = JBColor.PanelBackground
        g.fillRect(0, 0, size.width, size.height)
        if (g !is Graphics2D || svg == null) {
            return
        }
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


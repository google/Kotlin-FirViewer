package io.github.tgeng.firviewer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints.KEY_INTERPOLATION
import java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import kotlin.math.pow


class ImageView : JPanel() {

    private var _image: BufferedImage? = null
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

    fun setImage(image: BufferedImage) {
        ApplicationManager.getApplication().invokeLater {
            _image = image
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
        val image = this._image
        val width = size.width
        val height = size.height
        g.color = JBColor.PanelBackground
        g.fillRect(0, 0, width, height)
        if (g !is Graphics2D || image == null) {
            return
        }
        baseTransform.setToIdentity()
        baseTransform.scaleAt(Point2D.Double(width / 2.0, height / 2.0), 0.5)
        g.transform(baseTransform)
        g.transform(this.transform)
        val x = (width - image.width) / 2
        val y = (height - image.height) / 2
        g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(image, x, y, null)
    }
}

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


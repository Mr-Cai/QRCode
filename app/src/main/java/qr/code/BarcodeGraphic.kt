package qr.code

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

import com.google.android.gms.vision.barcode.Barcode

import qr.code.camera.GraphicOverlay

//用于在关联的图形叠加视图中呈现条形码位置,大小和ID的图形实例
class BarcodeGraphic(overlay: GraphicOverlay<*>) : GraphicOverlay.Graphic(overlay) {
    private var id: Int = 0
    private val mRectPaint: Paint
    private val mTextPaint: Paint
    var barcode: Barcode? = null

    init {
        mCurrentColorIndex = (mCurrentColorIndex + 1) % COLOR_CHOICES.size
        val selectedColor = COLOR_CHOICES[mCurrentColorIndex]
        mRectPaint = Paint()
        mRectPaint.color = selectedColor
        mRectPaint.style = Paint.Style.STROKE
        mRectPaint.strokeWidth = 4.0f
        mTextPaint = Paint()
        mTextPaint.color = selectedColor
        mTextPaint.textSize = 36.0f
    }

    fun setId(id: Int) {
        this.id = id
    }

    internal fun updateItem(barcode: Barcode) { //从检测到最近的帧更新条形码实例,使叠加层的相关部分无效以触发重绘
        this.barcode = barcode
        postInvalidate()
    }

    override fun draw(canvas: Canvas) { //绘制条形码周围的边界框
        val barcode = this.barcode ?: return
        val rect = RectF(barcode.boundingBox)
        rect.left = translateX(rect.left)
        rect.top = translateY(rect.top)
        rect.right = translateX(rect.right)
        rect.bottom = translateY(rect.bottom)
        canvas.drawRect(rect, mRectPaint)
        //在条形码底部绘制标签，指示检测到的条形码值
        canvas.drawText(barcode.rawValue, rect.left, rect.bottom, mTextPaint)
    }

    companion object {
        private val COLOR_CHOICES = intArrayOf(Color.BLUE, Color.CYAN, Color.GREEN)
        private var mCurrentColorIndex = 0
    }
}

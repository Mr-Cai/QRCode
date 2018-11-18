package qr.code.camera

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import java.util.*

class GraphicOverlay<T : GraphicOverlay.Graphic>(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val mLock = Any()
    private var mPreviewWidth: Int = 0
    //返回水平比例因子
    var widthScaleFactor = 1.0f
    private var mPreviewHeight: Int = 0
    //返回垂直比例因子
    var heightScaleFactor = 1.0f
    private var mFacing = CameraSource.CAMERA_FACING_BACK
    private val mGraphics = HashSet<T>()

    //返回所有活动图形的列表。
    val graphics: Vector<T>
        get() = synchronized(mLock) {
            return Vector(mGraphics)
        }

    //要在图形叠加层中呈现的自定义图形对象的基类。 对此进行子类化并实现方法来定义图形元素.
    // 使用将实例添加到叠加层。
    abstract class Graphic(//将提供的值的垂直值从预览比例调整为视图比例
            private val mOverlay: GraphicOverlay<*>) {

        abstract fun draw(canvas: Canvas)

        private fun scaleX(horizontal: Float): Float {
            return horizontal * mOverlay.widthScaleFactor
        }

        private fun scaleY(vertical: Float): Float {
            return vertical * mOverlay.heightScaleFactor
        }

        protected fun translateX(x: Float): Float { //调整从预览坐标系到视图坐标的x坐标
            return if (mOverlay.mFacing == CameraSource.CAMERA_FACING_FRONT) {
                mOverlay.width - scaleX(x)
            } else {
                scaleX(x)
            }
        }

        protected fun translateY(y: Float): Float { //调整从预览坐标系到视图坐标系的y坐标
            return scaleY(y)
        }

        protected fun postInvalidate() {
            mOverlay.postInvalidate()
        }
    }

    fun clear() { //从叠加层中删除所有图形。
        synchronized(mLock) {
            mGraphics.clear()
        }
        postInvalidate()
    }

    fun add(graphic: T) { //向叠加层添加图形。
        synchronized(mLock) {
            mGraphics.add(graphic)
        }
        postInvalidate()
    }

    fun remove(graphic: T) { //从叠加层中删除图形
        synchronized(mLock) {
            mGraphics.remove(graphic)
        }
        postInvalidate()
    }

    //设置大小和面向方向的摄像机属性，以便稍后通知如何变换图像坐标。
    fun setCameraInfo(previewWidth: Int, previewHeight: Int, facing: Int) {
        synchronized(mLock) {
            mPreviewWidth = previewWidth
            mPreviewHeight = previewHeight
            mFacing = facing
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) { //使用关联的图形对象绘制叠加层
        super.onDraw(canvas)
        synchronized(mLock) {
            if (mPreviewWidth != 0 && mPreviewHeight != 0) {
                widthScaleFactor = width.toFloat() / mPreviewWidth.toFloat()
                heightScaleFactor = height.toFloat() / mPreviewHeight.toFloat()
            }
            for (graphic in mGraphics) {
                graphic.draw(canvas)
            }
        }
    }
}

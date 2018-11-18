package qr.code.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

public class GraphicOverlay<T extends GraphicOverlay.Graphic> extends View {
    private final Object mLock = new Object();
    private int mPreviewWidth;
    private float mWidthScaleFactor = 1.0f;
    private int mPreviewHeight;
    private float mHeightScaleFactor = 1.0f;
    private int mFacing = CameraSource.CAMERA_FACING_BACK;
    private Set<T> mGraphics = new HashSet<>();

    //要在图形叠加层中呈现的自定义图形对象的基类。 对此进行子类化并实现方法来定义图形元素.
    // 使用将实例添加到叠加层。
    public static abstract class Graphic { //将提供的值的垂直值从预览比例调整为视图比例
        private GraphicOverlay mOverlay;

        public Graphic(GraphicOverlay overlay) {
            mOverlay = overlay;
        }

        public abstract void draw(Canvas canvas);

        float scaleX(float horizontal) {
            return horizontal * mOverlay.mWidthScaleFactor;
        }

        float scaleY(float vertical) {
            return vertical * mOverlay.mHeightScaleFactor;
        }

        protected float translateX(float x) { //调整从预览坐标系到视图坐标的x坐标
            if (mOverlay.mFacing == CameraSource.CAMERA_FACING_FRONT) {
                return mOverlay.getWidth() - scaleX(x);
            } else {
                return scaleX(x);
            }
        }

        protected float translateY(float y) { //调整从预览坐标系到视图坐标系的y坐标
            return scaleY(y);
        }

        protected void postInvalidate() {
            mOverlay.postInvalidate();
        }
    }

    public GraphicOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void clear() { //从叠加层中删除所有图形。
        synchronized (mLock) {
            mGraphics.clear();
        }
        postInvalidate();
    }

    public void add(T graphic) { //向叠加层添加图形。
        synchronized (mLock) {
            mGraphics.add(graphic);
        }
        postInvalidate();
    }

    public void remove(T graphic) { //从叠加层中删除图形
        synchronized (mLock) {
            mGraphics.remove(graphic);
        }
        postInvalidate();
    }

    public Vector<T> getGraphics() { //返回所有活动图形的列表。
        synchronized (mLock) {
            return new Vector<>(mGraphics);
        }
    }

    public float getWidthScaleFactor() { //返回水平比例因子
        return mWidthScaleFactor;
    }

    public float getHeightScaleFactor() { //返回垂直比例因子
        return mHeightScaleFactor;
    }

    //设置大小和面向方向的摄像机属性，以便稍后通知如何变换图像坐标。
    public void setCameraInfo(int previewWidth, int previewHeight, int facing) {
        synchronized (mLock) {
            mPreviewWidth = previewWidth;
            mPreviewHeight = previewHeight;
            mFacing = facing;
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) { //使用关联的图形对象绘制叠加层
        super.onDraw(canvas);

        synchronized (mLock) {
            if ((mPreviewWidth != 0) && (mPreviewHeight != 0)) {
                mWidthScaleFactor = (float) getWidth() / (float) mPreviewWidth;
                mHeightScaleFactor = (float) getHeight() / (float) mPreviewHeight;
            }
            for (Graphic graphic : mGraphics) {
                graphic.draw(canvas);
            }
        }
    }
}

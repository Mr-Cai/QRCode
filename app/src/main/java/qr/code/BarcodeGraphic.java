package qr.code;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.google.android.gms.vision.barcode.Barcode;

import qr.code.camera.GraphicOverlay;

//用于在关联的图形叠加视图中呈现条形码位置,大小和ID的图形实例
public class BarcodeGraphic extends GraphicOverlay.Graphic {
    private int mId;
    private static final int[] COLOR_CHOICES = {
            Color.BLUE,
            Color.CYAN,
            Color.GREEN
    };
    private static int mCurrentColorIndex = 0;
    private Paint mRectPaint;
    private Paint mTextPaint;
    private volatile Barcode mBarcode;

    BarcodeGraphic(GraphicOverlay overlay) {
        super(overlay);
        mCurrentColorIndex = (mCurrentColorIndex + 1) % COLOR_CHOICES.length;
        final int selectedColor = COLOR_CHOICES[mCurrentColorIndex];
        mRectPaint = new Paint();
        mRectPaint.setColor(selectedColor);
        mRectPaint.setStyle(Paint.Style.STROKE);
        mRectPaint.setStrokeWidth(4.0f);
        mTextPaint = new Paint();
        mTextPaint.setColor(selectedColor);
        mTextPaint.setTextSize(36.0f);
    }

    public void setId(int id) {
        this.mId = id;
    }

    Barcode getBarcode() {
        return mBarcode;
    }

    void updateItem(Barcode barcode) { //从检测到最近的帧更新条形码实例,使叠加层的相关部分无效以触发重绘
        mBarcode = barcode;
        postInvalidate();
    }

    @Override
    public void draw(Canvas canvas) { //绘制条形码周围的边界框
        Barcode barcode = mBarcode;
        if (barcode == null) return;
        RectF rect = new RectF(barcode.getBoundingBox());
        rect.left = translateX(rect.left);
        rect.top = translateY(rect.top);
        rect.right = translateX(rect.right);
        rect.bottom = translateY(rect.bottom);
        canvas.drawRect(rect, mRectPaint);
        //在条形码底部绘制标签，指示检测到的条形码值
        canvas.drawText(barcode.rawValue, rect.left, rect.bottom, mTextPaint);
    }
}

package qr.code;

import android.content.Context;

import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.barcode.Barcode;

import androidx.annotation.UiThread;
import qr.code.camera.GraphicOverlay;

//通用跟踪器,用于跟踪或读取条形码(可复用)并接收新检测到的项目
public class BarcodeGraphicTracker extends Tracker<Barcode> {
    private GraphicOverlay<BarcodeGraphic> mOverlay;
    private BarcodeGraphic mGraphic;
    private BarcodeUpdateListener mBarcodeUpdateListener;

    //通过在检测条码上实现更新接口方法,消耗从活动或碎片级别检测到的子项实例
    public interface BarcodeUpdateListener {
        @UiThread
        void onBarcodeDetected(Barcode barcode);
    }

    BarcodeGraphicTracker(GraphicOverlay<BarcodeGraphic> mOverlay, BarcodeGraphic mGraphic,
                          Context context) {
        this.mOverlay = mOverlay;
        this.mGraphic = mGraphic;
        if (context instanceof BarcodeUpdateListener) {
            this.mBarcodeUpdateListener = (BarcodeUpdateListener) context;
        } else {
            throw new RuntimeException("Hosting activity must implement BarcodeUpdateListener");
        }
    }

    @Override
    public void onNewItem(int id, Barcode item) { //开始跟踪项目图层中的检测到的子项实例
        mGraphic.setId(id);
        mBarcodeUpdateListener.onBarcodeDetected(item);
    }

    @Override //更新叠加层中子项的位置特征
    public void onUpdate(Detector.Detections<Barcode> detectionResults, Barcode item) {
        mOverlay.add(mGraphic);
        mGraphic.updateItem(item);
    }

    @Override //未检测到相应对象时隐藏图形.这可能暂时发生在中间帧上.例如,对象暂时被阻挡在视野之外
    public void onMissing(Detector.Detections<Barcode> detectionResults) {
        mOverlay.remove(mGraphic);
    }

    @Override //当假定物品消失时调用,从叠加层中删除图形注释
    public void onDone() {
        mOverlay.remove(mGraphic);
    }
}

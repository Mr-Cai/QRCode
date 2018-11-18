package qr.code;

import android.content.Context;

import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.barcode.Barcode;

import qr.code.camera.GraphicOverlay;

//用于创建跟踪器和相关图形并与新条形码关联的工厂类,多处理器使用此工厂根据需要为每个创建条形码跟踪器
class BarcodeTrackerFactory implements MultiProcessor.Factory<Barcode> {
    private GraphicOverlay<BarcodeGraphic> mGraphicOverlay;
    private Context mContext;

    BarcodeTrackerFactory(GraphicOverlay<BarcodeGraphic> mGraphicOverlay, Context mContext) {
        this.mGraphicOverlay = mGraphicOverlay;
        this.mContext = mContext;
    }

    @Override
    public Tracker<Barcode> create(Barcode barcode) {
        BarcodeGraphic graphic = new BarcodeGraphic(mGraphicOverlay);
        return new BarcodeGraphicTracker(mGraphicOverlay, graphic, mContext);
    }

}


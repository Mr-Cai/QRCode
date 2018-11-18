package qr.code

import android.content.Context

import com.google.android.gms.vision.MultiProcessor
import com.google.android.gms.vision.Tracker
import com.google.android.gms.vision.barcode.Barcode

import qr.code.camera.GraphicOverlay

//用于创建跟踪器和相关图形并与新条形码关联的工厂类,多处理器使用此工厂根据需要为每个创建条形码跟踪器
internal class BarcodeTrackerFactory(private val mGraphicOverlay: GraphicOverlay<BarcodeGraphic>, private val mContext: Context) : MultiProcessor.Factory<Barcode> {

    override fun create(barcode: Barcode): Tracker<Barcode> {
        val graphic = BarcodeGraphic(mGraphicOverlay)
        return BarcodeGraphicTracker(mGraphicOverlay, graphic, mContext)
    }

}


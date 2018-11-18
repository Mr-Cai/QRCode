package qr.code.camera

import android.content.Context
import androidx.annotation.UiThread
import com.google.android.gms.vision.Detector
import com.google.android.gms.vision.Tracker
import com.google.android.gms.vision.barcode.Barcode

//通用跟踪器,用于跟踪或读取条形码(可复用)并接收新检测到的项目
class BarcodeGraphicTracker(private val mOverlay: GraphicOverlay<BarcodeGraphic>,
                            private val mGraphic: BarcodeGraphic,
                            context: Context) : Tracker<Barcode>() {
    private var mBarcodeUpdateListener: BarcodeUpdateListener? = null

    //通过在检测条码上实现更新接口方法,消耗从活动或碎片级别检测到的子项实例
    interface BarcodeUpdateListener {
        @UiThread
        fun onBarcodeDetected(barcode: Barcode?)
    }

    init {
        if (context is BarcodeUpdateListener) {
            this.mBarcodeUpdateListener = context
        } else {
            throw RuntimeException("Hosting activity must implement BarcodeUpdateListener")
        }
    }

    override fun onNewItem(id: Int, item: Barcode?) { //开始跟踪项目图层中的检测到的子项实例
        mGraphic.setId(id)
        mBarcodeUpdateListener!!.onBarcodeDetected(item)
    }

    override//更新叠加层中子项的位置特征
    fun onUpdate(detectionResults: Detector.Detections<Barcode>?, item: Barcode?) {
        mOverlay.add(mGraphic)
        mGraphic.updateItem(item!!)
    }

    override//未检测到相应对象时隐藏图形.这可能暂时发生在中间帧上.例如,对象暂时被阻挡在视野之外
    fun onMissing(detectionResults: Detector.Detections<Barcode>?) {
        mOverlay.remove(mGraphic)
    }

    override//当假定物品消失时调用,从叠加层中删除图形注释
    fun onDone() {
        mOverlay.remove(mGraphic)
    }
}

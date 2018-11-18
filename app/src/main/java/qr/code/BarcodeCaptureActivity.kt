package qr.code

import android.Manifest.permission.CAMERA
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.hardware.Camera
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.checkSelfPermission
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.vision.MultiProcessor
import com.google.android.gms.vision.barcode.Barcode
import com.google.android.gms.vision.barcode.BarcodeDetector
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.barcode_capture.*
import qr.code.camera.CameraSource
import qr.code.camera.GraphicOverlay
import java.io.IOException

class BarcodeCaptureActivity : AppCompatActivity(), BarcodeGraphicTracker.BarcodeUpdateListener {
    private var mCameraSource: CameraSource? = null
    private var graphicOverlay: GraphicOverlay<BarcodeGraphic>? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null  //多指缩放手势辅助对象
    private var gestureDetector: GestureDetector? = null //用于手势检测的辅助对象
    public override fun onCreate(icicle: Bundle?) { //初始化界面并创建检测器管道
        super.onCreate(icicle)
        setContentView(R.layout.barcode_capture)
        graphicOverlay = findViewById(R.id.graphicOverlay)
        val autoFocus = intent.getBooleanExtra(AutoFocus, false)  //从用于启动活动的意图中读取参数
        val useFlash = intent.getBooleanExtra(UseFlash, false)
        if (checkSelfPermission(this, CAMERA) == PERMISSION_GRANTED) { //动态添加相机权限
            createCameraSource(autoFocus, useFlash)
        } else {
            requestCameraPermission()
        }
        gestureDetector = GestureDetector(this, CaptureGestureListener())
        scaleGestureDetector = ScaleGestureDetector(this, ScaleListener())
        Snackbar.make(graphicOverlay!!, "双指缩放镜头", Snackbar.LENGTH_LONG).show()
    }

    private fun requestCameraPermission() { //请求打开相机的权限
        val permissions = arrayOf(CAMERA)
        if (!ActivityCompat.shouldShowRequestPermissionRationale(this, CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM)
            return
        }
        val thisActivity = this
        val listener = View.OnClickListener { ActivityCompat.requestPermissions(thisActivity, permissions, RC_HANDLE_CAMERA_PERM) }
        findViewById<View>(R.id.topLayout).setOnClickListener(listener)
        Snackbar.make(graphicOverlay!!, R.string.permission_camera_rationale,
                Snackbar.LENGTH_INDEFINITE).setAction(R.string.ok, listener).show()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = scaleGestureDetector!!.onTouchEvent(e)
        val c = gestureDetector!!.onTouchEvent(e)
        return b || c || super.onTouchEvent(e)
    }

    // 创建并启动相机. 请注意,相比之下,这使用更高的分辨率其他检测示例使条形码检测器能够检测小条形码在远距离
    private fun createCameraSource(autoFocus: Boolean, useFlash: Boolean) {
        val context = applicationContext
        /*
         * 创建条形码检测器以跟踪条形码.设置关联的多处理器实例以接收条形码检测结果,
         * 跟踪条形码,并维护屏幕上每个条形码的图形.多处理器使用工厂为每个条形码创建单独的跟踪器实例。
         * */
        val barcodeDetector = BarcodeDetector.Builder(context).build()
        val barcodeFactory = BarcodeTrackerFactory(graphicOverlay!!, this)
        barcodeDetector.setProcessor(
                MultiProcessor.Builder(barcodeFactory).build())
        //创建并启动相机.请注意,与其他检测示例相比,这使用更高的分辨率,以使条形码检测器能够长距离检测小条形码
        var builder: CameraSource.Builder = CameraSource.Builder(applicationContext, barcodeDetector)
                .setFacing(CameraSource.CAMERA_FACING_BACK)
                .setRequestedPreviewSize(1600, 1024)
                .setRequestedFps(15.0f)

        //确保自动对焦是一个可用选项
        builder = builder.setFocusMode(if (autoFocus) Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE else "")
        mCameraSource = builder.setFlashMode(if (useFlash) Camera.Parameters.FLASH_MODE_TORCH else "")
                .build()
    }

    override fun onResume() {
        super.onResume()
        startCameraSource() //重启相机
    }

    override fun onPause() { //停用相机
        super.onPause()
        if (cameraSourcePreview != null) cameraSourcePreview!!.stop()
    }

    override fun onDestroy() { //释放与摄像机源,相关检测器和处理管道的其余部分相关的资源.
        super.onDestroy()
        if (cameraSourcePreview != null) {
            cameraSourcePreview!!.release()
        }
    }

    override//回调动态权限结果集
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                   grantResults: IntArray) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }

        if (grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED) { //授权后创建相机源
            val autoFocus = intent.getBooleanExtra(AutoFocus, false)
            val useFlash = intent.getBooleanExtra(UseFlash, false)
            createCameraSource(autoFocus, useFlash)
            return
        }
        val listener = DialogInterface.OnClickListener { dialog, id -> finish() }
        val builder = AlertDialog.Builder(this)
        builder.setTitle("多重检测器示例")
                .setMessage(R.string.no_camera_permission)
                .setPositiveButton(R.string.ok, listener)
                .show()
    }

    @Throws(SecurityException::class)
    private fun startCameraSource() { //启动或重新启动相机源
        val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                applicationContext)
        if (code != ConnectionResult.SUCCESS) {
            val dlg = GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS)
            dlg.show()
        }
        if (mCameraSource != null) {
            try {
                cameraSourcePreview!!.start(mCameraSource!!, graphicOverlay!!)
            } catch (e: IOException) {
                mCameraSource!!.release()
                mCameraSource = null
            }

        }
    }

    /**
     * 将条形码结果点击跳转到调用的活动
     * @param rawX - 原本点击的位置X
     * @param rawY - 原本点击的位置Y
     * @return 如果活动结束则为真
     */
    private fun onTap(rawX: Float, rawY: Float): Boolean {
        // 在预览帧坐标中查找点击点
        val location = IntArray(2)
        graphicOverlay!!.getLocationOnScreen(location)
        val x = (rawX - location[0]) / graphicOverlay!!.widthScaleFactor
        val y = (rawY - location[1]) / graphicOverlay!!.heightScaleFactor
        var best: Barcode? = null  //找到中心最靠近点击的条形码
        var bestDistance = java.lang.Float.MAX_VALUE
        for (graphic in graphicOverlay!!.graphics) {
            val barcode = graphic.barcode
            if (barcode!!.boundingBox.contains(x.toInt(), y.toInt())) {
                //确定点中了,无需继续寻找.
                best = barcode
                break
            }
            val dx = x - barcode.boundingBox.centerX()
            val dy = y - barcode.boundingBox.centerY()
            val distance = dx * dx + dy * dy  // actually squared distance
            if (distance < bestDistance) {
                best = barcode
                bestDistance = distance
            }
        }

        if (best != null) {
            val data = Intent()
            data.putExtra(BarcodeObject, best)
            setResult(CommonStatusCodes.SUCCESS, data)
            finish()
            return true
        }
        return false
    }

    private inner class CaptureGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return onTap(e.rawX, e.rawY) || super.onSingleTapConfirmed(e)
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.OnScaleGestureListener {
        /**
         * 响应正在进行的手势的缩放事件,由指针报告示意
         * @param detector 报告事件的检测器 - 使用它来检索有关事件状态的扩展信息。
         * @return 检测器是否应将此事件视为已处理.如果未处理事件, 检测器将继续累积运动直到处理事件.
         * 例如,如果应用程序仅在更改大于0.01时想要更新缩放因子,则此功能非常有用.
         */
        override fun onScale(detector: ScaleGestureDetector) = false

        override fun onScaleBegin(detector: ScaleGestureDetector) = true //开始响应缩放手势
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            mCameraSource!!.doZoom(detector.scaleFactor)
        }
    }

    override fun onBarcodeDetected(barcode: Barcode?) = Unit

    companion object {
        private const val RC_HANDLE_GMS = 9001  //处理更新
        private const val RC_HANDLE_CAMERA_PERM = 2 //权限请求码必须<256
        const val AutoFocus = "AutoFocus"
        const val UseFlash = "UseFlash"
        const val BarcodeObject = "Barcode"
    }
}

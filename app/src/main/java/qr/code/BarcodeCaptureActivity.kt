package qr.code

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.vision.MultiProcessor
import com.google.android.gms.vision.barcode.Barcode
import com.google.android.gms.vision.barcode.BarcodeDetector
import com.google.android.material.snackbar.Snackbar
import qr.code.camera.CameraSource
import qr.code.camera.CameraSourcePreview
import qr.code.camera.GraphicOverlay
import java.io.IOException

class BarcodeCaptureActivity : AppCompatActivity(), BarcodeGraphicTracker.BarcodeUpdateListener {
    private var mCameraSource: CameraSource? = null
    private var mPreview: CameraSourcePreview? = null
    private var mGraphicOverlay: GraphicOverlay<BarcodeGraphic>? = null
    //用于检测双指缩放的辅助对象。
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null

    public override fun onCreate(icicle: Bundle?) { //初始化界面并创建检测器管道
        super.onCreate(icicle)
        setContentView(R.layout.barcode_capture)
        mPreview = findViewById(R.id.preview)
        mGraphicOverlay = findViewById(R.id.graphicOverlay)
        //从用于启动活动的意图中读取参数
        val autoFocus = intent.getBooleanExtra(AutoFocus, false)
        val useFlash = intent.getBooleanExtra(UseFlash, false)
        //动态添加相机权限
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            createCameraSource(autoFocus, useFlash)
        } else {
            requestCameraPermission()
        }
        gestureDetector = GestureDetector(this, CaptureGestureListener())
        scaleGestureDetector = ScaleGestureDetector(this, ScaleListener())
        Snackbar.make(mGraphicOverlay!!, "双指缩放镜头", Snackbar.LENGTH_LONG).show()
    }

    private fun requestCameraPermission() { //处理请求相机的权限,这包括显示消息,说明为什么需要权限然后发送请求.
        Log.w(TAG, "Camera permission is not granted. Requesting permission")
        val permissions = arrayOf(Manifest.permission.CAMERA)
        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM)
            return
        }
        val thisActivity = this
        val listener = View.OnClickListener { ActivityCompat.requestPermissions(thisActivity, permissions, RC_HANDLE_CAMERA_PERM) }
        findViewById<View>(R.id.topLayout).setOnClickListener(listener)
        Snackbar.make(mGraphicOverlay!!, R.string.permission_camera_rationale,
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
        val barcodeFactory = BarcodeTrackerFactory(mGraphicOverlay!!, this)
        barcodeDetector.setProcessor(
                MultiProcessor.Builder(barcodeFactory).build())

        if (!barcodeDetector.isOperational) { //可用于检查所需的本机库当前是否可用,一旦库在设备上下载完成,检测器将自动运行
            //首次运行,GMS会自动下载扫码库,若未成功将不会检测到任何条形码和或面
            Log.w(TAG, "检测器依赖尚不可用")
            //如果存储空间不足,则不会下载本机库,因此检测将无法运行
            val lowStorageFilter = IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW)
            val hasLowStorage = registerReceiver(null, lowStorageFilter) != null
            if (hasLowStorage) {
                Toast.makeText(this, R.string.low_storage_error, Toast.LENGTH_LONG).show()
                Log.w(TAG, getString(R.string.low_storage_error))
            }
        }
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

    override fun onPause() {
        super.onPause()
        if (mPreview != null) {
            mPreview!!.stop() //停用相机
        }
    }

    override fun onDestroy() { //释放与摄像机源,相关检测器和处理管道的其余部分相关的资源.
        super.onDestroy()
        if (mPreview != null) {
            mPreview!!.release()
        }
    }

    override//回调动态权限结果集
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                   grantResults: IntArray) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: $requestCode")
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission granted - initialize the camera source")
            //授予权限后创建相机源
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
                mPreview!!.start(mCameraSource!!, mGraphicOverlay!!)
            } catch (e: IOException) {
                Log.e(TAG, "无法启动相机资源", e)
                mCameraSource!!.release()
                mCameraSource = null
            }

        }
    }

    /**
     * 将条形码结果点击跳转到调用的活动
     *
     * @param rawX - 原本点击的位置X
     * @param rawY - 原本点击的位置Y
     * @return 如果活动结束则为真
     */
    private fun onTap(rawX: Float, rawY: Float): Boolean {
        // 在预览帧坐标中查找点击点
        val location = IntArray(2)
        mGraphicOverlay!!.getLocationOnScreen(location)
        val x = (rawX - location[0]) / mGraphicOverlay!!.widthScaleFactor
        val y = (rawY - location[1]) / mGraphicOverlay!!.heightScaleFactor
        var best: Barcode? = null  //找到中心最靠近点击的条形码
        var bestDistance = java.lang.Float.MAX_VALUE
        for (graphic in mGraphicOverlay!!.graphics) {
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
         *
         * @param detector 报告事件的检测器 - 使用它来检索有关事件状态的扩展信息。
         * @return 检测器是否应将此事件视为已处理.如果未处理事件, 检测器将继续累积运动直到处理事件.
         * 例如,如果应用程序仅在更改大于0.01时想要更新缩放因子,则此功能非常有用.
         */
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return false
        }

        override//开始响应缩放手势,跟据新指针报告.
        fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            mCameraSource!!.doZoom(detector.scaleFactor)
        }
    }

    override fun onBarcodeDetected(barcode: Barcode?) {

    }

    companion object {
        /**
         * 多跟踪器应用的活动.
         * 此应用程序检测条形码并使用后置摄像头显示值.
         * 在检测期间,绘制覆盖图形以指示每个条形码的位置,大小和ID
         */
        private val TAG = "Barcode-reader"
        private val RC_HANDLE_GMS = 9001  //意图请求码,用于在需要时处理更新播放服务
        private val RC_HANDLE_CAMERA_PERM = 2//权限请求码必须<256
        // 常量用于传递意图中的额外数据
        val AutoFocus = "AutoFocus"
        val UseFlash = "UseFlash"
        val BarcodeObject = "Barcode"
    }
}

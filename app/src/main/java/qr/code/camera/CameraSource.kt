package qr.code.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager

import com.google.android.gms.common.images.Size
import com.google.android.gms.vision.Detector
import com.google.android.gms.vision.Frame

import java.io.IOException
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.HashMap

class CameraSource {

    private var mContext: Context? = null
    private val mCameraLock = Any()
    private var mCamera: Camera? = null // 相机锁保护
    internal//返回当前摄像头
    var cameraFacing = CAMERA_FACING_BACK
        private set
    private var mRotation: Int = 0 //旋转设备，从而从设备捕获相关的预览图像
    internal//返回底层摄像头当前正在使用的预览大小
    var previewSize: Size? = null
        private set
    //调用方可能会请求这些值。由于硬件限制，我们可能需要选择关闭，但这些值并不完全相同
    private var mRequestedFps = 30.0f
    private var mRequestedPreviewWidth = 1024
    private var mRequestedPreviewHeight = 768
    private var mFocusMode: String? = null
    private var mFlashMode: String? = null
    //当相机可以使用帧时，专用线程和相关的可运行用于使用帧调用检测器。
    private var mProcessingThread: Thread? = null
    private var mFrameProcessor: FrameProcessingRunnable? = null
    /**
     * 映射以在从摄像机接收的字节数组及其关联的字节缓冲区之间进行转换。
     * 我们在内部使用字节缓冲区，因为这是以后调用本机代码的一种更有效的方法（避免潜在的复制）。
     */
    private val mBytesToByteBuffer = HashMap<ByteArray, ByteBuffer>()

    @Retention(RetentionPolicy.SOURCE)
    private annotation class FocusMode

    @Retention(RetentionPolicy.SOURCE)
    private annotation class FlashMode

    class Builder// 使用提供的上下文和检测器创建相机源构建器. 启动摄像机信号源后，摄像机预览图像将流式传输到相关的检测器。
    (context: Context?, //生成器用于配置和建立相关的摄像机源。
     private val mDetector: Detector<*>?) {
        private val mCameraSource = CameraSource()

        init {
            if (context == null) {
                throw IllegalArgumentException("No context supplied.")
            }
            if (mDetector == null) {
                throw IllegalArgumentException("No detector supplied.")
            }
            mCameraSource.mContext = context
        }

        fun setRequestedFps(fps: Float): Builder { //每秒帧数:30
            if (fps <= 0) {
                throw IllegalArgumentException("Invalid fps: $fps")
            }
            mCameraSource.mRequestedFps = fps
            return this
        }

        fun setFocusMode(@FocusMode mode: String): Builder {
            mCameraSource.mFocusMode = mode
            return this
        }

        fun setFlashMode(@FlashMode mode: String): Builder {
            mCameraSource.mFlashMode = mode
            return this
        }

        fun setRequestedPreviewSize(width: Int, height: Int): Builder { // 限定预览尺寸
            val max = 1000000
            if (width <= 0 || width > max || height <= 0 || height > max) {
                throw IllegalArgumentException("Invalid preview size: " + width + "x" + height)
            }
            mCameraSource.mRequestedPreviewWidth = width
            mCameraSource.mRequestedPreviewHeight = height
            return this
        }

        fun setFacing(facing: Int): Builder { //设置使用的摄像头(默认后置)
            if (facing != CAMERA_FACING_BACK && facing != CAMERA_FACING_FRONT) {
                throw IllegalArgumentException("Invalid camera: $facing")
            }
            mCameraSource.cameraFacing = facing
            return this
        }

        fun build(): CameraSource { //创建相机资源实例
            mCameraSource.mFrameProcessor = mCameraSource.FrameProcessingRunnable(mDetector)
            return mCameraSource
        }
    }


    fun release() { //停止相机并释放相机和底层探测器的资源
        synchronized(mCameraLock) {
            stop()
            mFrameProcessor!!.release()
        }
    }

    // 打开相机并开始将预览帧发送到底层检测器,提供的表面固定器用于预览,因此可以向用户显示帧.
     fun start(surfaceHolder: SurfaceHolder) {
        synchronized(mCameraLock) {
            if (mCamera != null) return
            mCamera = createCamera()
            mCamera!!.setPreviewDisplay(surfaceHolder)
            mCamera!!.startPreview()
            mProcessingThread = Thread(mFrameProcessor)
            mFrameProcessor!!.setActive(true)
            mProcessingThread!!.start()
        }
    }

     fun stop() { //关闭相机并停止向底层帧检测器发送帧。
        synchronized(mCameraLock) {
            mFrameProcessor!!.setActive(false)
            if (mProcessingThread != null) {
                try { //等待线程完成确保不会同时执行多个线程（即:防止点击太快)
                    mProcessingThread!!.join()
                } catch (e: InterruptedException) {
                    Log.d(TAG, "帧处理线程在释放时被中断")
                }

                mProcessingThread = null
            }
            mBytesToByteBuffer.clear() //清除缓冲区以防止异常

            if (mCamera != null) {
                mCamera!!.stopPreview()
                mCamera!!.setPreviewCallbackWithBuffer(null)
                mCamera!!.release()
                mCamera = null
            }
        }
    }

    fun doZoom(scale: Float) {
        synchronized(mCameraLock) {
            if (mCamera == null) {
                return
            }
            var currentZoom: Int
            val maxZoom: Int
            val parameters = mCamera!!.parameters
            if (!parameters.isZoomSupported) {
                Log.w(TAG, "Zoom is not supported on this device")
                return
            }
            maxZoom = parameters.maxZoom

            currentZoom = parameters.zoom + 1
            val newZoom: Float
            if (scale > 1) {
                newZoom = currentZoom + scale * (maxZoom / 10)
            } else {
                newZoom = currentZoom * scale
            }
            currentZoom = Math.round(newZoom) - 1
            if (currentZoom < 0) {
                currentZoom = 0
            } else if (currentZoom > maxZoom) {
                currentZoom = maxZoom
            }
            parameters.zoom = currentZoom
            mCamera!!.parameters = parameters
        }
    }

    @SuppressLint("InlinedApi")
    private fun createCamera(): Camera { //打开相机并应用用户设置
        val requestedCameraId = getIdForRequestedCamera(cameraFacing)
        if (requestedCameraId == -1) {
            throw RuntimeException("Could not find requested camera.")
        }
        val camera = Camera.open(requestedCameraId)
        val sizePair = selectSizePair(camera, mRequestedPreviewWidth, mRequestedPreviewHeight)
                ?: throw RuntimeException("Could not find suitable preview size.")
        val pictureSize = sizePair.pictureSize()
        previewSize = sizePair.previewSize()
        val previewFpsRange = selectPreviewFpsRange(camera, mRequestedFps)
                ?: throw RuntimeException("Could not find suitable preview frames per second range.")
        val parameters = camera.parameters
        if (pictureSize != null) {
            parameters.setPictureSize(pictureSize.width, pictureSize.height)
        }
        parameters.setPreviewSize(previewSize!!.width, previewSize!!.height)
        parameters.setPreviewFpsRange(
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX])
        parameters.previewFormat = ImageFormat.NV21
        setRotation(camera, parameters, requestedCameraId)
        if (mFocusMode != null) {
            if (parameters.supportedFocusModes.contains(
                            mFocusMode)) {
                parameters.focusMode = mFocusMode
            } else {
                Log.i(TAG, "Camera focus mode: $mFocusMode is not supported on this device.")
            }
        }
        mFocusMode = parameters.focusMode
        if (mFlashMode != null) {
            if (parameters.supportedFlashModes != null) {
                if (parameters.supportedFlashModes.contains(
                                mFlashMode)) {
                    parameters.flashMode = mFlashMode
                } else {
                    Log.i(TAG, "Camera flash mode: $mFlashMode is not supported on this device.")
                }
            }
        }
        mFlashMode = parameters.flashMode //设置mFlashMode在一组参数
        camera.parameters = parameters
        // 使用相机需要四个帧缓冲器:当前帧、下一帧、两个预测补帧
        camera.setPreviewCallbackWithBuffer(CameraPreviewCallback())
        camera.addCallbackBuffer(createPreviewBuffer(previewSize!!))
        camera.addCallbackBuffer(createPreviewBuffer(previewSize!!))
        camera.addCallbackBuffer(createPreviewBuffer(previewSize!!))
        camera.addCallbackBuffer(createPreviewBuffer(previewSize!!))
        return camera
    }

    /*
     * 存储预览大小和相应的相同宽高比图片大小。 为避免某些设备上的预览图像失真，
     * 必须将图片大小设置为与预览尺寸相同的宽高比，否则预览可能会失真。
     * 如果图片大小为空，则没有与预览大小具有相同宽高比的图片大小
     */
    private class SizePair internal constructor(previewSize: Camera.Size,
                                                pictureSize: Camera.Size?) {
        private val mPreview: Size
        private var mPicture: Size? = null

        init {
            mPreview = Size(previewSize.width, previewSize.height)
            if (pictureSize != null) {
                mPicture = Size(pictureSize.width, pictureSize.height)
            }
        }

        internal fun previewSize(): Size {
            return mPreview
        }

        internal fun pictureSize(): Size? {
            return mPicture
        }
    }

    private fun selectPreviewFpsRange(camera: Camera, desiredPreviewFps: Float): IntArray? {// 选择最合适的帧数
        val desiredPreviewFpsScaled = (desiredPreviewFps * 1000.0f).toInt()
        //选择最佳范围的方法是最小化期望值与范围的上限和下限之间的差的总和, 这可以选择期望值在其之外的范围，
        // 但这通常是优选的。 例如，如果期望的帧速率是29.97，则范围（30,30）可能比范围（15,30）更合乎需要。
        var selectedFpsRange: IntArray? = null
        var minDiff = Integer.MAX_VALUE
        val previewFpsRangeList = camera.parameters.supportedPreviewFpsRange
        for (range in previewFpsRangeList) {
            val deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX]
            val deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]
            val diff = Math.abs(deltaMin) + Math.abs(deltaMax)
            if (diff < minDiff) {
                selectedFpsRange = range
                minDiff = diff
            }
        }
        return selectedFpsRange
    }

    //计算摄像头旋转
    private fun setRotation(camera: Camera, parameters: Camera.Parameters, cameraId: Int) {
        val windowManager = mContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var degrees = 0
        val rotation = windowManager.defaultDisplay.rotation
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
            else -> Log.e(TAG, "Bad rotation value: $rotation")
        }
        val cameraInfo = CameraInfo()
        Camera.getCameraInfo(cameraId, cameraInfo)
        val angle: Int
        val displayAngle: Int
        if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
            angle = (cameraInfo.orientation + degrees) % 360
            displayAngle = (360 - angle) % 360 //补帧
        } else {
            angle = (cameraInfo.orientation - degrees + 360) % 360
            displayAngle = angle
        }
        mRotation = angle / 90
        camera.setDisplayOrientation(displayAngle)
        parameters.setRotation(angle)
    }

    private fun createPreviewBuffer(previewSize: Size): ByteArray {
        //为摄像头预览回调创建一个缓冲区,缓冲区大小取决于相机预览大小和相机图像格式,返回适合当前相机的新预览缓冲区
        val bitsPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21)
        val sizeInBits = (previewSize.height * previewSize.width * bitsPerPixel).toLong()
        val bufferSize = Math.ceil(sizeInBits / 8.0).toInt() + 1
        //以这种方式创建字节数组并将其包装起来,而不是allocate()这应该保证将有一个数组可以使用
        val byteArray = ByteArray(bufferSize)
        val buffer = ByteBuffer.wrap(byteArray)
        if (!buffer.hasArray() || buffer.array() != byteArray) {
            //我认为这不会发生,但如果确实如此. 那么我们以后就不会将预览内容传递给底层探测器。
            throw IllegalStateException("Failed to create valid buffer for camera source.")
        }
        mBytesToByteBuffer[byteArray] = buffer
        return byteArray
    }

    private inner class CameraPreviewCallback : Camera.PreviewCallback { //出现新的预览帧时调用
        override fun onPreviewFrame(data: ByteArray, camera: Camera) {
            mFrameProcessor!!.setNextFrame(data, camera)
        }
    }

    private inner class FrameProcessingRunnable internal constructor(//控制底层接收器访问
            private var mDetector: Detector<*>?) : Runnable {
        private val mStartTimeMillis = SystemClock.elapsedRealtime()
        private val mLock = Object() //锁对象保护以下所有成员
        private var mActive = true

        // 这些挂起的变量保持与等待处理的新帧相关联的状态.
        private var mPendingTimeMillis: Long = 0
        private var mPendingFrameId = 0
        private var mPendingFrameData: ByteBuffer? = null

        internal fun release() { //释放底层接收器.
            mDetector!!.release()
            mDetector = null
        }

        internal fun setActive(active: Boolean) { //标记激活状态,发信号通知阻塞线程继续.
            synchronized(mLock) {
                mActive = active
                mLock.notifyAll()
            }
        }

        internal fun setNextFrame(data: ByteArray, camera: Camera) { //将缓冲区添加回相机,并保留待处理引用
            synchronized(mLock) {
                if (mPendingFrameData != null) {
                    camera.addCallbackBuffer(mPendingFrameData!!.array())
                    mPendingFrameData = null
                }
                if (!mBytesToByteBuffer.containsKey(data)) {
                    Log.d(TAG, "跳帧！找不到来自相机的与图像相关联的字节缓冲区数据。")
                    return
                }
                //保留了时间戳和帧ID,这将为以下代码提供接收帧的时序和防止中途丢帧
                mPendingTimeMillis = SystemClock.elapsedRealtime() - mStartTimeMillis
                mPendingFrameId++
                mPendingFrameData = mBytesToByteBuffer[data]
                mLock.notifyAll()  //如果处理器线程正在等待下一帧,则通知处理器线程
            }
        }

        /**
         * 只要处理线程处于活动状态，就会连续执行帧检测。 下一个待处理框架要么立即可用，要么尚未接收。
         * 一旦可用，我们将帧信息传输到局部变量并在该帧上运行检测。 它会立即循环回到下一帧而不会暂停。
         * 如果检测时间超过摄像机新帧之间的时间，则意味着此循环将在不等待帧的情况下运行，从而避免任何上下文切换或帧采集时间延迟。
         * 如果你发现这使用的CPU更多,那么你应该降低上面的FPS,以允许帧之间有空闲
         */
        override fun run() {
            var outputFrame: Frame
            var data: ByteBuffer
            while (true) {
                synchronized(mLock) {
                    while (mActive && mPendingFrameData == null) {
                        try { // 等待从相机接收到下一帧，因为我们没有它。
                            mLock.wait()
                        } catch (e: InterruptedException) {
                            Log.d(TAG, "Frame processing loop terminated.", e)
                            return
                        }

                    }
                    if (!mActive) { //此相机源停止或释放后退出循环
                        return
                    }
                    outputFrame = Frame.Builder().setImageData(
                            mPendingFrameData!!,
                            previewSize!!.width,
                            previewSize!!.height, ImageFormat.NV21)
                            .setId(mPendingFrameId)
                            .setTimestampMillis(mPendingTimeMillis)
                            .setRotation(mRotation)
                            .build()
                    data = mPendingFrameData!! //缓存待定帧数据
                    mPendingFrameData = null
                }
                // 下面的代码需要在同步之外运行，因为这将允许摄像机在我们对当前帧运行检测时添加待定帧。
                try {
                    mDetector!!.receiveFrame(outputFrame)
                } catch (t: Throwable) {
                    Log.e(TAG, "Exception thrown from receiver.", t)
                } finally {
                    mCamera!!.addCallbackBuffer(data.array())
                }
            }
        }
    }

    companion object {
        const val CAMERA_FACING_BACK = CameraInfo.CAMERA_FACING_BACK
        internal const val CAMERA_FACING_FRONT = CameraInfo.CAMERA_FACING_FRONT
        private const val TAG = "OpenCameraSource"
        private const val ASPECT_RATIO_TOLERANCE = 0.01f

        //获取由其面向的方向指定的摄像机的id,如果没有找到这样的相机，则返回-1。
        private fun getIdForRequestedCamera(facing: Int): Int {
            val cameraInfo = CameraInfo()
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, cameraInfo)
                if (cameraInfo.facing == facing) {
                    return i
                }
            }
            return -1
        }

        //给定所需的宽度和高度,选择最合适的预览和图片大小.
        private fun selectSizePair(camera: Camera, desiredWidth: Int, desiredHeight: Int): SizePair? {
            val validPreviewSizes = generateValidPreviewSizeList(camera)
            /*
          选择最佳尺寸的方法是最小化期望值与宽度和高度的实际值之间的差的总和。
          这当然不是选择最佳尺寸的唯一方法，
          但它在使用最近纵横比与使用最近像素区域之间提供了一个很好的权衡。
         * */
            var selectedPair: SizePair? = null
            var minDiff = Integer.MAX_VALUE
            for (sizePair in validPreviewSizes) {
                val size = sizePair.previewSize()
                val diff = Math.abs(size.width - desiredWidth) + Math.abs(size.height - desiredHeight)
                if (diff < minDiff) {
                    selectedPair = sizePair
                    minDiff = diff
                }
            }

            return selectedPair
        }

        /**
         * 生成可接受的预览大小列表。 如果没有相同宽高比的相应图片大小，则不接受预览尺寸。
         * 如果存在相同宽高比的相应图片尺寸，则图片尺寸与预览尺寸配对。
         * 这是必要的，因为即使我们不使用静态图片，静态图片大小也必须设置为与我们选择的预览尺寸相同的宽高比。
         * 否则，某些设备上的预览图像可能会失真。
         */
        private fun generateValidPreviewSizeList(camera: Camera): List<SizePair> {
            val parameters = camera.parameters
            val supportedPreviewSizes = parameters.supportedPreviewSizes
            val supportedPictureSizes = parameters.supportedPictureSizes
            val validPreviewSizes = ArrayList<SizePair>()
            for (previewSize in supportedPreviewSizes) {
                val previewAspectRatio = previewSize.width.toFloat() / previewSize.height.toFloat()
                // 通过按顺序循环显示图片大小,我们赞成更高的分辨率。我们选择最高分辨率以支持稍后拍摄完整的分辨率图像
                for (pictureSize in supportedPictureSizes) {
                    val pictureAspectRatio = pictureSize.width.toFloat() / pictureSize.height.toFloat()
                    if (Math.abs(previewAspectRatio - pictureAspectRatio) < ASPECT_RATIO_TOLERANCE) {
                        validPreviewSizes.add(SizePair(previewSize, pictureSize))
                        break
                    }
                }
            }

            if (validPreviewSizes.size == 0) { //默认使用预览尺寸大小
                Log.w(TAG, "No preview sizes have a corresponding same-aspect-ratio picture size")
                for (previewSize in supportedPreviewSizes) {
                    validPreviewSizes.add(SizePair(previewSize, null))
                }
            }
            return validPreviewSizes
        }
    }
}

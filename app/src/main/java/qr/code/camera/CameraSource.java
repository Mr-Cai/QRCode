
package qr.code.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.google.android.gms.common.images.Size;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Frame;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("ALL")
public class CameraSource {
    public static final int CAMERA_FACING_BACK = CameraInfo.CAMERA_FACING_BACK;
    public static final int CAMERA_FACING_FRONT = CameraInfo.CAMERA_FACING_FRONT;
    private static final String TAG = "OpenCameraSource";
    private static final float ASPECT_RATIO_TOLERANCE = 0.01f;

    @Retention(RetentionPolicy.SOURCE)
    private @interface FocusMode {
    }

    @Retention(RetentionPolicy.SOURCE)
    private @interface FlashMode {
    }

    private Context mContext;
    private final Object mCameraLock = new Object();
    private Camera mCamera; // 相机锁保护
    private int mFacing = CAMERA_FACING_BACK;
    private int mRotation; //旋转设备，从而从设备捕获相关的预览图像
    private Size mPreviewSize;
    //调用方可能会请求这些值。由于硬件限制，我们可能需要选择关闭，但这些值并不完全相同
    private float mRequestedFps = 30.0f;
    private int mRequestedPreviewWidth = 1024;
    private int mRequestedPreviewHeight = 768;
    private String mFocusMode = null;
    private String mFlashMode = null;
    //当相机可以使用帧时，专用线程和相关的可运行用于使用帧调用检测器。
    private Thread mProcessingThread;
    private FrameProcessingRunnable mFrameProcessor;
    /**
     * 映射以在从摄像机接收的字节数组及其关联的字节缓冲区之间进行转换。
     * 我们在内部使用字节缓冲区，因为这是以后调用本机代码的一种更有效的方法（避免潜在的复制）。
     */
    private Map<byte[], ByteBuffer> mBytesToByteBuffer = new HashMap<>();

    public static class Builder { //生成器用于配置和建立相关的摄像机源。
        private final Detector<?> mDetector;
        private CameraSource mCameraSource = new CameraSource();

        // 使用提供的上下文和检测器创建相机源构建器. 启动摄像机信号源后，摄像机预览图像将流式传输到相关的检测器。
        public Builder(Context context, Detector<?> detector) {
            if (context == null) {
                throw new IllegalArgumentException("No context supplied.");
            }
            if (detector == null) {
                throw new IllegalArgumentException("No detector supplied.");
            }
            mDetector = detector;
            mCameraSource.mContext = context;
        }

        public Builder setRequestedFps(float fps) { //每秒帧数:30
            if (fps <= 0) {
                throw new IllegalArgumentException("Invalid fps: " + fps);
            }
            mCameraSource.mRequestedFps = fps;
            return this;
        }

        public Builder setFocusMode(@FocusMode String mode) {
            mCameraSource.mFocusMode = mode;
            return this;
        }

        public Builder setFlashMode(@FlashMode String mode) {
            mCameraSource.mFlashMode = mode;
            return this;
        }

        public Builder setRequestedPreviewSize(int width, int height) { // 限定预览尺寸
            final int MAX = 1000000;
            if ((width <= 0) || (width > MAX) || (height <= 0) || (height > MAX)) {
                throw new IllegalArgumentException("Invalid preview size: " + width + "x" + height);
            }
            mCameraSource.mRequestedPreviewWidth = width;
            mCameraSource.mRequestedPreviewHeight = height;
            return this;
        }

        public Builder setFacing(int facing) { //设置使用的摄像头(默认后置)
            if ((facing != CAMERA_FACING_BACK) && (facing != CAMERA_FACING_FRONT)) {
                throw new IllegalArgumentException("Invalid camera: " + facing);
            }
            mCameraSource.mFacing = facing;
            return this;
        }

        public CameraSource build() { //创建相机资源实例
            mCameraSource.mFrameProcessor = mCameraSource.new FrameProcessingRunnable(mDetector);
            return mCameraSource;
        }
    }


    public void release() { //停止相机并释放相机和底层探测器的资源
        synchronized (mCameraLock) {
            stop();
            mFrameProcessor.release();
        }
    }

    // 打开相机并开始将预览帧发送到底层检测器,提供的表面固定器用于预览,因此可以向用户显示帧.
    void start(SurfaceHolder surfaceHolder) throws IOException {
        synchronized (mCameraLock) {
            if (mCamera != null) return;
            mCamera = createCamera();
            mCamera.setPreviewDisplay(surfaceHolder);
            mCamera.startPreview();
            mProcessingThread = new Thread(mFrameProcessor);
            mFrameProcessor.setActive(true);
            mProcessingThread.start();
        }
    }

    void stop() { //关闭相机并停止向底层帧检测器发送帧。
        synchronized (mCameraLock) {
            mFrameProcessor.setActive(false);
            if (mProcessingThread != null) {
                try { //等待线程完成确保不会同时执行多个线程（即:防止点击太快)
                    mProcessingThread.join();
                } catch (InterruptedException e) {
                    Log.d(TAG, "帧处理线程在释放时被中断");
                }
                mProcessingThread = null;
            }
            mBytesToByteBuffer.clear(); //清除缓冲区以防止异常

            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.setPreviewCallbackWithBuffer(null);
                mCamera.release();
                mCamera = null;
            }
        }
    }

    Size getPreviewSize() { //返回底层摄像头当前正在使用的预览大小
        return mPreviewSize;
    }

    int getCameraFacing() { //返回当前摄像头
        return mFacing;
    }

    public void doZoom(float scale) {
        synchronized (mCameraLock) {
            if (mCamera == null) {
                return;
            }
            int currentZoom;
            int maxZoom;
            Camera.Parameters parameters = mCamera.getParameters();
            if (!parameters.isZoomSupported()) {
                Log.w(TAG, "Zoom is not supported on this device");
                return;
            }
            maxZoom = parameters.getMaxZoom();

            currentZoom = parameters.getZoom() + 1;
            float newZoom;
            if (scale > 1) {
                newZoom = currentZoom + scale * (maxZoom / 10);
            } else {
                newZoom = currentZoom * scale;
            }
            currentZoom = Math.round(newZoom) - 1;
            if (currentZoom < 0) {
                currentZoom = 0;
            } else if (currentZoom > maxZoom) {
                currentZoom = maxZoom;
            }
            parameters.setZoom(currentZoom);
            mCamera.setParameters(parameters);
        }
    }

    @SuppressLint("InlinedApi")
    private Camera createCamera() { //打开相机并应用用户设置
        int requestedCameraId = getIdForRequestedCamera(mFacing);
        if (requestedCameraId == -1) {
            throw new RuntimeException("Could not find requested camera.");
        }
        Camera camera = Camera.open(requestedCameraId);
        SizePair sizePair = selectSizePair(camera, mRequestedPreviewWidth, mRequestedPreviewHeight);
        if (sizePair == null) {
            throw new RuntimeException("Could not find suitable preview size.");
        }
        Size pictureSize = sizePair.pictureSize();
        mPreviewSize = sizePair.previewSize();
        int[] previewFpsRange = selectPreviewFpsRange(camera, mRequestedFps);
        if (previewFpsRange == null) {
            throw new RuntimeException("Could not find suitable preview frames per second range.");
        }
        Camera.Parameters parameters = camera.getParameters();
        if (pictureSize != null) {
            parameters.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
        }
        parameters.setPreviewSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        parameters.setPreviewFpsRange(
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
        parameters.setPreviewFormat(ImageFormat.NV21);
        setRotation(camera, parameters, requestedCameraId);
        if (mFocusMode != null) {
            if (parameters.getSupportedFocusModes().contains(
                    mFocusMode)) {
                parameters.setFocusMode(mFocusMode);
            } else {
                Log.i(TAG, "Camera focus mode: " + mFocusMode + " is not supported on this device.");
            }
        }
        mFocusMode = parameters.getFocusMode();
        if (mFlashMode != null) {
            if (parameters.getSupportedFlashModes() != null) {
                if (parameters.getSupportedFlashModes().contains(
                        mFlashMode)) {
                    parameters.setFlashMode(mFlashMode);
                } else {
                    Log.i(TAG, "Camera flash mode: " + mFlashMode + " is not supported on this device.");
                }
            }
        }
        mFlashMode = parameters.getFlashMode(); //设置mFlashMode在一组参数
        camera.setParameters(parameters);
        // 使用相机需要四个帧缓冲器:当前帧、下一帧、两个预测补帧
        camera.setPreviewCallbackWithBuffer(new CameraPreviewCallback());
        camera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
        camera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
        camera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
        camera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
        return camera;
    }

    //获取由其面向的方向指定的摄像机的id,如果没有找到这样的相机，则返回-1。
    private static int getIdForRequestedCamera(int facing) {
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); ++i) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == facing) {
                return i;
            }
        }
        return -1;
    }

    //给定所需的宽度和高度,选择最合适的预览和图片大小.
    private static SizePair selectSizePair(Camera camera, int desiredWidth, int desiredHeight) {
        List<SizePair> validPreviewSizes = generateValidPreviewSizeList(camera);
        /*
          选择最佳尺寸的方法是最小化期望值与宽度和高度的实际值之间的差的总和。
          这当然不是选择最佳尺寸的唯一方法，
          但它在使用最近纵横比与使用最近像素区域之间提供了一个很好的权衡。
         * */
        SizePair selectedPair = null;
        int minDiff = Integer.MAX_VALUE;
        for (SizePair sizePair : validPreviewSizes) {
            Size size = sizePair.previewSize();
            int diff = Math.abs(size.getWidth() - desiredWidth) +
                    Math.abs(size.getHeight() - desiredHeight);
            if (diff < minDiff) {
                selectedPair = sizePair;
                minDiff = diff;
            }
        }

        return selectedPair;
    }

    /*
     * 存储预览大小和相应的相同宽高比图片大小。 为避免某些设备上的预览图像失真，
     * 必须将图片大小设置为与预览尺寸相同的宽高比，否则预览可能会失真。
     * 如果图片大小为空，则没有与预览大小具有相同宽高比的图片大小
     */
    private static class SizePair {
        private Size mPreview;
        private Size mPicture;

        SizePair(Camera.Size previewSize,
                 Camera.Size pictureSize) {
            mPreview = new Size(previewSize.width, previewSize.height);
            if (pictureSize != null) {
                mPicture = new Size(pictureSize.width, pictureSize.height);
            }
        }

        Size previewSize() {
            return mPreview;
        }

        Size pictureSize() {
            return mPicture;
        }
    }

    /**
     * 生成可接受的预览大小列表。 如果没有相同宽高比的相应图片大小，则不接受预览尺寸。
     * 如果存在相同宽高比的相应图片尺寸，则图片尺寸与预览尺寸配对。
     * 这是必要的，因为即使我们不使用静态图片，静态图片大小也必须设置为与我们选择的预览尺寸相同的宽高比。
     * 否则，某些设备上的预览图像可能会失真。
     */
    private static List<SizePair> generateValidPreviewSizeList(Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        List<Camera.Size> supportedPreviewSizes =
                parameters.getSupportedPreviewSizes();
        List<Camera.Size> supportedPictureSizes =
                parameters.getSupportedPictureSizes();
        List<SizePair> validPreviewSizes = new ArrayList<>();
        for (Camera.Size previewSize : supportedPreviewSizes) {
            float previewAspectRatio = (float) previewSize.width / (float) previewSize.height;
            // 通过按顺序循环显示图片大小,我们赞成更高的分辨率。我们选择最高分辨率以支持稍后拍摄完整的分辨率图像
            for (Camera.Size pictureSize : supportedPictureSizes) {
                float pictureAspectRatio = (float) pictureSize.width / (float) pictureSize.height;
                if (Math.abs(previewAspectRatio - pictureAspectRatio) < ASPECT_RATIO_TOLERANCE) {
                    validPreviewSizes.add(new SizePair(previewSize, pictureSize));
                    break;
                }
            }
        }

        if (validPreviewSizes.size() == 0) { //默认使用预览尺寸大小
            Log.w(TAG, "No preview sizes have a corresponding same-aspect-ratio picture size");
            for (Camera.Size previewSize : supportedPreviewSizes) {
                validPreviewSizes.add(new SizePair(previewSize, null));
            }
        }
        return validPreviewSizes;
    }

    private int[] selectPreviewFpsRange(Camera camera, float desiredPreviewFps) {// 选择最合适的帧数
        int desiredPreviewFpsScaled = (int) (desiredPreviewFps * 1000.0f);
        //选择最佳范围的方法是最小化期望值与范围的上限和下限之间的差的总和, 这可以选择期望值在其之外的范围，
        // 但这通常是优选的。 例如，如果期望的帧速率是29.97，则范围（30,30）可能比范围（15,30）更合乎需要。
        int[] selectedFpsRange = null;
        int minDiff = Integer.MAX_VALUE;
        List<int[]> previewFpsRangeList = camera.getParameters().getSupportedPreviewFpsRange();
        for (int[] range : previewFpsRangeList) {
            int deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            int diff = Math.abs(deltaMin) + Math.abs(deltaMax);
            if (diff < minDiff) {
                selectedFpsRange = range;
                minDiff = diff;
            }
        }
        return selectedFpsRange;
    }

    //计算摄像头旋转
    private void setRotation(Camera camera, Camera.Parameters parameters, int cameraId) {
        WindowManager windowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        int degrees = 0;
        int rotation = windowManager.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                Log.e(TAG, "Bad rotation value: " + rotation);
        }
        CameraInfo cameraInfo = new CameraInfo();
        Camera.getCameraInfo(cameraId, cameraInfo);
        int angle;
        int displayAngle;
        if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
            angle = (cameraInfo.orientation + degrees) % 360;
            displayAngle = (360 - angle) % 360; //补帧
        } else {
            angle = (cameraInfo.orientation - degrees + 360) % 360;
            displayAngle = angle;
        }
        mRotation = angle / 90;
        camera.setDisplayOrientation(displayAngle);
        parameters.setRotation(angle);
    }

    private byte[] createPreviewBuffer(Size previewSize) {
        //为摄像头预览回调创建一个缓冲区,缓冲区大小取决于相机预览大小和相机图像格式,返回适合当前相机的新预览缓冲区
        int bitsPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21);
        long sizeInBits = previewSize.getHeight() * previewSize.getWidth() * bitsPerPixel;
        int bufferSize = (int) Math.ceil(sizeInBits / 8.0d) + 1;
        //以这种方式创建字节数组并将其包装起来,而不是allocate()这应该保证将有一个数组可以使用
        byte[] byteArray = new byte[bufferSize];
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        if (!buffer.hasArray() || (buffer.array() != byteArray)) {
            //我认为这不会发生,但如果确实如此. 那么我们以后就不会将预览内容传递给底层探测器。
            throw new IllegalStateException("Failed to create valid buffer for camera source.");
        }
        mBytesToByteBuffer.put(byteArray, buffer);
        return byteArray;
    }

    private class CameraPreviewCallback implements Camera.PreviewCallback { //出现新的预览帧时调用
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            mFrameProcessor.setNextFrame(data, camera);
        }
    }

    private class FrameProcessingRunnable implements Runnable { //控制底层接收器访问
        private Detector<?> mDetector;
        private long mStartTimeMillis = SystemClock.elapsedRealtime();
        private final Object mLock = new Object(); //锁对象保护以下所有成员
        private boolean mActive = true;

        // 这些挂起的变量保持与等待处理的新帧相关联的状态.
        private long mPendingTimeMillis;
        private int mPendingFrameId = 0;
        private ByteBuffer mPendingFrameData;

        FrameProcessingRunnable(Detector<?> detector) {
            mDetector = detector;
        }

        void release() { //释放底层接收器.
            mDetector.release();
            mDetector = null;
        }

        void setActive(boolean active) { //标记激活状态,发信号通知阻塞线程继续.
            synchronized (mLock) {
                mActive = active;
                mLock.notifyAll();
            }
        }

        void setNextFrame(byte[] data, Camera camera) { //将缓冲区添加回相机,并保留待处理引用
            synchronized (mLock) {
                if (mPendingFrameData != null) {
                    camera.addCallbackBuffer(mPendingFrameData.array());
                    mPendingFrameData = null;
                }
                if (!mBytesToByteBuffer.containsKey(data)) {
                    Log.d(TAG, "跳帧！找不到来自相机的与图像相关联的字节缓冲区数据。");
                    return;
                }
                //保留了时间戳和帧ID,这将为以下代码提供接收帧的时序和防止中途丢帧
                mPendingTimeMillis = SystemClock.elapsedRealtime() - mStartTimeMillis;
                mPendingFrameId++;
                mPendingFrameData = mBytesToByteBuffer.get(data);
                mLock.notifyAll();  //如果处理器线程正在等待下一帧,则通知处理器线程
            }
        }

        /**
         * 只要处理线程处于活动状态，就会连续执行帧检测。 下一个待处理框架要么立即可用，要么尚未接收。
         * 一旦可用，我们将帧信息传输到局部变量并在该帧上运行检测。 它会立即循环回到下一帧而不会暂停。
         * 如果检测时间超过摄像机新帧之间的时间，则意味着此循环将在不等待帧的情况下运行，从而避免任何上下文切换或帧采集时间延迟。
         * 如果你发现这使用的CPU更多,那么你应该降低上面的FPS,以允许帧之间有空闲
         */
        @Override
        public void run() {
            Frame outputFrame;
            ByteBuffer data;
            while (true) {
                synchronized (mLock) {
                    while (mActive && (mPendingFrameData == null)) {
                        try { // 等待从相机接收到下一帧，因为我们没有它。
                            mLock.wait();
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Frame processing loop terminated.", e);
                            return;
                        }
                    }
                    if (!mActive) { //此相机源停止或释放后退出循环
                        return;
                    }
                    outputFrame = new Frame.Builder().setImageData(
                            mPendingFrameData,
                            mPreviewSize.getWidth(),
                            mPreviewSize.getHeight(), ImageFormat.NV21)
                            .setId(mPendingFrameId)
                            .setTimestampMillis(mPendingTimeMillis)
                            .setRotation(mRotation)
                            .build();
                    data = mPendingFrameData; //缓存待定帧数据
                    mPendingFrameData = null;
                }
                // 下面的代码需要在同步之外运行，因为这将允许摄像机在我们对当前帧运行检测时添加待定帧。
                try {
                    mDetector.receiveFrame(outputFrame);
                } catch (Throwable t) {
                    Log.e(TAG, "Exception thrown from receiver.", t);
                } finally {
                    mCamera.addCallbackBuffer(data.array());
                }
            }
        }
    }
}

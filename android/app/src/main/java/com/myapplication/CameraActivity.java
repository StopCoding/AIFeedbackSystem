package com.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class CameraActivity extends AppCompatActivity {

    private final static String TAG = "CameraActivity";

    //在关闭摄像机之前阻止应用程序退出的{链接信号量}。
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private TextureView textureView;
    private CameraManager mCameraManager;
    private String mCameraID;
    private ImageReader mImageRader;
    private CameraCaptureSession mCameraCaptureSession;
    private CameraDevice mCameraDevice;
    private ImageView imageView;
    private Button button;

    private HandlerThread mHandlerThread;
    private Handler childHandler;

    private CaptureRequest.Builder previewBuilder;
    private CaptureRequest.Builder pictureBuilder;

    ///为了使照片竖直显示
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        if (ActivityCompat.checkSelfPermission(CameraActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 100);
        }

        button = (Button) findViewById(R.id.take);
        imageView = (ImageView) findViewById(R.id.iv_show_camera);
        textureView = (TextureView) findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // 检查相机权限
                if (ActivityCompat.checkSelfPermission(CameraActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(CameraActivity.this, "没有照相机权限", Toast.LENGTH_SHORT);
                    return ;
                }

                if (button.getText().equals("拍照")) {
                    takePicture();
                    button.setText("返回");
                } else {
                    button.setText("拍照");
                    imageView.setVisibility(View.GONE);
                    textureView.setVisibility(View.VISIBLE);
                }
            }
        });

    }

    TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private void openCamera() {
        if (mHandlerThread == null) {
            mHandlerThread = new HandlerThread("Camera2");
            mHandlerThread.start();
            childHandler = new Handler(mHandlerThread.getLooper());
        }

        if (mImageRader == null) {
            mImageRader = ImageReader.newInstance(1080, 1920, ImageFormat.JPEG ,3);
            mImageRader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        final Bitmap bitmap = imageToBitmap(image);

                        if (bitmap != null) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    textureView.setVisibility(View.GONE);
                                    imageView.setVisibility(View.VISIBLE);
                                    imageView.setImageBitmap(bitmap);
                                }
                            });
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                            image = null;
                        }
                    }
                }
            }, childHandler);
        }

        if (mCameraManager == null) {
            mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        }

        String cameraIds[] = {};
        try {
            cameraIds = mCameraManager.getCameraIdList();
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cam access exception getting IDs", e);
        }
        if (cameraIds.length < 1) {
            Log.e(TAG, "No Cameras found");
        }

        mCameraID = cameraIds[1];
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "没有照相机权限");
                return ;
            }

            // 开启摄像头
            mCameraManager.openCamera(mCameraID, stateCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();

            mCameraDevice = camera;
            takePreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            //关闭摄像头
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mImageRader.close();
                mImageRader = null;
            }
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            mCameraDevice = null;
            Log.e(TAG, "摄像头开启失败");
        }
    };

    private CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (mCameraDevice == null) { return; }
            mCameraCaptureSession = session;

            try {
                mCameraCaptureSession.setRepeatingRequest(previewBuilder.build(), null, childHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG,"配置错误");
        }
    };


    private void takePreview() {
        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(textureView.getWidth(), textureView.getHeight());
        Surface surface = new Surface(texture);

        try {
            if (previewBuilder == null) {
                previewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewBuilder.addTarget(surface);
                // 自动对焦
                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                // 打开闪光
                previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            }

            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageRader.getSurface()), sessionStateCallback, null);


        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void takePicture() {
        try {
            if (pictureBuilder == null) {
                pictureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                pictureBuilder.addTarget(mImageRader.getSurface());
                // 自动对焦
                pictureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                // 打开闪光灯
                pictureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                // 根据设备方向计算照片方向
                pictureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
            }
            mCameraCaptureSession.capture(pictureBuilder.build(), null, childHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();

            if (mCameraCaptureSession != null) {
                mCameraCaptureSession.close();
                mCameraCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mImageRader != null) {
                mImageRader.close();
                mImageRader = null;
            }
            stopBackgroundThread();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void stopBackgroundThread() {
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            try {
                mHandlerThread.join();
                mHandlerThread = null;
                childHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (permissions[0].equals(Manifest.permission.CAMERA) && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            closeCamera();
            openCamera();
        }
    }

    private Bitmap imageToBitmap(Image image) {
         ByteBuffer buffer = image.getPlanes()[0].getBuffer();
         byte[] bytes = new byte[buffer.remaining()];
         buffer.get(bytes);
         return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
     }
}

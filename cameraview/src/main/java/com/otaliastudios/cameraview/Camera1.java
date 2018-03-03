package com.otaliastudios.cameraview;

import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.location.Location;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.support.media.ExifInterface;
import android.view.SurfaceHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


@SuppressWarnings("deprecation")
class Camera1 extends CameraController implements Camera.PreviewCallback, Camera.ErrorCallback, VideoRecorder.VideoResultListener {

    private static final String TAG = Camera1.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    private Camera mCamera;
    private boolean mIsBound = false;

    private final int mPostFocusResetDelay = 3000;
    private Runnable mPostFocusResetRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isCameraAvailable()) return;
            mCamera.cancelAutoFocus();
            Camera.Parameters params = mCamera.getParameters();
            int maxAF = params.getMaxNumFocusAreas();
            int maxAE = params.getMaxNumMeteringAreas();
            if (maxAF > 0) params.setFocusAreas(null);
            if (maxAE > 0) params.setMeteringAreas(null);
            applyDefaultFocus(params); // Revert to internal focus.
            mCamera.setParameters(params);
        }
    };

    Camera1(CameraView.CameraCallbacks callback) {
        super(callback);
        mMapper = new Mapper1();
    }

    private void schedule(@Nullable final Task<Void> task, final boolean ensureAvailable, final Runnable action) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (ensureAvailable && !isCameraAvailable()) {
                    if (task != null) task.end(null);
                } else {
                    action.run();
                    if (task != null) task.end(null);
                }
            }
        });
    }

    // Preview surface is now available. If camera is open, set up.
    @Override
    public void onSurfaceAvailable() {
        LOG.i("onSurfaceAvailable:", "Size is", mPreview.getSurfaceSize());
        schedule(null, false, new Runnable() {
            @Override
            public void run() {
                LOG.i("onSurfaceAvailable:", "Inside handler. About to bind.");
                if (shouldBindToSurface()) bindToSurface();
            }
        });
    }

    // Preview surface did change its size. Compute a new preview size.
    // This requires stopping and restarting the preview.
    @Override
    public void onSurfaceChanged() {
        LOG.i("onSurfaceChanged, size is", mPreview.getSurfaceSize());
        schedule(null, true, new Runnable() {
            @Override
            public void run() {
                if (!mIsBound) return;

                // Compute a new camera preview size.
                Size newSize = computePreviewSize(sizesFromList(mCamera.getParameters().getSupportedPreviewSizes()));
                if (newSize.equals(mPreviewSize)) return;

                // Apply.
                LOG.i("onSurfaceChanged:", "Computed a new preview size. Going on.");
                mPreviewSize = newSize;
                mCamera.stopPreview();
                applySizesAndStartPreview("onSurfaceChanged:");
            }
        });
    }

    private boolean shouldBindToSurface() {
        return isCameraAvailable() && mPreview != null && mPreview.isReady() && !mIsBound;
    }

    // The act of binding an "open" camera to a "ready" preview.
    // These can happen at different times but we want to end up here.
    @WorkerThread
    private void bindToSurface() {
        LOG.i("bindToSurface:", "Started");
        Object output = mPreview.getOutput();
        try {
            if (mPreview.getOutputClass() == SurfaceHolder.class) {
                mCamera.setPreviewDisplay((SurfaceHolder) output);
            } else {
                mCamera.setPreviewTexture((SurfaceTexture) output);
            }
        } catch (IOException e) {
            Log.e("bindToSurface:", "Failed to bind.", e);
            throw new CameraException(e, CameraException.REASON_FAILED_TO_START_PREVIEW);
        }

        mCaptureSize = computeCaptureSize();
        mPreviewSize = computePreviewSize(sizesFromList(mCamera.getParameters().getSupportedPreviewSizes()));
        applySizesAndStartPreview("bindToSurface:");
        mIsBound = true;
    }

    // To be called when the preview size is setup or changed.
    private void applySizesAndStartPreview(String log) {
        LOG.i(log, "Dispatching onCameraPreviewSizeChanged.");
        mCameraCallbacks.onCameraPreviewSizeChanged();

        Size previewSize = getPreviewSize(REF_VIEW);
        mPreview.setDesiredSize(previewSize.getWidth(), previewSize.getHeight());

        Camera.Parameters params = mCamera.getParameters();
        mPreviewFormat = params.getPreviewFormat();
        params.setPreviewSize(mPreviewSize.getWidth(), mPreviewSize.getHeight()); // <- not allowed during preview
        params.setPictureSize(mCaptureSize.getWidth(), mCaptureSize.getHeight()); // <- allowed
        mCamera.setParameters(params);

        mCamera.setPreviewCallbackWithBuffer(null); // Release anything left
        mCamera.setPreviewCallbackWithBuffer(this); // Add ourselves
        mFrameManager.allocate(ImageFormat.getBitsPerPixel(mPreviewFormat), mPreviewSize);

        LOG.i(log, "Starting preview with startPreview().");
        try {
            mCamera.startPreview();
        } catch (Exception e) {
            LOG.e(log, "Failed to start preview.", e);
            throw new CameraException(e, CameraException.REASON_FAILED_TO_START_PREVIEW);
        }
        LOG.i(log, "Started preview.");
    }

    @WorkerThread
    @Override
    void onStart() {
        if (isCameraAvailable()) {
            LOG.w("onStart:", "Camera not available. Should not happen.");
            onStop(); // Should not happen.
        }
        if (collectCameraId()) {
            try {
                mCamera = Camera.open(mCameraId);
            } catch (Exception e) {
                LOG.e("onStart:", "Failed to connect. Maybe in use by another app?");
                throw new CameraException(e, CameraException.REASON_FAILED_TO_CONNECT);
            }
            mCamera.setErrorCallback(this);

            // Set parameters that might have been set before the camera was opened.
            LOG.i("onStart:", "Applying default parameters.");
            Camera.Parameters params = mCamera.getParameters();
            mCameraOptions = new CameraOptions(params, flip(REF_SENSOR, REF_VIEW));
            applyDefaultFocus(params);
            mergeFlash(params, Flash.DEFAULT);
            mergeLocation(params, null);
            mergeWhiteBalance(params, WhiteBalance.DEFAULT);
            mergeHdr(params, Hdr.DEFAULT);
            mergePlaySound(mPlaySounds);
            params.setRecordingHint(mMode == Mode.VIDEO);
            mCamera.setParameters(params);

            // Try starting preview.
            mCamera.setDisplayOrientation(offset(REF_SENSOR, REF_VIEW)); // <- not allowed during preview
            if (shouldBindToSurface()) bindToSurface();
            LOG.i("onStart:", "Ended");
        }
    }

    @WorkerThread
    @Override
    void onStop() {
        LOG.i("onStop:", "About to clean up.");
        mHandler.get().removeCallbacks(mPostFocusResetRunnable);
        mFrameManager.release();

        if (mCamera != null) {
            LOG.i("onStop:", "Clean up.", "Ending video.");
            if (mVideoRecorder != null) {
                mVideoRecorder.stop();
                mVideoRecorder = null;
            }

            try {
                LOG.i("onStop:", "Clean up.", "Stopping preview.");
                mCamera.setPreviewCallbackWithBuffer(null);
                mCamera.stopPreview();
                LOG.i("onStop:", "Clean up.", "Stopped preview.");
            } catch (Exception e) {
                LOG.w("onStop:", "Clean up.", "Exception while stopping preview.", e);
            }

            try {
                LOG.i("onStop:", "Clean up.", "Releasing camera.");
                mCamera.release();
                LOG.i("onStop:", "Clean up.", "Released camera.");
            } catch (Exception e) {
                LOG.w("onStop:", "Clean up.", "Exception while releasing camera.", e);
            }
        }
        mCameraOptions = null;
        mCamera = null;
        mPreviewSize = null;
        mCaptureSize = null;
        mIsBound = false;
        mIsTakingImage = false;
        mIsTakingVideo = false;
        LOG.w("onStop:", "Clean up.", "Returning.");

        // We were saving a reference to the exception here and throwing to the user.
        // I don't think it's correct. We are closing and have already done our best
        // to clean up resources. No need to throw.
        // if (error != null) throw new CameraException(error);
    }

    private boolean collectCameraId() {
        int internalFacing = mMapper.map(mFacing);
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == internalFacing) {
                mSensorOffset = cameraInfo.orientation;
                mCameraId = i;
                return true;
            }
        }
        return false;
    }

    @Override
    public void onBufferAvailable(byte[] buffer) {
        // TODO: sync with handler?
        if (isCameraAvailable()) {
            mCamera.addCallbackBuffer(buffer);
        }
    }

    @Override
    public void onError(int error, Camera camera) {
        if (error == Camera.CAMERA_ERROR_SERVER_DIED) {
            // Looks like this is recoverable.
            LOG.w("Recoverable error inside the onError callback.", "CAMERA_ERROR_SERVER_DIED");
            stopImmediately();
            start();
            return;
        }

        LOG.e("Error inside the onError callback.", error);
        Exception runtime = new RuntimeException(CameraLogger.lastMessage);
        int reason;
        switch (error) {
            case Camera.CAMERA_ERROR_EVICTED: reason = CameraException.REASON_DISCONNECTED; break;
            case Camera.CAMERA_ERROR_UNKNOWN: reason = CameraException.REASON_UNKNOWN; break;
            default: reason = CameraException.REASON_UNKNOWN;
        }
        throw new CameraException(runtime, reason);
    }

    @Override
    void setMode(Mode mode) {
        if (mode != mMode) {
            mMode = mode;
            schedule(null, true, new Runnable() {
                @Override
                public void run() {
                    restart();
                }
            });
        }
    }

    @Override
    void setLocation(Location location) {
        final Location oldLocation = mLocation;
        mLocation = location;
        schedule(mLocationTask, true, new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (mergeLocation(params, oldLocation)) mCamera.setParameters(params);
            }
        });
    }

    private boolean mergeLocation(Camera.Parameters params, Location oldLocation) {
        if (mLocation != null) {
            params.setGpsLatitude(mLocation.getLatitude());
            params.setGpsLongitude(mLocation.getLongitude());
            params.setGpsAltitude(mLocation.getAltitude());
            params.setGpsTimestamp(mLocation.getTime());
            params.setGpsProcessingMethod(mLocation.getProvider());
        }
        return true;
    }

    @Override
    void setFacing(Facing facing) {
        if (facing != mFacing) {
            mFacing = facing;
            schedule(null, true, new Runnable() {
                @Override
                public void run() {
                    if (collectCameraId()) {
                        restart();
                    }
                }
            });
        }
    }

    @Override
    void setWhiteBalance(WhiteBalance whiteBalance) {
        final WhiteBalance old = mWhiteBalance;
        mWhiteBalance = whiteBalance;
        schedule(mWhiteBalanceTask, true, new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (mergeWhiteBalance(params, old)) mCamera.setParameters(params);
            }
        });
    }

    private boolean mergeWhiteBalance(Camera.Parameters params, WhiteBalance oldWhiteBalance) {
        if (mCameraOptions.supports(mWhiteBalance)) {
            params.setWhiteBalance((String) mMapper.map(mWhiteBalance));
            return true;
        }
        mWhiteBalance = oldWhiteBalance;
        return false;
    }

    @Override
    void setHdr(Hdr hdr) {
        final Hdr old = mHdr;
        mHdr = hdr;
        schedule(mHdrTask, true, new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (mergeHdr(params, old)) mCamera.setParameters(params);
            }
        });
    }

    private boolean mergeHdr(Camera.Parameters params, Hdr oldHdr) {
        if (mCameraOptions.supports(mHdr)) {
            params.setSceneMode((String) mMapper.map(mHdr));
            return true;
        }
        mHdr = oldHdr;
        return false;
    }

    @TargetApi(17)
    private boolean mergePlaySound(boolean oldPlaySound) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(mCameraId, info);
            if (info.canDisableShutterSound) {
                mCamera.enableShutterSound(mPlaySounds);
                return true;
            }
        }
        if (mPlaySounds) {
            return true;
        }
        mPlaySounds = oldPlaySound;
        return false;
    }


    @Override
    void setAudio(Audio audio) {
        if (mAudio != audio) {
            if (mIsTakingVideo) {
                LOG.w("Audio setting was changed while recording. " +
                        "Changes will take place starting from next video");
            }
            mAudio = audio;
        }
    }

    @Override
    void setFlash(Flash flash) {
        final Flash old = mFlash;
        mFlash = flash;
        schedule(mFlashTask, true, new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (mergeFlash(params, old)) mCamera.setParameters(params);
            }
        });
    }


    private boolean mergeFlash(Camera.Parameters params, Flash oldFlash) {
        if (mCameraOptions.supports(mFlash)) {
            params.setFlashMode((String) mMapper.map(mFlash));
            return true;
        }
        mFlash = oldFlash;
        return false;
    }


    // Choose the best default focus, based on session type.
    private void applyDefaultFocus(Camera.Parameters params) {
        List<String> modes = params.getSupportedFocusModes();

        if (mMode == Mode.VIDEO &&
                modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            return;
        }

        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            return;
        }

        if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            return;
        }

        if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
            return;
        }
    }


    @Override
    void takePicture() {
        LOG.v("takePicture: scheduling");
        schedule(null, true, new Runnable() {
            @Override
            public void run() {
                if (mMode == Mode.VIDEO) {
                    throw new IllegalStateException("Can't take hq pictures while in VIDEO mode");
                }

                LOG.v("takePicture: performing.", mIsTakingImage);
                if (mIsTakingImage) return;
                mIsTakingImage = true;

                final int sensorToOutput = offset(REF_SENSOR, REF_OUTPUT);
                final Size outputSize = getPictureSize(REF_OUTPUT);
                Camera.Parameters params = mCamera.getParameters();
                params.setRotation(sensorToOutput);
                mCamera.setParameters(params);
                mCamera.takePicture(
                        new Camera.ShutterCallback() {
                            @Override
                            public void onShutter() {
                                mCameraCallbacks.onShutter(false);
                            }
                        },
                        null,
                        null,
                        new Camera.PictureCallback() {
                            @Override
                            public void onPictureTaken(byte[] data, final Camera camera) {
                                mIsTakingImage = false;
                                int exifRotation;
                                try {
                                    ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
                                    int exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                                    exifRotation = CameraUtils.decodeExifOrientation(exifOrientation);
                                } catch (IOException e) {
                                    exifRotation = 0;
                                }
                                PictureResult result = new PictureResult();
                                result.jpeg = data;
                                result.isSnapshot = false;
                                result.location = mLocation;
                                result.rotation = exifRotation;
                                result.size = outputSize;
                                mCameraCallbacks.dispatchOnPictureTaken(result);
                                camera.startPreview(); // This is needed, read somewhere in the docs.
                            }
                        }
                );
            }
        });
    }


    @Override
    void takePictureSnapshot(final AspectRatio viewAspectRatio) {
        LOG.v("takePictureSnapshot: scheduling");
        schedule(null, true, new Runnable() {
            @Override
            public void run() {
                if (mIsTakingVideo) {
                    // TODO v2: what to do here?
                    // This won't work while capturing a video.
                    // But we want it to work.
                    return;
                }

                LOG.v("takePictureSnapshot: performing.", mIsTakingImage);
                if (mIsTakingImage) return;
                mIsTakingImage = true;
                mCamera.setOneShotPreviewCallback(new Camera.PreviewCallback() {
                    @Override
                    public void onPreviewFrame(final byte[] yuv, Camera camera) {
                        mCameraCallbacks.onShutter(true);

                        // Got to rotate the preview frame, since byte[] data here does not include
                        // EXIF tags automatically set by camera. So either we add EXIF, or we rotate.
                        // Adding EXIF to a byte array, unfortunately, is hard.
                        final int sensorToOutput = offset(REF_SENSOR, REF_OUTPUT);
                        final AspectRatio outputRatio = flip(REF_OUTPUT, REF_VIEW) ? viewAspectRatio.inverse() : viewAspectRatio;
                        final Size outputSize = getPreviewSize(REF_OUTPUT);
                        final int format = mPreviewFormat;
                        WorkerHandler.run(new Runnable() {
                            @Override
                            public void run() {
                                // Rotate the picture, because no one will write EXIF data,
                                // then crop if needed. In both cases, transform yuv to jpeg.
                                LOG.v("takePictureSnapshot:", "rotating.");
                                byte[] data = RotationHelper.rotate(yuv, mPreviewSize, sensorToOutput);
                                YuvImage yuv = new YuvImage(data, format, outputSize.getWidth(), outputSize.getHeight(), null);

                                LOG.v("takePictureSnapshot:", "rotated. Cropping and transforming to jpeg.");
                                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                                Rect outputRect = CropHelper.computeCrop(outputSize, outputRatio);
                                yuv.compressToJpeg(outputRect, 90, stream);
                                data = stream.toByteArray();

                                LOG.v("takePictureSnapshot:", "cropped. Dispatching.");
                                PictureResult result = new PictureResult();
                                result.jpeg = data;
                                result.size = new Size(outputRect.width(), outputRect.height());
                                result.rotation = 0;
                                result.location = mLocation;
                                result.isSnapshot = true;
                                mCameraCallbacks.dispatchOnPictureTaken(result);
                                mIsTakingImage = false;
                            }
                        });

                        // It seems that the buffers are already cleared here, so we need to allocate again.
                        mCamera.setPreviewCallbackWithBuffer(null); // Release anything left
                        mCamera.setPreviewCallbackWithBuffer(Camera1.this); // Add ourselves
                        mFrameManager.allocate(ImageFormat.getBitsPerPixel(mPreviewFormat), mPreviewSize);
                    }
                });
            }
        });
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        Frame frame = mFrameManager.getFrame(data,
                System.currentTimeMillis(),
                offset(REF_SENSOR, REF_OUTPUT),
                mPreviewSize,
                mPreviewFormat);
        mCameraCallbacks.dispatchFrame(frame);
    }

    private boolean isCameraAvailable() {
        switch (mState) {
            // If we are stopped, don't.
            case STATE_STOPPED:
                return false;
            // If we are going to be closed, don't act on camera.
            // Even if mCamera != null, it might have been released.
            case STATE_STOPPING:
                return false;
            // If we are started, mCamera should never be null.
            case STATE_STARTED:
                return true;
            // If we are starting, theoretically we could act.
            // Just check that camera is available.
            case STATE_STARTING:
                return mCamera != null;
        }
        return false;
    }

    // -----------------
    // Video recording stuff.


    @Override
    void takeVideo(@NonNull final File videoFile) {
        schedule(mStartVideoTask, true, new Runnable() {
            @Override
            public void run() {
                if (mMode == Mode.PICTURE) {
                    throw new IllegalStateException("Can't record video while in PICTURE mode");
                }

                if (mIsTakingVideo) return;
                mIsTakingVideo = true;

                // Create the video result stub
                VideoResult videoResult = new VideoResult();
                videoResult.file = videoFile;
                videoResult.isSnapshot = false;
                videoResult.codec = mVideoCodec;
                videoResult.location = mLocation;
                videoResult.rotation = offset(REF_SENSOR, REF_OUTPUT);
                videoResult.size = flip(REF_SENSOR, REF_OUTPUT) ? mCaptureSize.flip() : mCaptureSize;
                videoResult.audio = mAudio;
                videoResult.maxSize = mVideoMaxSize;
                videoResult.maxDuration = mVideoMaxDuration;

                // Initialize the media recorder
                mCamera.unlock();
                mVideoRecorder = new MediaRecorderVideoRecorder(videoResult, Camera1.this, mCamera, mCameraId);
                mVideoRecorder.start();
            }
        });
    }

    @Override
    void stopVideo() {
        schedule(null, false, new Runnable() {
            @Override
            public void run() {
                mIsTakingVideo = false;
                if (mVideoRecorder != null) {
                    mVideoRecorder.stop();
                    mVideoRecorder = null;
                }
            }
        });
    }

    @Override
    public void onVideoResult(@Nullable VideoResult result) {
        if (result != null) {
            mCameraCallbacks.dispatchOnVideoTaken(result);
        } else {
            // Something went wrong, lock the camera again.
            mCamera.lock();
        }
        if (mCamera != null) {
            // This is needed to restore FrameProcessor. No re-allocation needed though.
            mCamera.setPreviewCallbackWithBuffer(this);
        }
    }

    @WorkerThread
    private void initMediaRecorder() {
        mMediaRecorder = new MediaRecorder();
        mCamera.unlock();
        mMediaRecorder.setCamera(mCamera);

        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        if (mAudio == Audio.ON) {
            // Must be called before setOutputFormat.
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        }
        CamcorderProfile profile = getCamcorderProfile();
        mMediaRecorder.setOutputFormat(profile.fileFormat);
        mMediaRecorder.setVideoFrameRate(profile.videoFrameRate);
        mMediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        if (mVideoCodec == VideoCodec.DEFAULT) {
            mMediaRecorder.setVideoEncoder(profile.videoCodec);
        } else {
            mMediaRecorder.setVideoEncoder(mMapper.map(mVideoCodec));
        }
        mMediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        if (mAudio == Audio.ON) {
            mMediaRecorder.setAudioChannels(profile.audioChannels);
            mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
            mMediaRecorder.setAudioEncoder(profile.audioCodec);
            mMediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
        }
    }

    // -----------------
    // Zoom and simpler stuff.


    @Override
    void setZoom(final float zoom, final PointF[] points, final boolean notify) {
        schedule(mZoomTask, true, new Runnable() {
            @Override
            public void run() {
                if (!mCameraOptions.isZoomSupported()) return;

                mZoomValue = zoom;
                Camera.Parameters params = mCamera.getParameters();
                float max = params.getMaxZoom();
                params.setZoom((int) (zoom * max));
                mCamera.setParameters(params);

                if (notify) {
                    mCameraCallbacks.dispatchOnZoomChanged(zoom, points);
                }
            }
        });
    }

    @Override
    void setExposureCorrection(final float EVvalue, final float[] bounds,
                               final PointF[] points, final boolean notify) {
        schedule(mExposureCorrectionTask, true, new Runnable() {
            @Override
            public void run() {
                if (!mCameraOptions.isExposureCorrectionSupported()) return;

                float value = EVvalue;
                float max = mCameraOptions.getExposureCorrectionMaxValue();
                float min = mCameraOptions.getExposureCorrectionMinValue();
                value = value < min ? min : value > max ? max : value; // cap
                mExposureCorrectionValue = value;
                Camera.Parameters params = mCamera.getParameters();
                int indexValue = (int) (value / params.getExposureCompensationStep());
                params.setExposureCompensation(indexValue);
                mCamera.setParameters(params);

                if (notify) {
                    mCameraCallbacks.dispatchOnExposureCorrectionChanged(value, bounds, points);
                }
            }
        });
    }

    // -----------------
    // Tap to focus stuff.


    @Override
    void startAutoFocus(@Nullable final Gesture gesture, final PointF point) {
        // Must get width and height from the UI thread.
        int viewWidth = 0, viewHeight = 0;
        if (mPreview != null && mPreview.isReady()) {
            viewWidth = mPreview.getView().getWidth();
            viewHeight = mPreview.getView().getHeight();
        }
        final int viewWidthF = viewWidth;
        final int viewHeightF = viewHeight;
        // Schedule.
        schedule(null, true, new Runnable() {
            @Override
            public void run() {
                if (!mCameraOptions.isAutoFocusSupported()) return;
                final PointF p = new PointF(point.x, point.y); // copy.
                List<Camera.Area> meteringAreas2 = computeMeteringAreas(p.x, p.y,
                        viewWidthF, viewHeightF, offset(REF_SENSOR, REF_VIEW));
                List<Camera.Area> meteringAreas1 = meteringAreas2.subList(0, 1);

                // At this point we are sure that camera supports auto focus... right? Look at CameraView.onTouchEvent().
                Camera.Parameters params = mCamera.getParameters();
                int maxAF = params.getMaxNumFocusAreas();
                int maxAE = params.getMaxNumMeteringAreas();
                if (maxAF > 0) params.setFocusAreas(maxAF > 1 ? meteringAreas2 : meteringAreas1);
                if (maxAE > 0) params.setMeteringAreas(maxAE > 1 ? meteringAreas2 : meteringAreas1);
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                mCamera.setParameters(params);
                mCameraCallbacks.dispatchOnFocusStart(gesture, p);
                // TODO this is not guaranteed to be called... Fix.
                try {
                    mCamera.autoFocus(new Camera.AutoFocusCallback() {
                        @Override
                        public void onAutoFocus(boolean success, Camera camera) {
                            // TODO lock auto exposure and white balance for a while
                            mCameraCallbacks.dispatchOnFocusEnd(gesture, success, p);
                            mHandler.get().removeCallbacks(mPostFocusResetRunnable);
                            mHandler.get().postDelayed(mPostFocusResetRunnable, mPostFocusResetDelay);
                        }
                    });
                } catch (RuntimeException e) {
                    // Handling random auto-focus exception on some devices
                    // See https://github.com/natario1/CameraView/issues/181
                    LOG.e("startAutoFocus:", "Error calling autoFocus", e);
                    mCameraCallbacks.dispatchOnFocusEnd(gesture, false, p);
                }
            }
        });
    }


    @WorkerThread
    private static List<Camera.Area> computeMeteringAreas(double viewClickX, double viewClickY,
                                                          int viewWidth, int viewHeight,
                                                          int sensorToDisplay) {
        // Event came in view coordinates. We must rotate to sensor coordinates.
        // First, rescale to the -1000 ... 1000 range.
        int displayToSensor = -sensorToDisplay;
        viewClickX = -1000d + (viewClickX / (double) viewWidth) * 2000d;
        viewClickY = -1000d + (viewClickY / (double) viewHeight) * 2000d;

        // Apply rotation to this point.
        // https://academo.org/demos/rotation-about-point/
        double theta = ((double) displayToSensor) * Math.PI / 180;
        double sensorClickX = viewClickX * Math.cos(theta) - viewClickY * Math.sin(theta);
        double sensorClickY = viewClickX * Math.sin(theta) + viewClickY * Math.cos(theta);
        LOG.i("focus:", "viewClickX:", viewClickX, "viewClickY:", viewClickY);
        LOG.i("focus:", "sensorClickX:", sensorClickX, "sensorClickY:", sensorClickY);

        // Compute the rect bounds.
        Rect rect1 = computeMeteringArea(sensorClickX, sensorClickY, 150d);
        int weight1 = 1000; // 150 * 150 * 1000 = more than 10.000.000
        Rect rect2 = computeMeteringArea(sensorClickX, sensorClickY, 300d);
        int weight2 = 100; // 300 * 300 * 100 = 9.000.000

        List<Camera.Area> list = new ArrayList<>(2);
        list.add(new Camera.Area(rect1, weight1));
        list.add(new Camera.Area(rect2, weight2));
        return list;
    }


    private static Rect computeMeteringArea(double centerX, double centerY, double size) {
        double delta = size / 2d;
        int top = (int) Math.max(centerY - delta, -1000);
        int bottom = (int) Math.min(centerY + delta, 1000);
        int left = (int) Math.max(centerX - delta, -1000);
        int right = (int) Math.min(centerX + delta, 1000);
        LOG.i("focus:", "computeMeteringArea:", "top:", top, "left:", left, "bottom:", bottom, "right:", right);
        return new Rect(left, top, right, bottom);
    }


    // -----------------
    // Size stuff.


    @Nullable
    private List<Size> sizesFromList(List<Camera.Size> sizes) {
        if (sizes == null) return null;
        List<Size> result = new ArrayList<>(sizes.size());
        for (Camera.Size size : sizes) {
            Size add = new Size(size.width, size.height);
            if (!result.contains(add)) result.add(add);
        }
        LOG.i("size:", "sizesFromList:", result);
        return result;
    }

    @Override
    void setPlaySounds(boolean playSounds) {
        final boolean old = mPlaySounds;
        mPlaySounds = playSounds;
        schedule(mPlaySoundsTask, true, new Runnable() {
            @Override
            public void run() {
                mergePlaySound(old);
            }
        });
    }

    // -----------------
    // Additional helper info
}


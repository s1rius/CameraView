package com.otaliastudios.cameraview.video;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.os.Build;
import android.view.Surface;

import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.overlay.Overlay;
import com.otaliastudios.cameraview.VideoResult;
import com.otaliastudios.cameraview.controls.Audio;
import com.otaliastudios.cameraview.engine.CameraEngine;
import com.otaliastudios.cameraview.internal.egl.EglViewport;
import com.otaliastudios.cameraview.preview.GlCameraPreview;
import com.otaliastudios.cameraview.preview.RendererFrameCallback;
import com.otaliastudios.cameraview.preview.RendererThread;
import com.otaliastudios.cameraview.size.Size;
import com.otaliastudios.cameraview.video.encoding.AudioConfig;
import com.otaliastudios.cameraview.video.encoding.AudioMediaEncoder;
import com.otaliastudios.cameraview.video.encoding.EncoderThread;
import com.otaliastudios.cameraview.video.encoding.MediaEncoderEngine;
import com.otaliastudios.cameraview.video.encoding.TextureConfig;
import com.otaliastudios.cameraview.video.encoding.TextureMediaEncoder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * A {@link VideoRecorder} that uses {@link android.media.MediaCodec} APIs.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class SnapshotVideoRecorder extends VideoRecorder implements RendererFrameCallback,
        MediaEncoderEngine.Listener {

    private static final String TAG = SnapshotVideoRecorder.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    private static final int DEFAULT_VIDEO_FRAMERATE = 30;
    private static final int DEFAULT_AUDIO_BITRATE = 64000;

    // https://stackoverflow.com/a/5220554/4288782
    // Assuming low motion, we don't want to put this too high for default usage,
    // advanced users are still free to change this for each video.
    private static int estimateVideoBitRate(@NonNull Size size, int frameRate) {
        return (int) (0.07F * 1F * size.getWidth() * size.getHeight() * frameRate);
    }

    private static final int STATE_RECORDING = 0;
    private static final int STATE_NOT_RECORDING = 1;

    private MediaEncoderEngine mEncoderEngine;
    private GlCameraPreview mPreview;

    private int mCurrentState = STATE_NOT_RECORDING;
    private int mDesiredState = STATE_NOT_RECORDING;
    private int mTextureId = 0;

    private int mOverlayTextureId = 0;
    private SurfaceTexture mOverlaySurfaceTexture;
    private Surface mOverlaySurface;
    private Overlay mOverlay;
    private boolean mHasOverlay;
    private int mOverlayRotation;

    public SnapshotVideoRecorder(@NonNull CameraEngine engine,
                                 @NonNull GlCameraPreview preview,
                                 @Nullable Overlay overlay,
                                 int overlayRotation) {
        super(engine);
        mPreview = preview;
        mOverlay = overlay;
        mHasOverlay = overlay != null && overlay.drawsOn(Overlay.Target.VIDEO_SNAPSHOT);
        mOverlayRotation = overlayRotation;
    }

    @Override
    protected void onStart() {
        mPreview.addRendererFrameCallback(this);
        mDesiredState = STATE_RECORDING;
    }

    @Override
    protected void onStop() {
        mDesiredState = STATE_NOT_RECORDING;
    }

    @RendererThread
    @Override
    public void onRendererTextureCreated(int textureId) {
        mTextureId = textureId;
        if (mHasOverlay) {
            EglViewport temp = new EglViewport();
            mOverlayTextureId = temp.createTexture();
            mOverlaySurfaceTexture = new SurfaceTexture(mOverlayTextureId);
            mOverlaySurfaceTexture.setDefaultBufferSize(mResult.size.getWidth(), mResult.size.getHeight());
            mOverlaySurface = new Surface(mOverlaySurfaceTexture);
            temp.release(true);
        }
    }

    @RendererThread
    @Override
    public void onRendererFrame(@NonNull SurfaceTexture surfaceTexture, float scaleX, float scaleY) {
        if (mCurrentState == STATE_NOT_RECORDING && mDesiredState == STATE_RECORDING) {
            LOG.i("Starting the encoder engine.");

            // Set default options
            if (mResult.videoFrameRate <= 0) mResult.videoFrameRate = DEFAULT_VIDEO_FRAMERATE;
            if (mResult.videoBitRate <= 0) mResult.videoBitRate = estimateVideoBitRate(mResult.size, mResult.videoFrameRate);
            if (mResult.audioBitRate <= 0) mResult.audioBitRate = DEFAULT_AUDIO_BITRATE;

            // Video. Ensure width and height are divisible by 2, as I have read somewhere.
            Size size = mResult.size;
            int width = size.getWidth();
            int height = size.getHeight();
            width = width % 2 == 0 ? width : width + 1;
            height = height % 2 == 0 ? height : height + 1;
            String type = "";
            switch (mResult.videoCodec) {
                case H_263: type = "video/3gpp"; break; // MediaFormat.MIMETYPE_VIDEO_H263;
                case H_264: type = "video/avc"; break; // MediaFormat.MIMETYPE_VIDEO_AVC:
                case DEVICE_DEFAULT: type = "video/avc"; break;
            }
            LOG.w("Creating frame encoder. Rotation:", mResult.rotation);
            TextureConfig videoConfig = new TextureConfig();
            videoConfig.width = width;
            videoConfig.height = height;
            videoConfig.bitRate = mResult.videoBitRate;
            videoConfig.frameRate = mResult.videoFrameRate;
            videoConfig.rotation = mResult.rotation;
            videoConfig.mimeType = type;
            videoConfig.textureId = mTextureId;
            videoConfig.scaleX = scaleX;
            videoConfig.scaleY = scaleY;
            // Get egl context from the RendererThread, which is the one in which we have created
            // the textureId and the overlayTextureId, managed by the GlSurfaceView.
            // Next operations can then be performed on different threads using this handle.
            videoConfig.eglContext = EGL14.eglGetCurrentContext();
            if (mHasOverlay) {
                videoConfig.overlayTextureId = mOverlayTextureId;
                videoConfig.overlayRotation = mOverlayRotation;
            }
            TextureMediaEncoder videoEncoder = new TextureMediaEncoder(videoConfig);

            // Audio
            AudioMediaEncoder audioEncoder = null;
            if (mResult.audio == Audio.ON || mResult.audio == Audio.MONO || mResult.audio == Audio.STEREO) {
                AudioConfig audioConfig = new AudioConfig();
                audioConfig.bitRate = mResult.audioBitRate;
                if (mResult.audio == Audio.MONO) audioConfig.channels = 1;
                if (mResult.audio == Audio.STEREO) audioConfig.channels = 2;
                audioEncoder = new AudioMediaEncoder(audioConfig);
            }

            // Engine
            mEncoderEngine = new MediaEncoderEngine(mResult.file, videoEncoder, audioEncoder,
                    mResult.maxDuration, mResult.maxSize, SnapshotVideoRecorder.this);
            mEncoderEngine.start();
            mResult.rotation = 0; // We will rotate the result instead.
            mCurrentState = STATE_RECORDING;
        }

        if (mCurrentState == STATE_RECORDING) {
            LOG.v("dispatching frame.");
            TextureMediaEncoder textureEncoder = (TextureMediaEncoder) mEncoderEngine.getVideoEncoder();
            TextureMediaEncoder.Frame frame = textureEncoder.acquireFrame();
            frame.timestamp = surfaceTexture.getTimestamp();
            frame.timestampMillis = System.currentTimeMillis(); // NOTE: this is an approximation but it seems to work.
            surfaceTexture.getTransformMatrix(frame.transform);

            // get overlay
            if (mHasOverlay) {
                try {
                    final Canvas surfaceCanvas = mOverlaySurface.lockHardwareCanvas();
                    surfaceCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    mOverlay.drawOn(Overlay.Target.VIDEO_SNAPSHOT, surfaceCanvas);
                    mOverlaySurface.unlockCanvasAndPost(surfaceCanvas);
                } catch (Surface.OutOfResourcesException e) {
                    LOG.w("Got Surface.OutOfResourcesException while drawing video overlays", e);
                }
                mOverlaySurfaceTexture.updateTexImage();
                mOverlaySurfaceTexture.getTransformMatrix(frame.overlayTransform);
            }

            if (mEncoderEngine != null) {
                // Can happen on teardown. At least it used to.
                // NOTE: If this still happens, I would say we can still crash on mOverlaySurface
                // calls above. We might have to add some synchronization.
                mEncoderEngine.notify(TextureMediaEncoder.FRAME_EVENT, frame);
            }
        }

        if (mCurrentState == STATE_RECORDING && mDesiredState == STATE_NOT_RECORDING) {
            LOG.i("Stopping the encoder engine.");
            mCurrentState = STATE_NOT_RECORDING;
            mEncoderEngine.stop();
        }

    }

    @Override
    public void onEncodingStart() {
        dispatchVideoRecordingStart();
    }

    @Override
    public void onEncodingStop() {
        dispatchVideoRecordingEnd();
    }

    @EncoderThread
    @Override
    public void onEncodingEnd(int stopReason, @Nullable Exception e) {
        // If something failed, undo the result, since this is the mechanism
        // to notify Camera1Engine about this.
        if (e != null) {
            LOG.e("Error onEncodingEnd", e);
            mResult = null;
            mError = e;
        } else {
            if (stopReason == MediaEncoderEngine.END_BY_MAX_DURATION) {
                LOG.i("onEncodingEnd because of max duration.");
                mResult.endReason = VideoResult.REASON_MAX_DURATION_REACHED;
            } else if (stopReason == MediaEncoderEngine.END_BY_MAX_SIZE) {
                LOG.i("onEncodingEnd because of max size.");
                mResult.endReason = VideoResult.REASON_MAX_SIZE_REACHED;
            } else {
                LOG.i("onEncodingEnd because of user.");
            }
        }
        // Cleanup
        mCurrentState = STATE_NOT_RECORDING;
        mDesiredState = STATE_NOT_RECORDING;
        mPreview.removeRendererFrameCallback(SnapshotVideoRecorder.this);
        mPreview = null;
        if (mOverlaySurfaceTexture != null) {
            mOverlaySurfaceTexture.release();
            mOverlaySurfaceTexture = null;
        }
        if (mOverlaySurface != null) {
            mOverlaySurface.release();
            mOverlaySurface = null;
        }
        mEncoderEngine = null;
        dispatchResult();
    }
}

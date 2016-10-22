package com.stremio.react;

import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.webkit.CookieManager;

import android.widget.MediaController;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.AndroidUtil;


import java.util.HashMap;
import java.util.Map;

// This originally extended ScalableVideoView, which extends TextureView
// Now we extend SurfaceView (https://github.com/crosswalk-project/crosswalk-website/wiki/Android-SurfaceView-vs-TextureView)
public class ReactVideoView extends SurfaceView implements IVLCVout.Callback {

    public enum Events {
        EVENT_LOAD_START("onVideoLoadStart"),
        EVENT_LOAD("onVideoLoad"),
        EVENT_ERROR("onVideoError"),
        EVENT_PROGRESS("onVideoProgress"),
        EVENT_SEEK("onVideoSeek"),
        EVENT_END("onVideoEnd"),
        EVENT_STALLED("onPlaybackStalled"),
        EVENT_RESUME("onPlaybackResume"),
        EVENT_READY_FOR_DISPLAY("onReadyForDisplay");

        private final String mName;

        Events(final String name) {
            mName = name;
        }

        @Override
        public String toString() {
            return mName;
        }
    }

    public static final String EVENT_PROP_FAST_FORWARD = "canPlayFastForward";
    public static final String EVENT_PROP_SLOW_FORWARD = "canPlaySlowForward";
    public static final String EVENT_PROP_SLOW_REVERSE = "canPlaySlowReverse";
    public static final String EVENT_PROP_REVERSE = "canPlayReverse";
    public static final String EVENT_PROP_STEP_FORWARD = "canStepForward";
    public static final String EVENT_PROP_STEP_BACKWARD = "canStepBackward";

    public static final String EVENT_PROP_DURATION = "duration";
    public static final String EVENT_PROP_PLAYABLE_DURATION = "playableDuration";
    public static final String EVENT_PROP_CURRENT_TIME = "currentTime";
    public static final String EVENT_PROP_SEEK_TIME = "seekTime";
    public static final String EVENT_PROP_NATURALSIZE = "naturalSize";
    public static final String EVENT_PROP_WIDTH = "width";
    public static final String EVENT_PROP_HEIGHT = "height";
    public static final String EVENT_PROP_ORIENTATION = "orientation";

    public static final String EVENT_PROP_ERROR = "error";
    public static final String EVENT_PROP_WHAT = "what";
    public static final String EVENT_PROP_EXTRA = "extra";

    private ThemedReactContext mThemedReactContext;
    private RCTEventEmitter mEventEmitter;

    private Handler mProgressUpdateHandler = new Handler();
    private Runnable mProgressUpdateRunnable = null;
    private Handler videoControlHandler = new Handler();
    private MediaController mediaController;

    private String mSrcUriString = null;
    private boolean mSrcIsNetwork = false;
    private boolean mSrcIsAsset = false;
    private boolean mPaused = false;
    private float mVolume = 1.0f;

    private boolean mMediaPlayerValid = false; // True if mMediaPlayer is in prepared, started, paused or completed state.
    private int mVideoDuration = 0;
    private int mVideoBufferedDuration = 0;
    private boolean isCompleted = false;

    public ReactVideoView(ThemedReactContext themedReactContext) {
        super(themedReactContext);

        mThemedReactContext = themedReactContext;
        mEventEmitter = themedReactContext.getJSModule(RCTEventEmitter.class);
        themedReactContext.addLifecycleEventListener(this);

        initializeMediaPlayerIfNeeded();
        setSurfaceTextureListener(this);

        mProgressUpdateRunnable = new Runnable() {
            @Override
            public void run() {

                if (mMediaPlayerValid && !isCompleted) {
                    WritableMap event = Arguments.createMap();
                    event.putDouble(EVENT_PROP_CURRENT_TIME, mMediaPlayer.getCurrentPosition() / 1000.0);
                    event.putDouble(EVENT_PROP_PLAYABLE_DURATION, mVideoBufferedDuration / 1000.0); //TODO:mBufferUpdateRunnable
                    mEventEmitter.receiveEvent(getId(), Events.EVENT_PROGRESS.toString(), event);
                }
                mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, 250);
            }
        };
        mProgressUpdateHandler.post(mProgressUpdateRunnable);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    private void initializeMediaPlayerIfNeeded() {
        if (mMediaPlayer == null) {
            mMediaPlayerValid = false;
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setScreenOnWhilePlaying(true);
            mMediaPlayer.setOnVideoSizeChangedListener(this);
            mMediaPlayer.setOnErrorListener(this);
            mMediaPlayer.setOnPreparedListener(this);
            mMediaPlayer.setOnBufferingUpdateListener(this);
            mMediaPlayer.setOnCompletionListener(this);
            mMediaPlayer.setOnInfoListener(this);
        }
    }

    private void initializeMediaControllerIfNeeded() {
        if (mediaController == null) {
            mediaController = new MediaController(this.getContext());
        }
    }

    public void setSrc(final String uriString, final boolean isNetwork, final boolean isAsset) {

        mSrcUriString = uriString;
        mSrcIsNetwork = isNetwork;
        mSrcIsAsset = isAsset;

        mMediaPlayerValid = false;
        mVideoDuration = 0;
        mVideoBufferedDuration = 0;

        initializeMediaPlayerIfNeeded();
        mMediaPlayer.reset();

        try {
            if (isNetwork) {
                // Use the shared CookieManager to access the cookies
                // set by WebViews inside the same app
                CookieManager cookieManager = CookieManager.getInstance();

                Uri parsedUrl = Uri.parse(uriString);
                Uri.Builder builtUrl = parsedUrl.buildUpon();

                String cookie = cookieManager.getCookie(builtUrl.build().toString());

                Map<String, String> headers = new HashMap<String, String>();

                if (cookie != null) {
                    headers.put("Cookie", cookie);
                }

                setDataSource(mThemedReactContext, parsedUrl, headers);
            } else if (isAsset) {
                if (uriString.startsWith("content://")) {
                    Uri parsedUrl = Uri.parse(uriString);
                    setDataSource(mThemedReactContext, parsedUrl);
                } else {
                    setDataSource(uriString);
                }
            } else {
                setRawData(mThemedReactContext.getResources().getIdentifier(
                        uriString,
                        "raw",
                        mThemedReactContext.getPackageName()
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        WritableMap src = Arguments.createMap();
        src.putString(ReactVideoViewManager.PROP_SRC_URI, uriString);
        src.putBoolean(ReactVideoViewManager.PROP_SRC_IS_NETWORK, isNetwork);
        WritableMap event = Arguments.createMap();
        event.putMap(ReactVideoViewManager.PROP_SRC, src);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_LOAD_START.toString(), event);

        prepareAsync(this);
    }

    public void setPausedModifier(final boolean paused) {
        mPaused = paused;

        if (!mMediaPlayerValid) {
            return;
        }

        if (mPaused) {
            if (mMediaPlayer.isPlaying()) {
                pause();
            }
        } else {
            if (!mMediaPlayer.isPlaying()) {
                start();
            }
        }
    }

    public void setVolumeModifier(final float volume) {
        mVolume = volume;
    }

    public void applyModifiers() {
        setPausedModifier(mPaused);
    }

    @Override
    public void onPrepared(MediaPlayer mp) {

        mMediaPlayerValid = true;
        mVideoDuration = mp.getDuration();

        WritableMap naturalSize = Arguments.createMap();
        naturalSize.putInt(EVENT_PROP_WIDTH, mp.getVideoWidth());
        naturalSize.putInt(EVENT_PROP_HEIGHT, mp.getVideoHeight());
        if (mp.getVideoWidth() > mp.getVideoHeight())
            naturalSize.putString(EVENT_PROP_ORIENTATION, "landscape");
        else
            naturalSize.putString(EVENT_PROP_ORIENTATION, "portrait");

        WritableMap event = Arguments.createMap();
        event.putDouble(EVENT_PROP_DURATION, mVideoDuration / 1000.0);
        event.putDouble(EVENT_PROP_CURRENT_TIME, mp.getCurrentPosition() / 1000.0);
        event.putMap(EVENT_PROP_NATURALSIZE, naturalSize);
        // TODO: Actually check if you can.
        event.putBoolean(EVENT_PROP_FAST_FORWARD, true);
        event.putBoolean(EVENT_PROP_SLOW_FORWARD, true);
        event.putBoolean(EVENT_PROP_SLOW_REVERSE, true);
        event.putBoolean(EVENT_PROP_REVERSE, true);
        event.putBoolean(EVENT_PROP_FAST_FORWARD, true);
        event.putBoolean(EVENT_PROP_STEP_BACKWARD, true);
        event.putBoolean(EVENT_PROP_STEP_FORWARD, true);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_LOAD.toString(), event);

        applyModifiers();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {

        WritableMap error = Arguments.createMap();
        error.putInt(EVENT_PROP_WHAT, what);
        error.putInt(EVENT_PROP_EXTRA, extra);
        WritableMap event = Arguments.createMap();
        event.putMap(EVENT_PROP_ERROR, error);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_ERROR.toString(), event);
        return true;
    }

    @Override
    public void onHardwareAccelerationError(IVLCVout vout) {
        // Handle errors with hardware acceleration
        WritableMap error = Arguments.createMap();
        error.putInt(EVENT_PROP_WHAT, "Error with hardware acceleration");
        WritableMap event = Arguments.createMap();
        event.putMap(EVENT_PROP_ERROR, error);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_ERROR.toString(), event);
        return true;
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        switch (what) {
            case MediaPlayer.MEDIA_INFO_BUFFERING_START:
                mEventEmitter.receiveEvent(getId(), Events.EVENT_STALLED.toString(), Arguments.createMap());
                break;
            case MediaPlayer.MEDIA_INFO_BUFFERING_END:
                mEventEmitter.receiveEvent(getId(), Events.EVENT_RESUME.toString(), Arguments.createMap());
                break;
            case MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                mEventEmitter.receiveEvent(getId(), Events.EVENT_READY_FOR_DISPLAY.toString(), Arguments.createMap());
                break;

            default:
        }
        return false;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        mVideoBufferedDuration = (int) Math.round((double) (mVideoDuration * percent) / 100.0);
    }

    @Override
    public void seekTo(int msec) {
        if (mMediaPlayerValid) {
            WritableMap event = Arguments.createMap();
            event.putDouble(EVENT_PROP_CURRENT_TIME, getCurrentPosition() / 1000.0);
            event.putDouble(EVENT_PROP_SEEK_TIME, msec / 1000.0);
            mEventEmitter.receiveEvent(getId(), Events.EVENT_SEEK.toString(), event);

            super.seekTo(msec);
            if (isCompleted && mVideoDuration != 0 && msec < mVideoDuration) {
                isCompleted = false;
            }
        }
    }

    @Override
    public int getBufferPercentage() {
        return 0;
    }

    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public boolean canSeekBackward() {
        return true;
    }

    @Override
    public boolean canSeekForward() {
        return true;
    }

    @Override
    public int getAudioSessionId() {
        return 0;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {

        isCompleted = true;
        mEventEmitter.receiveEvent(getId(), Events.EVENT_END.toString(), null);
    }

    @Override
    protected void onDetachedFromWindow() {

        mMediaPlayerValid = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {

        super.onAttachedToWindow();
        setSrc(mSrcUriString, mSrcIsNetwork, mSrcIsAsset);
    }

    @Override
    public void onHostPause() {

        if (mMediaPlayer != null) {
            mMediaPlayer.pause();
        }
    }

    @Override
    public void onHostResume() {
    }

    @Override
    public void onHostDestroy() {
    }
}

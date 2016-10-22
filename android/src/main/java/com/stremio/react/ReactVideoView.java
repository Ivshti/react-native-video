package com.stremio.react;

import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.AndroidUtil;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

// This originally extended ScalableVideoView, which extends TextureView
// Now we extend SurfaceView (https://github.com/crosswalk-project/crosswalk-website/wiki/Android-SurfaceView-vs-TextureView)
public class ReactVideoView extends SurfaceView implements IVLCVout.Callback, LifecycleEventListener {

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

    private static final String TAG = "RCTVLC";

    public static final String EVENT_PROP_DURATION = "duration";
    //public static final String EVENT_PROP_PLAYABLE_DURATION = "playableDuration";
    public static final String EVENT_PROP_CURRENT_TIME = "currentTime";
    public static final String EVENT_PROP_SEEK_TIME = "seekTime";
    public static final String EVENT_PROP_WIDTH = "width";
    public static final String EVENT_PROP_HEIGHT = "height";

    public static final String EVENT_PROP_ERROR = "error";
    public static final String EVENT_PROP_WHAT = "what";
    public static final String EVENT_PROP_EXTRA = "extra";

    private ThemedReactContext mThemedReactContext;
    private RCTEventEmitter mEventEmitter;

    private String mSrcUriString = null;
    private boolean mPaused = false;
    private float mVolume = 1.0f;

    private LibVLC libvlc;
    private MediaPlayer mMediaPlayer = null;
    private int mVideoWidth;
    private int mVideoHeight;

    private SurfaceHolder holder;

    private int mVideoDuration = 0;
    private int mVideoBufferedDuration = 0;
    private boolean isCompleted = false;

    public ReactVideoView(ThemedReactContext themedReactContext) {
        super(themedReactContext);

        mThemedReactContext = themedReactContext;
        mEventEmitter = themedReactContext.getJSModule(RCTEventEmitter.class);
        themedReactContext.addLifecycleEventListener(this);

        createPlayer();
    }

    private void createPlayer() {
        if (mMediaPlayer != null) return;

        try {
            // Create LibVLC
            ArrayList<String> options = new ArrayList<String>();
            //options.add("--subsdec-encoding <encoding>");
            options.add("--aout=opensles");
            options.add("--audio-time-stretch"); // time stretching
            options.add("-vvv"); // verbosity
            options.add("--http-reconnect");
            options.add("--network-caching="+(8*1000));
            libvlc = new LibVLC(options);
            holder.setKeepScreenOn(true);

            // Create media player
            mMediaPlayer = new MediaPlayer(libvlc);
            mMediaPlayer.setEventListener(mPlayerListener);

            // Set up video output
            final IVLCVout vout = mMediaPlayer.getVLCVout();
            vout.setVideoView(this);
            vout.addCallback(this);
            vout.attachViews();
        } catch (Exception e) {
            // TODO onError
        }

        holder = this.getHolder();
    }

    private void releasePlayer() {
        if (libvlc == null) return;
        mMediaPlayer.stop();
        final IVLCVout vout = mMediaPlayer.getVLCVout();
        vout.removeCallback(this);
        vout.detachViews();
        holder = null;
        libvlc.release();
        libvlc = null;
    }

   private void setSize(int width, int height) {
        mVideoWidth = width;
        mVideoHeight = height;
        if (mVideoWidth * mVideoHeight <= 1)
            return;

        if(holder == null)
            return;

        /*
        // get screen size
        int w = getWindow().getDecorView().getWidth();
        int h = getWindow().getDecorView().getHeight();

        // getWindow().getDecorView() doesn't always take orientation into
        // account, we have to correct the values
        boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        if (w > h && isPortrait || w < h && !isPortrait) {
            int i = w;
            w = h;
            h = i;
        }

        float videoAR = (float) mVideoWidth / (float) mVideoHeight;
        float screenAR = (float) w / (float) h;

        if (screenAR < videoAR)
            h = (int) (w / videoAR);
        else
            w = (int) (h * videoAR);
        */

        // force surface buffer size
        holder.setFixedSize(mVideoWidth, mVideoHeight);

        // set display size
        /*
        LayoutParams lp = this.getLayoutParams();
        lp.width = w;
        lp.height = h;
        this.setLayoutParams(lp);
        this.invalidate();
        */
    }

    @Override
    public void onNewLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        if (width * height == 0)
            return;

        // store video size
        mVideoWidth = width;
        mVideoHeight = height;
        setSize(mVideoWidth, mVideoHeight);
    }

    @Override
    public void onSurfacesCreated(IVLCVout vout) {

    }

    @Override
    public void onSurfacesDestroyed(IVLCVout vout) {

    }

    public void setSrc(final String uriString) {

        mSrcUriString = uriString;

        mVideoDuration = 0;

        createPlayer();

        Media m = new Media(libvlc, Uri.parse(uriString));
        mMediaPlayer.setMedia(m);
        mMediaPlayer.play();

        WritableMap src = Arguments.createMap();
        src.putString(ReactVideoViewManager.PROP_SRC_URI, uriString);
        WritableMap event = Arguments.createMap();
        event.putMap(ReactVideoViewManager.PROP_SRC, src);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_LOAD_START.toString(), event);
    }

    public void setPausedModifier(final boolean paused) {
        mPaused = paused;

        if (mPaused) {
            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
            }
        } else {
            if (!mMediaPlayer.isPlaying()) {
                mMediaPlayer.play();
            }
        }
    }

    public void setVolumeModifier(final float volume) {
        mVolume = volume;
        mMediaPlayer.setVolume((int) volume * 200);
    }

    public void applyModifiers() {
        setPausedModifier(mPaused);
    }

    public void seekTo(int msec) {
        WritableMap event = Arguments.createMap();
        event.putDouble(EVENT_PROP_CURRENT_TIME, mMediaPlayer.getTime() / 1000.0);
        event.putDouble(EVENT_PROP_SEEK_TIME, msec / 1000.0);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_SEEK.toString(), event);

        mMediaPlayer.setTime(msec);
        if (isCompleted && mVideoDuration != 0 && msec < mVideoDuration) {
            isCompleted = false;
        }
    }

    @Override
    public void onHardwareAccelerationError(IVLCVout vout) {
        // Handle errors with hardware acceleration
        WritableMap error = Arguments.createMap();
        error.putString(EVENT_PROP_WHAT, "Error with hardware acceleration");
        WritableMap event = Arguments.createMap();
        event.putMap(EVENT_PROP_ERROR, error);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_ERROR.toString(), event);
    }


    private MediaPlayer.EventListener mPlayerListener = new MyPlayerListener(this);

    private static class MyPlayerListener implements MediaPlayer.EventListener {
        private WeakReference<ReactVideoView> mOwner;

        public MyPlayerListener(ReactVideoView owner) {
            mOwner = new WeakReference<ReactVideoView>(owner);
        }

        @Override
        public void onEvent(MediaPlayer.Event ev) {
            ReactVideoView player = mOwner.get();
            switch(ev.type) {
                case MediaPlayer.Event.EndReached:
                    Log.d(TAG, "MediaPlayerEndReached");
                    player.releasePlayer();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    Log.d(TAG, "Media Player Error, re-try");
                    //player.releasePlayer();
                    break;
                case MediaPlayer.Event.Playing:
                    Log.d(TAG, "Media Player Playing");
                    break;
                case MediaPlayer.Event.Paused:
                    Log.d(TAG, "Media Player Paused");
                    break;
                case MediaPlayer.Event.Stopped:
                    Log.d(TAG, "Media Player Stopped");
                    break;
                case MediaPlayer.Event.TimeChanged:
                    Log.d(TAG, "Time Changed");
                    // WritableMap event = Arguments.createMap();
                    // event.putDouble(EVENT_PROP_CURRENT_TIME, mMediaPlayer.getTime() / 1000.0);
                    // mEventEmitter.mEventEmitter.receiveEvent(getId(), Events.EVENT_PROGRESS.toString(), event);
                    break;
                default:
                    break;
            }
        }
    }

    /*
    @Override
    public void onPrepared(MediaPlayer mp) {
        mVideoDuration = mp.getDuration();

        WritableMap naturalSize = Arguments.createMap();
        naturalSize.putInt(EVENT_PROP_WIDTH, mp.getVideoWidth());
        naturalSize.putInt(EVENT_PROP_HEIGHT, mp.getVideoHeight());

        WritableMap event = Arguments.createMap();
        event.putDouble(EVENT_PROP_DURATION, mVideoDuration / 1000.0);
        event.putDouble(EVENT_PROP_CURRENT_TIME, mp.getCurrentPosition() / 1000.0);

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
    public void onCompletion(MediaPlayer mp) {
        isCompleted = true;
        mEventEmitter.receiveEvent(getId(), Events.EVENT_END.toString(), null);
    }
    */

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setSrc(mSrcUriString);
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

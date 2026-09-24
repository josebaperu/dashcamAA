package com.aauto.dashcam.helper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.aauto.dashcam.api.IDashcamCallback;
import com.aauto.dashcam.api.IDashcamControl;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Binds to the dashcam app's signature-protected control service.
 */
public final class DashcamClient {
    public interface Listener {
        void onConnectionChanged(boolean connected);

        void onStatus(int state, boolean loopEnabled, long durationMs, String message, boolean frontCamera);
    }

    public static final String DASHCAM_PACKAGE = "com.aauto.dashcam";
    public static final String BIND_ACTION = "com.aauto.dashcam.api.BIND";
    private static final String TAG = "DashcamClient";

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private IDashcamControl control;
    private boolean bound;

    private final IDashcamCallback callback = new IDashcamCallback.Stub() {
        @Override
        public void onStatusChanged(int state, boolean loopEnabled, long durationMs, String message, boolean frontCamera) {
            main.post(() -> {
                for (Listener listener : listeners) {
                    listener.onStatus(state, loopEnabled, durationMs, message, frontCamera);
                }
            });
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            control = IDashcamControl.Stub.asInterface(service);
            try {
                control.registerCallback(callback);
            } catch (RemoteException e) {
                Log.w(TAG, "registerCallback failed", e);
            }
            notifyConnection(true);
            pullStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            control = null;
            notifyConnection(false);
        }
    };

    public DashcamClient(Context context) {
        app = context.getApplicationContext();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onConnectionChanged(isConnected());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return control != null;
    }

    public void rebind() {
        unbind();
        bind();
    }

    public void bind() {
        if (bound) {
            return;
        }
        Intent intent = new Intent(BIND_ACTION);
        intent.setPackage(DASHCAM_PACKAGE);
        bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            notifyConnection(false);
        }
    }

    public void unbind() {
        if (!bound) {
            return;
        }
        if (control != null) {
            try {
                control.unregisterCallback(callback);
            } catch (RemoteException ignored) {
            }
        }
        try {
            app.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
        bound = false;
        control = null;
        notifyConnection(false);
    }

    public void play() {
        run(IDashcamControl::play);
    }

    public void pause() {
        run(IDashcamControl::pause);
    }

    public void resume() {
        run(IDashcamControl::resumeRecording);
    }

    public void stop() {
        run(IDashcamControl::stop);
    }

    public void toggleLoop() {
        IDashcamControl c = control;
        if (c == null) {
            return;
        }
        try {
            c.setLoopEnabled(!c.isLoopEnabled());
        } catch (RemoteException e) {
            Log.w(TAG, "toggleLoop failed", e);
        }
    }

    public void toggleCamera() {
        run(IDashcamControl::toggleCamera);
    }

    public void refreshFromService() {
        pullStatus();
    }

    private void pullStatus() {
        IDashcamControl c = control;
        if (c == null) {
            return;
        }
        try {
            int state = c.getState();
            boolean loop = c.isLoopEnabled();
            boolean front = c.isFrontCamera();
            long duration = c.getDurationMs();
            String message = c.getStatusMessage();
            for (Listener listener : listeners) {
                listener.onStatus(state, loop, duration, message, front);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "pullStatus failed", e);
        }
    }

    private void run(RemoteAction action) {
        IDashcamControl c = control;
        if (c == null) {
            return;
        }
        try {
            action.call(c);
        } catch (RemoteException e) {
            Log.w(TAG, "command failed", e);
        }
    }

    private void notifyConnection(boolean connected) {
        for (Listener listener : listeners) {
            listener.onConnectionChanged(connected);
        }
    }

    @FunctionalInterface
    private interface RemoteAction {
        void call(IDashcamControl control) throws RemoteException;
    }
}

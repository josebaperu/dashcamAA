package com.aauto.dashcam.helper;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.aauto.dashcam.api.IDashcamControl;

public class DashcamScreen extends Screen implements DashcamClient.Listener {
    /**
     * Car clock steps in 10s marks (00:00, 00:10, 00:20). That spacing matches
     * typical head-unit refresh throttling; 5s steps are close enough to starve it.
     */
    private static final long CLOCK_STEP_MS = 10_000L;

    private final DashcamClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (connected) {
                client.refreshFromService();
            }
            handler.postDelayed(this, 1000L);
        }
    };
    private int state = IDashcamControl.STATE_IDLE;
    private boolean loopEnabled;
    private boolean frontCamera;
    private long durationMs;
    private String message = "";
    private boolean connected;
    private int renderedState = Integer.MIN_VALUE;
    private boolean renderedLoop;
    private boolean renderedFront;
    private boolean renderedConnected;
    private long renderedDurationBucket = Long.MIN_VALUE;

    public DashcamScreen(@NonNull CarContext carContext, DashcamClient client) {
        super(carContext);
        this.client = client;
        this.client.addListener(this);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                handler.post(tick);
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                handler.removeCallbacks(tick);
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        if (!connected) {
            return new MessageTemplate.Builder(getCarContext().getString(R.string.disconnected))
                    .setTitle(getCarContext().getString(R.string.car_title))
                    .setHeaderAction(Action.APP_ICON)
                    .addAction(new Action.Builder()
                            .setTitle(getCarContext().getString(R.string.retry))
                            .setOnClickListener(() -> {
                                client.bind();
                                CarToast.makeText(
                                        getCarContext(),
                                        getCarContext().getString(R.string.retry),
                                        CarToast.LENGTH_SHORT).show();
                            })
                            .build())
                    .build();
        }

        ItemList.Builder items = new ItemList.Builder();
        items.addItem(gridItem(
                getCarContext().getString(R.string.play),
                playSubtitle(),
                playIcon(),
                client::play));
        items.addItem(gridItem(
                getCarContext().getString(R.string.pause),
                "hold",
                R.drawable.ic_pause,
                client::pause));
        items.addItem(gridItem(
                getCarContext().getString(R.string.stop),
                "save",
                R.drawable.ic_stop,
                client::stop));
        items.addItem(gridItem(
                getCarContext().getString(R.string.loop),
                loopEnabled ? "ON" : "OFF",
                R.drawable.ic_loop,
                client::toggleLoop));
        items.addItem(gridItem(
                getCarContext().getString(R.string.camera),
                frontCamera ? "FRONT" : "REAR",
                R.drawable.ic_camera,
                client::toggleCamera));

        return new GridTemplate.Builder()
                .setTitle(headerTitle())
                .setHeaderAction(Action.APP_ICON)
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(new Action.Builder()
                                .setTitle(clockLabel())
                                .setIcon(carIcon(R.drawable.ic_timer))
                                .setOnClickListener(client::refreshFromService)
                                .build())
                        .build())
                .setSingleList(items.build())
                .build();
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        this.connected = connected;
        refreshIfVisibleChanged();
    }

    @Override
    public void onStatus(int state, boolean loopEnabled, long durationMs, String message, boolean frontCamera) {
        this.state = state;
        this.loopEnabled = loopEnabled;
        this.durationMs = durationMs;
        this.message = message == null ? "" : message;
        this.frontCamera = frontCamera;
        refreshIfVisibleChanged();
    }

    private void refreshIfVisibleChanged() {
        long bucket = displayDurationMs() / CLOCK_STEP_MS;
        boolean stateChanged = connected != renderedConnected
                || state != renderedState
                || loopEnabled != renderedLoop
                || frontCamera != renderedFront;
        boolean durationDue = bucket != renderedDurationBucket;
        if (!stateChanged && !durationDue) {
            return;
        }
        renderedConnected = connected;
        renderedState = state;
        renderedLoop = loopEnabled;
        renderedFront = frontCamera;
        renderedDurationBucket = bucket;
        invalidate();
    }

    private long displayDurationMs() {
        long ms = Math.max(0L, durationMs);
        return (ms / CLOCK_STEP_MS) * CLOCK_STEP_MS;
    }

    /**
     * GridTemplate treats a title change as a new screen, so keep "Dashcam"
     * and put REC / paused / idle in the Play subtitle instead.
     */
    private String headerTitle() {
        return getCarContext().getString(R.string.car_title);
    }

    private String clockLabel() {
        String clock = formatDuration(displayDurationMs());
        return switch (state) {
            case IDashcamControl.STATE_RECORDING -> "REC " + clock;
            case IDashcamControl.STATE_PAUSED -> "PAUSED " + clock;
            default -> clock;
        };
    }

    private String playSubtitle() {
        String clock = formatDuration(displayDurationMs());
        return switch (state) {
            case IDashcamControl.STATE_RECORDING -> "REC " + clock;
            case IDashcamControl.STATE_PAUSED -> "resume " + clock;
            default -> "start";
        };
    }

    private int playIcon() {
        return state == IDashcamControl.STATE_PAUSED ? R.drawable.ic_resume : R.drawable.ic_play;
    }

    private CarIcon carIcon(int iconRes) {
        return new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), iconRes)).build();
    }

    private GridItem gridItem(String title, String text, int iconRes, Runnable action) {
        CarIcon icon = carIcon(iconRes);
        return new GridItem.Builder()
                .setTitle(title)
                .setText(text)
                .setImage(icon, GridItem.IMAGE_TYPE_ICON)
                .setOnClickListener(action::run)
                .build();
    }

    static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs) / 1000L;
        return String.format(java.util.Locale.US, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }
}

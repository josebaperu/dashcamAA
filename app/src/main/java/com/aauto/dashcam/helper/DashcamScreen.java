package com.aauto.dashcam.helper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.ItemList;
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
    private static final CarColor COLOR_ACTIVE = CarColor.RED;
    private static final CarColor COLOR_DISABLED =
            CarColor.createCustom(0xFF9AA3B2, 0xFF9AA3B2);

    private final DashcamClient client;
    private int state = IDashcamControl.STATE_IDLE;
    private boolean loopEnabled;
    private boolean frontCamera;
    private long durationMs;
    private boolean connected;
    private int renderedState = Integer.MIN_VALUE;
    private boolean renderedLoop;
    private boolean renderedFront;
    private boolean renderedConnected;
    private long renderedDurationBucket = Long.MIN_VALUE;

    public DashcamScreen(@NonNull CarContext carContext, DashcamClient client) {
        super(carContext);
        this.client = client;
        // No polling: Dashcam pushes every state change and each second of duration.
        this.client.addListener(this);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                // Updates that arrived while hidden couldn't redraw (invalidate is a no-op when
                // stopped), and the host isn't guaranteed to re-fetch on return. Titles don't
                // change, so this is a refresh, not a template-quota step.
                invalidate();
            }
        });
    }

    // setTitle/setHeaderAction/setActionStrip are deprecated for setHeader, but setHeader
    // needs Car API 7 and this app supports hosts down to API 1 (minCarApiLevel).
    @SuppressWarnings("deprecation")
    @NonNull
    @Override
    public Template onGetTemplate() {
        ItemList.Builder items = new ItemList.Builder();
        items.addItem(recordItem());
        items.addItem(pauseItem());
        items.addItem(stopItem());
        items.addItem(loopItem());
        items.addItem(cameraItem());
        items.addItem(statusItem());

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
        if (!connected) {
            // Last-known state is stale once the link drops; never keep showing REC.
            state = IDashcamControl.STATE_IDLE;
            durationMs = 0L;
        }
        refreshIfVisibleChanged();
    }

    @Override
    public void onStatus(int state, boolean loopEnabled, long durationMs, String message, boolean frontCamera) {
        this.state = state;
        this.loopEnabled = loopEnabled;
        this.durationMs = durationMs;
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
     * and put REC / paused / idle in the clock label instead.
     */
    private String headerTitle() {
        return getCarContext().getString(R.string.car_title);
    }

    private String clockLabel() {
        if (!connected) {
            return "--:--";
        }
        String clock = formatDuration(displayDurationMs());
        return switch (state) {
            case IDashcamControl.STATE_RECORDING -> "REC " + clock;
            case IDashcamControl.STATE_WAITING_FOR_CAMERA -> "WAIT " + clock;
            case IDashcamControl.STATE_PAUSED -> "PAUSED " + clock;
            default -> clock;
        };
    }

    /** Recording was requested; frames may or may not be arriving. */
    private boolean recording() {
        return state == IDashcamControl.STATE_RECORDING
                || state == IDashcamControl.STATE_WAITING_FOR_CAMERA;
    }

    private int playIcon() {
        return state == IDashcamControl.STATE_PAUSED ? R.drawable.ic_resume : R.drawable.ic_play;
    }

    /** Connected and Dashcam has a camera bound, so recording commands can work. */
    private boolean ready() {
        return connected && state != IDashcamControl.STATE_NO_CAMERA;
    }

    private boolean idle() {
        return state == IDashcamControl.STATE_IDLE || state == IDashcamControl.STATE_NO_CAMERA;
    }

    private GridItem recordItem() {
        boolean enabled = ready() && !recording();
        CarColor color;
        if (!ready()) {
            color = COLOR_DISABLED;
        } else if (recording()) {
            color = COLOR_ACTIVE;
        } else {
            color = null;
        }
        return transportItem(
                getCarContext().getString(R.string.play),
                playIcon(),
                enabled,
                color,
                client::play);
    }

    private GridItem pauseItem() {
        boolean enabled = ready() && state == IDashcamControl.STATE_RECORDING;
        CarColor color;
        if (!ready()) {
            color = COLOR_DISABLED;
        } else if (state == IDashcamControl.STATE_PAUSED) {
            color = COLOR_ACTIVE;
        } else {
            color = null;
        }
        return transportItem(
                getCarContext().getString(R.string.pause),
                R.drawable.ic_pause,
                enabled,
                color,
                client::pause);
    }

    private GridItem stopItem() {
        boolean enabled = ready() && state != IDashcamControl.STATE_IDLE;
        CarColor color;
        if (!ready()) {
            color = COLOR_DISABLED;
        } else if (state == IDashcamControl.STATE_IDLE) {
            color = COLOR_ACTIVE;
        } else {
            color = null;
        }
        return transportItem(
                getCarContext().getString(R.string.stop),
                R.drawable.ic_stop,
                enabled,
                color,
                client::stop);
    }

    /** Loop mode can only change while idle, not mid-recording or paused. */
    private GridItem loopItem() {
        boolean enabled = connected && idle();
        return transportItem(
                getCarContext().getString(R.string.loop),
                loopEnabled ? R.drawable.ic_loop : R.drawable.ic_loop_off,
                enabled,
                enabled ? null : COLOR_DISABLED,
                client::toggleLoop);
    }

    /** Disabled while paused: a switch restarts the clip, which would undo the pause. */
    private GridItem cameraItem() {
        boolean enabled = connected && state != IDashcamControl.STATE_PAUSED;
        return transportItem(
                getCarContext().getString(R.string.camera),
                frontCamera ? R.drawable.ic_camera_front : R.drawable.ic_camera_rear,
                enabled,
                enabled ? null : COLOR_DISABLED,
                client::toggleCamera);
    }

    /**
     * Title and icon only: a second text line makes two rows of six cells taller than
     * the head unit's screen, so the grid scrolls. State shows through icon shape and tint;
     * titles stay fixed because a title change counts against the template quota.
     */
    private GridItem transportItem(
            String title,
            int iconRes,
            boolean enabled,
            @Nullable CarColor color,
            Runnable action) {
        CarIcon.Builder icon = new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), iconRes));
        if (color != null) {
            icon.setTint(color);
        }
        GridItem.Builder item = new GridItem.Builder()
                .setTitle(title)
                .setImage(icon.build(), GridItem.IMAGE_TYPE_ICON);
        if (enabled) {
            item.setOnClickListener(action::run);
        }
        return item.build();
    }

    private CarIcon carIcon(int iconRes) {
        return new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), iconRes)).build();
    }

    /**
     * Same situations as the phone screen's connection line: red when recording can't work.
     * The icon names the reason, since the title has to stay "Status" (see transportItem).
     */
    private GridItem statusItem() {
        int iconRes;
        if (!connected) {
            iconRes = R.drawable.ic_status_disconnected;
        } else if (state == IDashcamControl.STATE_NO_CAMERA) {
            iconRes = R.drawable.ic_status_camera_off;
        } else if (state == IDashcamControl.STATE_WAITING_FOR_CAMERA) {
            iconRes = R.drawable.ic_status_waiting;
        } else {
            iconRes = R.drawable.ic_status_ok;
        }
        CarColor color = iconRes == R.drawable.ic_status_ok ? CarColor.GREEN : COLOR_ACTIVE;
        CarIcon icon = new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), iconRes))
                .setTint(color)
                .build();
        GridItem.Builder item = new GridItem.Builder()
                .setTitle(getCarContext().getString(R.string.status))
                .setImage(icon, GridItem.IMAGE_TYPE_ICON);
        // Only a lost link can be retried from the car; the camera states need the phone.
        if (!connected) {
            item.setOnClickListener(client::rebind);
        }
        return item.build();
    }

    static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs) / 1000L;
        return String.format(java.util.Locale.US, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }
}

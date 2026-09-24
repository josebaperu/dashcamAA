package com.aauto.dashcam.helper;

import android.text.SpannableString;
import android.text.Spanned;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ForegroundCarColorSpan;
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
    /**
     * Same title connected or not: GridTemplate counts a grid item title change
     * as a new step against the host's 5-template quota, not a refresh.
     */
    private static final String STATUS_TITLE = "\u00A0";
    private static final CarColor COLOR_ACTIVE = CarColor.RED;
    private static final CarColor COLOR_DISABLED =
            CarColor.createCustom(0xFF9AA3B2, 0xFF9AA3B2);

    private final DashcamClient client;
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
    private String renderedMessage = "";
    private long renderedDurationBucket = Long.MIN_VALUE;

    public DashcamScreen(@NonNull CarContext carContext, DashcamClient client) {
        super(carContext);
        this.client = client;
        // No polling: Dashcam pushes every state/message change and each second of duration.
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
            message = "";
        }
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
                || frontCamera != renderedFront
                || !message.equals(renderedMessage);
        boolean durationDue = bucket != renderedDurationBucket;
        if (!stateChanged && !durationDue) {
            return;
        }
        renderedConnected = connected;
        renderedState = state;
        renderedLoop = loopEnabled;
        renderedFront = frontCamera;
        renderedMessage = message;
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

    private String playSubtitle() {
        return switch (state) {
            case IDashcamControl.STATE_RECORDING -> "REC";
            case IDashcamControl.STATE_WAITING_FOR_CAMERA -> "wait";
            case IDashcamControl.STATE_PAUSED -> "resume";
            default -> "start";
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
                playSubtitle(),
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
                "hold",
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
                "save",
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
                loopEnabled ? "ON" : "OFF",
                R.drawable.ic_loop,
                enabled,
                enabled ? null : COLOR_DISABLED,
                client::toggleLoop);
    }

    /** Disabled while paused: a switch restarts the clip, which would undo the pause. */
    private GridItem cameraItem() {
        boolean enabled = connected && state != IDashcamControl.STATE_PAUSED;
        return transportItem(
                getCarContext().getString(R.string.camera),
                frontCamera ? "FRONT" : "REAR",
                R.drawable.ic_camera,
                enabled,
                enabled ? null : COLOR_DISABLED,
                client::toggleCamera);
    }

    private GridItem transportItem(
            String title,
            String text,
            int iconRes,
            boolean enabled,
            @Nullable CarColor color,
            Runnable action) {
        CarIcon.Builder icon = new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), iconRes));
        if (color != null) {
            icon.setTint(color);
        }
        CharSequence labeled = text;
        if (color != null) {
            SpannableString span = new SpannableString(text);
            span.setSpan(
                    ForegroundCarColorSpan.create(color),
                    0,
                    text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            labeled = span;
        }
        GridItem.Builder item = new GridItem.Builder()
                .setTitle(title)
                .setText(labeled)
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

    private GridItem statusItem() {
        String label = alertText();
        if (label == null) {
            // Nothing wrong. When idle, show why Dashcam stopped ("Stopped", "Storage full"...).
            String text = state == IDashcamControl.STATE_IDLE && !message.isEmpty()
                    ? message
                    : "\u00A0";
            return new GridItem.Builder()
                    .setTitle(STATUS_TITLE)
                    .setText(text)
                    .setImage(carIcon(R.drawable.ic_blank), GridItem.IMAGE_TYPE_ICON)
                    .build();
        }
        SpannableString red = new SpannableString(label);
        red.setSpan(
                ForegroundCarColorSpan.create(COLOR_ACTIVE),
                0,
                label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        CarIcon icon = new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), R.drawable.ic_status))
                .setTint(COLOR_ACTIVE)
                .build();
        GridItem.Builder item = new GridItem.Builder()
                .setTitle(STATUS_TITLE)
                .setText(red)
                .setImage(icon, GridItem.IMAGE_TYPE_ICON);
        // Only a lost link can be retried from the car; the camera states need the phone.
        if (!connected) {
            item.setOnClickListener(client::rebind);
        }
        return item.build();
    }

    /** Red status text when recording can't work right now, or null when all is well. */
    @Nullable
    private String alertText() {
        if (!connected) {
            return getCarContext().getString(R.string.not_ready);
        }
        if (state == IDashcamControl.STATE_NO_CAMERA) {
            return getCarContext().getString(R.string.open_dashcam);
        }
        if (state == IDashcamControl.STATE_WAITING_FOR_CAMERA) {
            return getCarContext().getString(R.string.waiting_camera);
        }
        return null;
    }

    static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs) / 1000L;
        return String.format(java.util.Locale.US, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }
}

package com.aauto.dashcam.helper;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.aauto.dashcam.api.IDashcamControl;
import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity implements DashcamClient.Listener {
    private DashcamClient client;
    private TextView connection;
    private TextView status;
    private TextView timer;
    private MaterialButton btnPlay;
    private MaterialButton btnPause;
    private MaterialButton btnStop;
    private MaterialButton btnLoop;
    private MaterialButton btnCamera;
    private MaterialButton btnRetry;
    /** Linked, but Dashcam has no camera: the bottom button opens Dashcam instead of retrying. */
    private boolean cameraOff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyScreenPadding(findViewById(R.id.root));
        connection = findViewById(R.id.connection);
        status = findViewById(R.id.status);
        timer = findViewById(R.id.timer);
        btnPlay = findViewById(R.id.btnPlay);
        btnPause = findViewById(R.id.btnPause);
        btnStop = findViewById(R.id.btnStop);
        btnLoop = findViewById(R.id.btnLoop);
        btnCamera = findViewById(R.id.btnCamera);

        client = new DashcamClient(this);
        btnPlay.setOnClickListener(v -> client.play());
        btnPause.setOnClickListener(v -> client.pause());
        btnStop.setOnClickListener(v -> client.stop());
        btnLoop.setOnClickListener(v -> client.toggleLoop());
        btnCamera.setOnClickListener(v -> client.toggleCamera());
        btnCamera.setTextColor(ContextCompat.getColor(this, R.color.camera_toggle));
        btnRetry = findViewById(R.id.btnRetry);
        btnRetry.setOnClickListener(v -> {
            if (cameraOff) {
                openDashcam();
            } else {
                client.rebind();
            }
        });
    }

    /** Only Dashcam in the foreground can start its camera, so send the user there. */
    private void openDashcam() {
        Intent launch = getPackageManager()
                .getLaunchIntentForPackage(DashcamClient.DASHCAM_PACKAGE);
        if (launch != null) {
            startActivity(launch);
        }
    }

    /** Connection line and bottom button: link down, link up with no camera, or all good. */
    private void showLink(boolean connected, boolean noCamera) {
        cameraOff = connected && noCamera;
        if (!connected) {
            connection.setText(R.string.disconnected);
        } else {
            connection.setText(noCamera ? "Connected · camera off" : "Connected to Dashcam");
        }
        connection.setTextColor(ContextCompat.getColor(this,
                !connected ? R.color.text_muted : noCamera ? R.color.accent : R.color.ok));
        btnRetry.setEnabled(!connected || noCamera);
        btnRetry.setText(!connected ? R.string.retry
                : noCamera ? R.string.open_dashcam : R.string.connected);
    }

    private void applyScreenPadding(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int extra = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
            int extraEnd = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE
                    ? (int) TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 28, getResources().getDisplayMetrics())
                    : 0;
            v.setPadding(
                    bars.left + extra,
                    bars.top + extra,
                    bars.right + extra + extraEnd,
                    bars.bottom + extra);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        client.addListener(this);
        client.bind();
    }

    @Override
    protected void onStop() {
        client.removeListener(this);
        client.unbind();
        super.onStop();
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        // Camera state is unknown until Dashcam's first status arrives.
        showLink(connected, false);
        setControlsEnabled(connected);
        if (!connected) {
            // Last-known state is stale once the link drops; never keep showing Recording.
            status.setText(R.string.status);
            timer.setText("--:--");
            btnPlay.setText(R.string.play);
        }
    }

    @Override
    public void onStatus(int state, boolean loopEnabled, long durationMs, String message, boolean frontCamera) {
        String label = switch (state) {
            // Dashcam's message says "Waiting for camera" for the second state.
            case IDashcamControl.STATE_RECORDING,
                    IDashcamControl.STATE_WAITING_FOR_CAMERA -> "Recording";
            case IDashcamControl.STATE_PAUSED -> "Paused";
            case IDashcamControl.STATE_NO_CAMERA -> "Camera off · open Dashcam";
            default -> "Idle";
        };
        boolean noCamera = state == IDashcamControl.STATE_NO_CAMERA;
        boolean recording = state == IDashcamControl.STATE_RECORDING
                || state == IDashcamControl.STATE_WAITING_FOR_CAMERA;
        if (message != null && !message.isEmpty()) {
            label = label + " · " + message;
        }
        status.setText(label);
        showLink(true, noCamera);
        timer.setText(DashcamScreen.formatDuration(durationMs));
        btnLoop.setText(loopEnabled ? "Loop on" : "Loop off");
        btnCamera.setText(frontCamera ? R.string.camera_front : R.string.camera_rear);
        btnCamera.setTextColor(ContextCompat.getColor(this, R.color.camera_toggle));
        btnPlay.setText(state == IDashcamControl.STATE_PAUSED ? R.string.resume : R.string.play);
        btnPlay.setEnabled(!recording && !noCamera);
        btnPause.setEnabled(state == IDashcamControl.STATE_RECORDING);
        btnStop.setEnabled(state != IDashcamControl.STATE_IDLE && !noCamera);
        btnLoop.setEnabled(state == IDashcamControl.STATE_IDLE || noCamera);
        // A switch restarts the clip, which would undo a pause.
        btnCamera.setEnabled(state != IDashcamControl.STATE_PAUSED);
    }

    private void setControlsEnabled(boolean connected) {
        btnPlay.setEnabled(connected);
        btnPause.setEnabled(connected);
        btnStop.setEnabled(connected);
        btnLoop.setEnabled(connected);
        btnCamera.setEnabled(connected);
    }
}

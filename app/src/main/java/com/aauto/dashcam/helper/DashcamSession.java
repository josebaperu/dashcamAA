package com.aauto.dashcam.helper;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

public class DashcamSession extends Session {
    private DashcamClient client;

    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        client = new DashcamClient(getCarContext());
        client.bind();
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                if (client != null) {
                    client.unbind();
                    client = null;
                }
            }
        });
        return new DashcamScreen(getCarContext(), client);
    }
}

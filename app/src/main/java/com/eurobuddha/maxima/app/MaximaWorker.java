package com.eurobuddha.maxima.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * The belt, not the braces. Its only job is to restart the service if the OS
 * killed it and the alarm also failed. 15 minutes is WorkManager's floor.
 */
public final class MaximaWorker extends Worker {

    private static final String NAME = "maxima-keepalive";

    public MaximaWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            if (SeedStore.hasIdentity(getApplicationContext())) {
                MaximaService.start(getApplicationContext());
            }
        } catch (Exception ignored) {
        }
        return Result.success();
    }

    public static void enqueue(Context zContext) {
        try {
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                    MaximaWorker.class, 15, TimeUnit.MINUTES).build();
            WorkManager.getInstance(zContext).enqueueUniquePeriodicWork(
                    NAME, ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Exception ignored) {
        }
    }
}

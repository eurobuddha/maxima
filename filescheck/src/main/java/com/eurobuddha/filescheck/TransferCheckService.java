package com.eurobuddha.filescheck;

/** Mirrors production's foreground service so Android does not freeze the synthetic process. */
public final class TransferCheckService extends android.app.Service {
    @Override public void onCreate(){
        super.onCreate();
        android.app.NotificationManager nm=getSystemService(android.app.NotificationManager.class);
        nm.createNotificationChannel(new android.app.NotificationChannel("file-check","Synthetic file test",android.app.NotificationManager.IMPORTANCE_LOW));
        startForeground(731,new android.app.Notification.Builder(this,"file-check")
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Parlons synthetic transfer test")
                .setContentText("100 MB direct transfer and resume check").setOngoing(true).build());
    }
    @Override public android.os.IBinder onBind(android.content.Intent intent){return null;}
    @Override public int onStartCommand(android.content.Intent intent,int flags,int startId){return START_NOT_STICKY;}
}

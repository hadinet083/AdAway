/*
 * Derived from dns66:
 * Copyright (C) 2016-2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * Derived from AdBuster:
 * Copyright (C) 2016 Daniel Brodie <dbrodie@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */
package org.adaway.vpn;

import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static org.adaway.broadcast.Command.START;
import static org.adaway.broadcast.Command.STOP;
import static org.adaway.broadcast.CommandReceiver.SEND_COMMAND_ACTION;
import static org.adaway.helper.NotificationHelper.VPN_RESUME_SERVICE_NOTIFICATION_ID;
import static org.adaway.helper.NotificationHelper.VPN_RUNNING_SERVICE_NOTIFICATION_ID;
import static org.adaway.helper.NotificationHelper.VPN_SERVICE_NOTIFICATION_CHANNEL;
import static org.adaway.vpn.VpnService.MyHandler.VPN_MSG_STATUS_UPDATE;
import static org.adaway.vpn.VpnStatus.RECONNECTING;
import static org.adaway.vpn.VpnStatus.RUNNING;
import static org.adaway.vpn.VpnStatus.STARTING;
import static org.adaway.vpn.VpnStatus.STOPPED;
import static org.adaway.vpn.VpnStatus.WAITING_FOR_NETWORK;
import static java.util.Objects.requireNonNull;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.adaway.R;
import org.adaway.broadcast.Command;
import org.adaway.broadcast.CommandReceiver;
import org.adaway.helper.PreferenceHelper;
import org.adaway.ui.home.HomeActivity;

import java.lang.ref.WeakReference;

/**
 * This class is the VPN platform service implementation.<br>
 *
 * it is in charge of:
 * <ul>
 * <li>Accepting service commands,</li>
 * <li>Starting / stopping the {@link VpnWorker} thread,</li>
 * <li>Publishing notifications and intent about the VPN state,</li>
 * <li>Reacting to network connectivity changes.</li>
 * </ul>
 */
public class VpnService extends android.net.VpnService {
    public static final int REQUEST_CODE_START = 43;
    public static final int REQUEST_CODE_PAUSE = 42;
    public static final String VPN_UPDATE_STATUS_INTENT = "org.jak_linux.dns66.VPN_UPDATE_STATUS";
    public static final String VPN_UPDATE_STATUS_EXTRA = "VPN_STATUS";
    private static final String TAG = "VpnService";

    private final MyHandler handler;
    private final NetworkCallback networkCallback;
    private final VpnWorker vpnWorker;

    /**
     * Constructor.
     */
    public VpnService() {
        this.handler = new MyHandler(this::handleMessage);
        this.networkCallback = new MyNetworkCallback();
        this.vpnWorker = new VpnWorker(this, this.handler::sendVpnStatusMessage);
    }

    @Override
    public void onCreate() {
        Log.i(TAG, "Creating VPN service.");
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand" + intent);
        switch (Command.readFromIntent(intent)) {
            case START:
                startVpn();
                return START_STICKY;
            case STOP:
                stopVpn();
                return START_NOT_STICKY;
            default:
                Log.w(TAG, "Unknown command: " + intent);
                return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroying VPN service.");
        unregisterNetworkCallback();
        stopVpn();
        Log.i(TAG, "Destroyed VPN service.");
    }

    private boolean handleMessage(Message message) {
        if (message == null) {
            return true;
        }
        if (message.what == VPN_MSG_STATUS_UPDATE) {
            updateVpnStatus(VpnStatus.fromCode(message.arg1));
        }
        return true;
    }

    private void startVpn() {
        PreferenceHelper.setVpnServiceStatus(this, RUNNING);
        updateVpnStatus(STARTING);
        restartWorker();
    }

    private void stopVpn() {
        Log.i(TAG, "Stopping Service");
        PreferenceHelper.setVpnServiceStatus(this, STOPPED);
        stopVpnWorker();
        updateVpnStatus(STOPPED);
        stopSelf();
    }

    private void registerNetworkCallback() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_NOT_VPN)
                .build();
        connectivityManager.registerNetworkCallback(networkRequest, this.networkCallback, this.handler);
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        connectivityManager.unregisterNetworkCallback(this.networkCallback);
    }

    private void updateVpnStatus(VpnStatus status) {
        Notification notification = getNotification(status);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        switch (status) {
            case STARTING:
            case RUNNING:
                notificationManager.cancel(VPN_RESUME_SERVICE_NOTIFICATION_ID);
                startForeground(VPN_RUNNING_SERVICE_NOTIFICATION_ID, notification);
                break;
            default:
                notificationManager.notify(VPN_RESUME_SERVICE_NOTIFICATION_ID, notification);
        }

        // TODO BUG - Nobody is listening to this intent
        // TODO BUG - VpnModel can lister to it to update the MainActivity according its current state
        Intent intent = new Intent(VPN_UPDATE_STATUS_INTENT);
        intent.putExtra(VPN_UPDATE_STATUS_EXTRA, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private Notification getNotification(VpnStatus status) {
        String title = getString(R.string.vpn_notification_title, getString(status.getTextResource()));

        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(getApplicationContext(), 0, intent, FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, VPN_SERVICE_NOTIFICATION_CHANNEL)
                .setPriority(IMPORTANCE_LOW)
                .setContentIntent(contentIntent)
                .setSmallIcon(R.drawable.logo)
                .setColorized(true)
                .setColor(getColor(R.color.notification))
                .setContentTitle(title);
        switch (status) {
            case RUNNING:
                Intent stopIntent = new Intent(this, CommandReceiver.class)
                        .setAction(SEND_COMMAND_ACTION);
                STOP.appendToIntent(stopIntent);
                PendingIntent stopActionIntent = PendingIntent.getBroadcast(this, REQUEST_CODE_PAUSE, stopIntent, FLAG_IMMUTABLE);
                builder.addAction(
                        R.drawable.ic_pause_24dp,
                        getString(R.string.vpn_notification_action_pause),
                        stopActionIntent
                );
                break;
            case STOPPED:
                Intent startIntent = new Intent(this, CommandReceiver.class)
                        .setAction(SEND_COMMAND_ACTION);
                START.appendToIntent(startIntent);
                PendingIntent startActionIntent = PendingIntent.getBroadcast(this, REQUEST_CODE_START, startIntent, FLAG_IMMUTABLE);
                builder.addAction(
                        0,
                        getString(R.string.vpn_notification_action_resume),
                        startActionIntent
                );
                break;
        }
        return builder.build();
    }

    private void restartWorker() {
        this.vpnWorker.stop();
        this.vpnWorker.start();
    }

    private void stopVpnWorker() {
        this.vpnWorker.stop();
    }

    private void waitForNetVpn() {
        stopVpnWorker();
        updateVpnStatus(WAITING_FOR_NETWORK);
    }

    private void reconnect() {
        updateVpnStatus(RECONNECTING);
        restartWorker();
    }

    class MyNetworkCallback extends NetworkCallback {
        @Override
        public void onAvailable(@NonNull Network network) {
            Log.i(TAG, "Network changed to " + network + ", reconnecting...");
            reconnect();
        }

        @Override
        public void onLost(@NonNull Network network) {
            Log.i(TAG, "Connectivity changed to no connectivity, wait for network connection");
            waitForNetVpn();
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            Log.d(TAG, "Network " + network + " capabilities changed :\n" +
                    "- VPN: " + !networkCapabilities.hasCapability(NET_CAPABILITY_NOT_VPN) + "\n" +
                    "- INTERNET: " + networkCapabilities.hasCapability(NET_CAPABILITY_INTERNET) + "\n" +
                    "- VALIDATED: " + networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED));

        }
    }

    /* The handler may only keep a weak reference around, otherwise it leaks */
    static class MyHandler extends Handler {
        static final int VPN_MSG_STATUS_UPDATE = 0;

        private final WeakReference<Callback> callback;

        MyHandler(Callback callback) {
            super(requireNonNull(Looper.myLooper()));
            this.callback = new WeakReference<>(callback);
        }

        void sendVpnStatusMessage(VpnStatus status) {
            Message statusMessage = obtainMessage(VPN_MSG_STATUS_UPDATE, status.toCode(), 0);
            sendMessage(statusMessage);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            Callback callback = this.callback.get();
            if (callback != null) {
                callback.handleMessage(msg);
            }
            super.handleMessage(msg);
        }
    }
}

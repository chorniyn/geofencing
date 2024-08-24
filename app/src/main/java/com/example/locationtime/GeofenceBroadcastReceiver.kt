package com.example.locationtime

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.PendingIntent

class GeofenceBroadcastReceiver: BroadcastReceiver() {
    companion object {
        const val TAG = "Geofences"
    }
    override fun onReceive(context: Context, intent: Intent) {
        val now = ZonedDateTime.now()
        val geofencingEvent = GeofencingEvent.fromIntent(intent)!!
        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes
                .getStatusCodeString(geofencingEvent.errorCode)
            Log.e(TAG, errorMessage)
            return
        }

        // Get the transition type.
        val geofenceTransition = geofencingEvent.geofenceTransition
        val locationTime = geofencingEvent.triggeringLocation?.time?.let {
            ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(it), ZoneId.systemDefault()
            )
        }
        // Test that the reported transition was of interest.
        var enter: Boolean? = null
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.i(TAG, "Enter, notification: $now, location: $locationTime")
            enter = true
        } else if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.i(TAG, "Exit, notification: $now, location: $locationTime")
            enter = false
        }
        enter?.let { e ->
            runBlocking {
                context.dataStore.edit { prefs ->
                    val set = prefs[geolist] ?: emptySet()
                    prefs[geolist] = set + "$e;${now.toInstant().toEpochMilli()};${
                        locationTime?.toInstant()?.toEpochMilli() ?: 0
                    }"
                }
                val name = "Geofence"
                val descriptionText = "Geofence"
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel("geofences", name, importance).apply {
                    description = descriptionText
                }
                // Register the channel with the system.
                val notificationManager: NotificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
                val newIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, newIntent, PendingIntent.FLAG_IMMUTABLE)

                notificationManager.notify(100, Notification.Builder(context, channel.id)
                    .setSmallIcon(R.drawable.ic_launcher_background)
                    .setContentTitle("Geofence " + (if (e) "Enter" else "Exit"))
                    .setContentText("Geofence works")
                    .setContentIntent(pendingIntent)
                    .build())
            }
        }
    }
}
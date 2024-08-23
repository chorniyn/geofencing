package com.example.locationtime

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
            }
        }
    }
}
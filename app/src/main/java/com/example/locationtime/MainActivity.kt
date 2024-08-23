package com.example.locationtime

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.locationtime.GeofenceBroadcastReceiver.Companion.TAG
import com.example.locationtime.ui.theme.LocationTimeTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val USER_PREFERENCES_NAME = "geoprefs"

val Context.dataStore by preferencesDataStore(
    name = USER_PREFERENCES_NAME,
)

val geolist = stringSetPreferencesKey("list")

class MainActivity : ComponentActivity() {
    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocationTimeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val locationPermissions = rememberMultiplePermissionsState(
                        listOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                    if (locationPermissions.allPermissionsGranted) {
                        LocationUpdatesContent(
                            usePreciseLocation = false,
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        Column(modifier = Modifier.padding(innerPadding)) {
                            Text(
                                getTextToShowGivenPermissions(
                                    locationPermissions.revokedPermissions,
                                    locationPermissions.shouldShowRationale
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { locationPermissions.launchMultiplePermissionRequest() }) {
                                Text("Request location permissions")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Immutable
data class LocationUpdate(
    val id: Int,
    val provider: String,
    val locationTime: String,
    val receivedTime: String
)

@Immutable
data class Geofence(
    val enter: Boolean,
    val locationTime: String,
    val receivedTime: String
)

@RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION],
)
@Composable
fun LocationUpdatesContent(usePreciseLocation: Boolean, modifier: Modifier = Modifier) {
    // The location request that defines the location updates
    var locationRequest by remember {
        mutableStateOf<LocationRequest?>(null)
    }
    // Keeps track of received location updates as text
    val locationUpdates = remember {
        mutableStateListOf<LocationUpdate>()
    }

    // Only register the location updates effect when we have a request
    if (locationRequest != null) {
        LocationUpdatesEffect(locationRequest!!) { result ->
            val time = System.currentTimeMillis()
            locationUpdates.addAll(result.locations.mapIndexed { index, location ->
                LocationUpdate(
                    id = locationUpdates.size + index,
                    locationTime = formatTime(location.time),
                    provider = location.provider ?: "N/A",
                    receivedTime = formatTime(time)
                )
            })
        }
    }

    val context = LocalContext.current.applicationContext
    val geofences by produceState(initialValue = listOf(), key1 = context.dataStore.data) {
        context.dataStore.data.collect {
            value = (it[geolist] ?: emptySet()).map { s ->
                val (enter, notification, location) = s.split(';')
                Geofence(
                    enter = enter.toBoolean(),
                    locationTime = formatTime(location.toLong()),
                    receivedTime = formatTime(notification.toLong())
                )
            }.sortedBy { g -> g.receivedTime }
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        item {
            Button(onClick = {
                val geofencingClient = LocationServices.getGeofencingClient(context)
                val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
                val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                geofencingClient.addGeofences(GeofencingRequest.Builder()
                    .addGeofence(
                        Geofence.Builder()
                        // Set the request ID of the geofence. This is a string to identify this
                        // geofence.
                        .setRequestId("home")

                        // Set the circular region of this geofence.
                        .setCircularRegion(
                            49.43586168130942,
                            32.07911368372347,
                            100.0f
                        )

                        // Set the expiration duration of the geofence. This geofence gets automatically
                        // removed after this period of time.
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)

                        // Set the transition types of interest. Alerts are only generated for these
                        // transition. We track entry and exit transitions in this sample.
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)

                        // Create the geofence.
                        .build())
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                    .build(), pi).addOnSuccessListener {
                        Log.i(TAG, "Set up")
                    }.addOnFailureListener {
                    Log.i(TAG, "Failed", it)
                }
            }) {
             Text(text = "Geofences")
            }
        }
        item {
            TableRow(
                provider = "En/Ex",
                received = "Received Time",
                locationTime = "Location Time"
            )
        }
        items(geofences) {
            TableRow(
                provider = if (it.enter) "Enter" else "Exit",
                received = it.receivedTime,
                locationTime = it.locationTime
            )
        }
        item {
            // Toggle to start and stop location updates
            // before asking for periodic location updates,
            // it's good practice to fetch the current location
            // or get the last known location
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Enable location updates")
                Spacer(modifier = Modifier.padding(8.dp))
                Switch(
                    checked = locationRequest != null,
                    onCheckedChange = { checked ->
                        locationRequest = if (checked) {
                            // Define the accuracy based on your needs and granted permissions
                            val priority = if (usePreciseLocation) {
                                Priority.PRIORITY_HIGH_ACCURACY
                            } else {
                                Priority.PRIORITY_BALANCED_POWER_ACCURACY
                            }
                            LocationRequest.Builder(priority, TimeUnit.SECONDS.toMillis(3)).build()
                        } else {
                            null
                        }
                    },
                )
            }
        }
        item {
            TableRow(
                provider = "Provider",
                received = "Received Time",
                locationTime = "Location Time"
            )
        }
        items(locationUpdates, key = { it.id }) {
            TableRow(
                provider = it.provider,
                received = it.receivedTime,
                locationTime = it.locationTime
            )
        }
    }
}

@Composable
fun TableRow(provider: String, received: String, locationTime: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        BorderBox(text = provider, modifier = Modifier.weight(1.1f, fill = true))
        BorderBox(text = received, modifier = Modifier.weight(2f, fill = true))
        BorderBox(text = locationTime, modifier = Modifier.weight(2f, fill = true))
    }
}

@Composable
fun BorderBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(width = 1.dp, color = Color.Black)
            .padding(8.dp)
    ) {
        Text(text, modifier = Modifier.align(Alignment.Center))
    }
}


fun formatTime(time: Long): String {
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault()).format(
        DateTimeFormatter.ISO_LOCAL_TIME
    )
}

/**
 * An effect that request location updates based on the provided request and ensures that the
 * updates are added and removed whenever the composable enters or exists the composition.
 */
@RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION],
)
@Composable
fun LocationUpdatesEffect(
    locationRequest: LocationRequest,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onUpdate: (result: LocationResult) -> Unit,
) {
    val context = LocalContext.current
    val currentOnUpdate by rememberUpdatedState(newValue = onUpdate)

    // Whenever on of these parameters changes, dispose and restart the effect.
    DisposableEffect(locationRequest, lifecycleOwner) {
        val locationClient = LocationServices.getFusedLocationProviderClient(context)
        val locationCallback: LocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                currentOnUpdate(result)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                locationClient.requestLocationUpdates(
                    locationRequest, locationCallback, Looper.getMainLooper(),
                )
            } else if (event == Lifecycle.Event.ON_STOP) {
                locationClient.removeLocationUpdates(locationCallback)
            }
        }

        // Add the observer to the lifecycle
        lifecycleOwner.lifecycle.addObserver(observer)

        // When the effect leaves the Composition, remove the observer
        onDispose {
            locationClient.removeLocationUpdates(locationCallback)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
private fun getTextToShowGivenPermissions(
    permissions: List<PermissionState>,
    shouldShowRationale: Boolean
): String {
    val revokedPermissionsSize = permissions.size
    if (revokedPermissionsSize == 0) return ""

    val textToShow = StringBuilder().apply {
        append("The ")
    }

    for (i in permissions.indices) {
        textToShow.append(permissions[i].permission)
        when {
            revokedPermissionsSize > 1 && i == revokedPermissionsSize - 2 -> {
                textToShow.append(", and ")
            }

            i == revokedPermissionsSize - 1 -> {
                textToShow.append(" ")
            }

            else -> {
                textToShow.append(", ")
            }
        }
    }
    textToShow.append(if (revokedPermissionsSize == 1) "permission is" else "permissions are")
    textToShow.append(
        if (shouldShowRationale) {
            " important. Please grant all of them for the app to function properly."
        } else {
            " denied. The app cannot function without them."
        }
    )
    return textToShow.toString()
}
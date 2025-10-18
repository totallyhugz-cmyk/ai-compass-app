package com.compass.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.math.*

data class Place(val name: String, val lat: Double, val lon: Double, val alt: List<String>)

class MainActivity : ComponentActivity() {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var tts: TextToSpeech? = null
    private var speech: SpeechRecognizer? = null

    private val locReq = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1200L)
        .setMinUpdateIntervalMillis(700L).build()

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled by state */ }

    private fun getPrefs() = getSharedPreferences("ai_compass", MODE_PRIVATE)
    private var orsKey by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fused = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        tts = TextToSpeech(this) { }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speech = SpeechRecognizer.createSpeechRecognizer(this)
        }
        // preload saved key (prefilled with your key if none saved)
        orsKey = getPrefs().getString("ors_key",
            "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6ImE5ZDI2OWYzNjBhMjQ2ODk4ZDI3N2IzNTJhMGM4NjFhIiwiaCI6Im11cm11cjY0In0="
        )

        setContent { AppUI { requestPerms.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        speech?.destroy()
        sensorManager.unregisterListener(rotListener)
        fused.removeLocationUpdates(locCb)
    }

    // ---------- Sensors (heading)
    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var _headingDeg by mutableStateOf<Double?>(null)

    private val rotListener = object : SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            val az = Math.toDegrees(orientation[0].toDouble())
            _headingDeg = (az + 360) % 360
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ---------- Location (GPS)
    private var _latLon by mutableStateOf<Pair<Double,Double>?>(null)
    private val locCb = object : LocationCallback() {
        override fun onLocationResult(res: LocationResult) {
            res.lastLocation?.let { _latLon = it.latitude to it.longitude }
        }
    }

    // ---------- UI state
    private var homeLatLon by mutableStateOf<Pair<Double,Double>?>(null)
    private var routePoints by mutableStateOf<List<Pair<Double,Double>>>(emptyList())
    private var routeMeters by mutableStateOf<Int?>(null)
    private var useRoute by mutableStateOf(true)

    // ---------- Speak helper
    private fun speak(s: String) { tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "compass") }

    // ---------- Haversine + bearing
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0088
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dphi = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dphi/2).pow(2) + cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return 2*R*atan2(sqrt(a), sqrt(1 - a))
    }
    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val x = sin(dl) * cos(p2)
        val y = cos(p1)*sin(p2) - sin(p1)*cos(p2)*cos(dl)
        return (Math.toDegrees(atan2(x, y)) + 360) % 360
    }
    private fun turnDelta(destBearing: Double, deviceHeading: Double) =
        (destBearing - deviceHeading + 360) % 360

    // ---------- ORS routing (walking)
    private fun fetchRoute(start: Pair<Double,Double>, end: Pair<Double,Double>, onDone:(List<Pair<Double,Double>>?, Int?)->Unit) {
        val key = orsKey ?: return onDone(null, null)
        val client = OkHttpClient()
        val url = "https://api.openrouteservice.org/v2/directions/foot-walking"
        val bodyJson = JSONObject().apply {
            put("coordinates", listOf(listOf(start.second, start.first), listOf(end.second, end.first)))
            put("instructions", false)
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", key)
            .post(RequestBody.create(MediaType.parse("application/json"), bodyJson.toString()))
            .build()
        client.newCall(req).enqueue(object: Callback{
            override fun onFailure(call: Call, e: java.io.IOException) { runOnUiThread { onDone(null, null) } }
            override fun onResponse(call: Call, resp: Response) {
                resp.use {
                    if (!it.isSuccessful) return runOnUiThread { onDone(null, null) }
                    val j = JSONObject(it.body()!!.string())
                    val feat = j.getJSONArray("features").getJSONObject(0)
                    val coords = feat.getJSONObject("geometry").getJSONArray("coordinates")
                    val dist = feat.getJSONObject("properties").getJSONObject("summary").getDouble("distance").toInt()
                    val pts = (0 until coords.length()).map { i ->
                        val c = coords.getJSONArray(i); Pair(c.getDouble(1), c.getDouble(0))
                    }
                    runOnUiThread { onDone(pts, dist) }
                }
            }
        })
    }

    // ---------- Nominatim geocode (name -> lat/lon)
    private fun geocodeName(q: String, onDone:(Pair<Double,Double>?) -> Unit) {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&q=" +
                java.net.URLEncoder.encode(q, "UTF-8") + "&limit=1"
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "AICompass/0.4 (contact: demo@example.com)")
            .get().build()
        OkHttpClient().newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { runOnUiThread { onDone(null) } }
            override fun onResponse(call: Call, resp: Response) {
                resp.use {
                    if (!it.isSuccessful) return runOnUiThread { onDone(null) }
                    val arr = JSONArray(it.body()!!.string())
                    if (arr.length() == 0) return runOnUiThread { onDone(null) }
                    val obj = arr.getJSONObject(0)
                    val lat = obj.getString("lat").toDouble()
                    val lon = obj.getString("lon").toDouble()
                    runOnUiThread { onDone(lat to lon) }
                }
            }
        })
    }

    @Composable
    fun AppUI(askPerms: () -> Unit) {
        val locPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val micPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        var query by remember { mutableStateOf(TextFieldValue("")) }
        var target by remember { mutableStateOf<Pair<Double,Double>?>(null) }
        var status by remember { mutableStateOf("Ready") }
        var keyField by remember { mutableStateOf(orsKey ?: "") }

        // Start sensors/location
        LaunchedEffect(locPerm) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
                sensorManager.registerListener(rotListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            if (locPerm) fused.requestLocationUpdates(locReq, locCb, Looper.getMainLooper())
        }

        val my = _latLon
        val heading = _headingDeg
        var delta by remember { mutableStateOf(0f) }
        var distText by remember { mutableStateOf("--") }

        // Update needle and distance continuously
        LaunchedEffect(my, heading, target, routePoints, useRoute) {
            val t = target ?: return@LaunchedEffect
            val me = my ?: return@LaunchedEffect
            val (tLat, tLon) = if (useRoute && routePoints.isNotEmpty()) {
                val idx = routePoints
                    .withIndex()
                    .minByOrNull { (_, p) -> haversineKm(me.first, me.second, p.first, p.second) }?.index ?: 0
                routePoints[min(idx + 3, routePoints.lastIndex)]
            } else t

            val br = bearingDeg(me.first, me.second, tLat, tLon)
            val hd = heading ?: 0.0
            delta = turnDelta(br, hd).toFloat()
            val kmNow = haversineKm(me.first, me.second, tLat, tLon)
            distText = if (useRoute && routeMeters != null) {
                val etaH = (routeMeters!!/1000.0 / 4.5)
                "${routeMeters!!} m · ~${(etaH*60).toInt()} min"
            } else if (kmNow < 1) "${(kmNow*1000).toInt()} m" else String.format("%.2f km", kmNow)

            // Arrive when within ~15m of final destination
            val finalKm = haversineKm(me.first, me.second, t.first, t.second)
            if (finalKm*1000 < 15) speak("You have arrived.")
        }

        fun parseCoordsMaybe(txt: String): Pair<Double,Double>? {
            val re = Regex("""^\s*(-?\d+(\.\d+)?),\s*(-?\d+(\.\d+)?)\s*$""")
            val m = re.matchEntire(txt.trim()) ?: return null
            return m.groupValues[1].toDouble() to m.groupValues[3].toDouble()
        }

        fun goToQuery(q: String) {
            val c = parseCoordsMaybe(q)
            if (c != null) {
                target = c; speak("Navigating to coordinates")
                status = "Navigating to ${c.first}, ${c.second}"
                // Route?
                if (useRoute) {
                    val me = _latLon; val t = target
                    if (me != null && t != null) fetchRoute(me, t) { pts, dist ->
                        routePoints = pts ?: emptyList(); routeMeters = dist
                        if (pts == null) speak("Route unavailable, using direct")
                    }
                }
                return
            }
            // Try geocode online
            geocodeName(q) { res ->
                if (res != null) {
                    target = res; speak("Navigating to $q")
                    status = "Navigating to $q"
                    if (useRoute) {
                        val me = _latLon; val t = target
                        if (me != null && t != null) fetchRoute(me, t) { pts, dist ->
                            routePoints = pts ?: emptyList(); routeMeters = dist
                            if (pts == null) speak("Route unavailable, using direct")
                        }
                    }
                } else {
                    status = "Place not found"; speak("Place not found")
                }
            }
        }

        fun startListening() {
            if (speech == null) { status = "Speech not available"; return }
            if (!micPerm) { askPerms(); return }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            speech?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p0: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(code: Int) { status = "Speech error: $code" }
                override fun onEvent(p0: Int, p1: Bundle?) {}
                override fun onPartialResults(p0: Bundle?) {}
                override fun onResults(b: Bundle) {
                    val said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (said.isNotEmpty()) goToQuery(said) else status = "No speech recognized"
                }
            })
            speech?.startListening(intent)
        }

        MaterialTheme {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // ORS key save
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keyField, onValueChange = { keyField = it },
                        label = { Text("OpenRouteService API key") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        orsKey = keyField.trim().ifEmpty { null }
                        getPrefs().edit().putString("ors_key", orsKey).apply()
                        speak("Routing key saved")
                    }) { Text("Save") }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Where to? (name or lat,lon)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (useRoute) "Route (paths)" else "Direct (crow-fly)")
                    Switch(checked = useRoute, onCheckedChange = { useRoute = it })
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                            != PackageManager.PERMISSION_GRANTED) { askPerms(); return@Button }
                        val q = query.text.trim()
                        if (q.isNotEmpty()) goToQuery(q)
                    }) { Text("Go") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { startListening() }) { Text("🎤 Speak") }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { _latLon?.let { homeLatLon = it; speak("Home set") } }) { Text("Set Home") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        homeLatLon?.let {
                            query = TextFieldValue("${it.first},${it.second}")
                            goToQuery("${it.first},${it.second}")
                            speak("Going home")
                        }
                    }) { Text("Go Home") }
                }

                Spacer(Modifier.height(12.dp))
                Text(status)

                Spacer(Modifier.height(24.dp))
                Compass(deltaDeg = (delta), distanceText = distText)
            }
        }
    }
}

@Composable
fun Compass(deltaDeg: Float, distanceText: String) {
    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(240.dp)) {
            drawCircle()
            rotate(degrees = -deltaDeg) {
                val cx = size.width/2; val cy = size.height/2
                drawLine(
                    start = Offset(cx, cy*0.22f),
                    end   = Offset(cx, cy*1.78f),
                    strokeWidth = 12f
                )
            }
        }
        Text(distanceText, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
}

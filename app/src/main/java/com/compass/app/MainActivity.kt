package com.compass.app

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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.*
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var tts: TextToSpeech? = null
    private var speech: SpeechRecognizer? = null

    private val locReq = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1200L)
        .setMinUpdateIntervalMillis(700L).build()
    private val client = OkHttpClient()

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled by state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fused = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        tts = TextToSpeech(this) { }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speech = SpeechRecognizer.createSpeechRecognizer(this)
        }

        setContent {
            AppUI(
                askPerms = {
                    requestPerms.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.RECORD_AUDIO
                    ))
                }
            )
        }
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

    @Composable
    fun AppUI(askPerms: () -> Unit) {
        val locPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val micPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        var query by remember { mutableStateOf(TextFieldValue("")) }
        var target by remember { mutableStateOf<Pair<Double,Double>?>(null) }
        var listening by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Ready") }

        // start sensors/location when permission granted
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

        // update needle and distance
        LaunchedEffect(my, heading, target) {
            val t = target ?: return@LaunchedEffect
            val me = my ?: return@LaunchedEffect
            val br = bearingDeg(me.first, me.second, t.first, t.second)
            val hd = heading ?: 0.0
            delta = turnDelta(br, hd).toFloat()
            val km = haversineKm(me.first, me.second, t.first, t.second)
            distText = if (km < 1) "${(km*1000).toInt()} m" else String.format("%.2f km", km)
            if (km < 0.008) speak("You have arrived.")
        }

        fun goToQuery(q: String) {
            // coords?
            val m = Regex("""^\s*(-?\d+(\.\d+)?),\s*(-?\d+(\.\d+)?)\s*$""").matchEntire(q)
            if (m != null) {
                target = m.groupValues[1].toDouble() to m.groupValues[3].toDouble()
                speak("Navigating to coordinates")
                status = "Navigating to coordinates"
            } else {
                Thread {
                    geocode(q)?.let {
                        runOnUiThread {
                            target = it
                            speak("Navigating to $q")
                            status = "Navigating to $q"
                        }
                    } ?: runOnUiThread { status = "Not found" }
                }.start()
            }
        }

        fun parseDestinationSpoken(text: String): String {
            var t = text.lowercase(Locale.UK).trim()
            t = t.replace(Regex("^(hey|ok|okay)\s+(compass|assistant|buddy)\s*,?\s*"), "")
            t = t.replace(Regex("^(take|navigate|nav|guide|go)\s+(me\s+)?(to|towards)\s+"), "")
            t = t.replace(Regex("^to\s+"), "")
            // split “in/near/at”
            val parts = Regex("\s+(in|near|at|by)\s+").split(t, 2)
            val q = if (parts.size == 2) {
                val poi = parts[0].trim(' ', '.', ',', '"', '\'')
                val area = parts[1].trim(' ', '.', ',', '"', '\'')
                "${poi}, ${area}, London"
            } else t
            return q.replace(Regex("\\bsainsburys\\b"), "Sainsbury's")
                .split(" ").joinToString(" ") { w ->
                    if (w.contains("'")) w.replaceFirstChar { it.uppercase() }
                    else w.replaceFirstChar { it.uppercase() }
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
            listening = true
            status = "Listening…"
            speech?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p0: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(code: Int) { listening = false; status = "Speech error: $code" }
                override fun onEvent(p0: Int, p1: Bundle?) {}
                override fun onPartialResults(p0: Bundle?) {}
                override fun onResults(b: Bundle) {
                    listening = false
                    val results = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val said = results?.firstOrNull()?.trim().orEmpty()
                    if (said.isNotEmpty()) {
                        val q = parseDestinationSpoken(said)
                        query = TextFieldValue(q)
                        speak("Navigating to $q")
                        goToQuery(q)
                    } else status = "No speech recognized"
                }
            })
            speech?.startListening(intent)
        }

        MaterialTheme {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {

                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Where to? (say or type)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        if (!locPerm) { askPerms(); return@Button }
                        val q = query.text.trim()
                        if (q.isNotEmpty()) goToQuery(q)
                    }) { Text("Go") }

                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { if (!micPerm) askPerms() else startListening() }) {
                        Text(if (listening) "Listening…" else "🎤 Speak")
                    }

                    Spacer(Modifier.width(12.dp))
                    if (!locPerm) Button(onClick = askPerms) { Text("Grant location") }
                }

                Spacer(Modifier.height(12.dp))
                Text(status)

                Spacer(Modifier.height(24.dp))
                Compass(deltaDeg = delta, distanceText = distText)
            }
        }
    }

    // ----- utilities
    private fun geocode(q: String): Pair<Double,Double>? {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" +
                URLEncoder.encode(q, "UTF-8")
        val req = Request.Builder().url(url)
            .header("User-Agent", "ai-compass/0.2").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JSONArray(resp.body!!.string())
            if (arr.length()==0) return null
            val o = arr.getJSONObject(0)
            return o.getString("lat").toDouble() to o.getString("lon").toDouble()
        }
    }

    private fun speak(s: String) { tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "compass") }

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

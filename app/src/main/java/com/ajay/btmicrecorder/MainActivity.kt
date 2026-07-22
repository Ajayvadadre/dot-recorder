package com.ajay.btmicrecorder

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/**
 * Audio-only recorder that routes input through a connected Bluetooth
 * headset (HFP/SCO link) instead of the phone's built-in mic — so you
 * can walk away from the phone and it still picks up your voice.
 *
 * Reality check carried over from earlier: Bluetooth SCO audio is
 * narrowband (roughly 8-16kHz, phone-call quality). That's a limit of
 * the Bluetooth HFP profile on standard earphones, not something this
 * app can improve — a dedicated RF lav mic (DJI Mic etc.) uses a
 * completely different radio link to get studio-grade audio.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var recDot: TextView

    private lateinit var audioManager: AudioManager
    private var recorder: MediaRecorder? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var currentUri: android.net.Uri? = null

    private var isRecording = false
    private var scoReceiverRegistered = false
    private var startTimeMs = 0L
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            statusText.text = "READY"
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val scoReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR
            )
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    if (!isRecording) beginRecording()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (isRecording) statusText.text = "BLUETOOTH MIC LOST"
                }
            }
        }
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000
                val mins = elapsed / 60
                val secs = elapsed % 60
                timerText.text = String.format(Locale.US, "%02d:%02d", mins, secs)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.recordButton)
        statusText = findViewById(R.id.statusText)
        timerText = findViewById(R.id.timerText)
        recDot = findViewById(R.id.recDot)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        recordButton.setOnClickListener {
            if (!isRecording) requestBluetoothAudioAndRecord() else stopRecordingFlow()
        }

        if (requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            statusText.text = "READY"
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun requestBluetoothAudioAndRecord() {
        if (!scoReceiverRegistered) {
            registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            scoReceiverRegistered = true
        }

        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Toast.makeText(
                this,
                "No Bluetooth mic detected — connect your earphones first",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        statusText.text = "CONNECTING TO EARPHONES…"
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        // Fallback in case the connected broadcast is slow/missed on some devices:
        recordButton.postDelayed({ if (!isRecording) beginRecording() }, 2500)
    }

    private fun beginRecording() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val name = "DotAudio_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DotRecorder")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri == null) {
            statusText.text = "STORAGE ERROR"
            return
        }
        currentUri = uri
        val pfd = contentResolver.openFileDescriptor(uri, "w") ?: run {
            statusText.text = "STORAGE ERROR"
            return
        }
        outputPfd = pfd

        try {
            recorder = MediaRecorder().apply {
                // VOICE_COMMUNICATION routes correctly through an active SCO link
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96000)
                setAudioSamplingRate(44100)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }
        } catch (e: Exception) {
            statusText.text = "RECORDER ERROR"
            cleanupFailedRecording()
            return
        }

        isRecording = true
        startTimeMs = System.currentTimeMillis()
        statusText.text = "RECORDING (BLUETOOTH MIC)"
        recordButton.text = "STOP"
        blinkRecDot()
        timerHandler.post(timerTick)
    }

    private fun stopRecordingFlow() {
        if (!isRecording) return
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // stop() can throw if recording was too short — file may still be partially valid
        }
        recorder?.release()
        recorder = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentUri?.let {
                val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                contentResolver.update(it, values, null, null)
            }
        }
        outputPfd?.close()
        outputPfd = null

        isRecording = false
        recDot.clearAnimation()
        recDot.alpha = 0f
        recordButton.text = "RECORD"
        statusText.text = "SAVED TO MUSIC/DOTRECORDER"

        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun cleanupFailedRecording() {
        recorder?.release()
        recorder = null
        outputPfd?.close()
        outputPfd = null
        currentUri?.let { contentResolver.delete(it, null, null) }
        currentUri = null
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun blinkRecDot() {
        recDot.alpha = 1f
        val anim = AlphaAnimation(1f, 0.15f).apply {
            duration = 700
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        recDot.startAnimation(anim)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (scoReceiverRegistered) unregisterReceiver(scoReceiver)
        if (isRecording) stopRecordingFlow()
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
    }
}

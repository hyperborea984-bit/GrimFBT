package com.vrcosc.facetrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vrcosc.facetrack.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var oscClient: OscClient? = null
    private var poseTracker: PoseTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var tracking = false

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.ipInput.setText("127.0.0.1")
        binding.portInput.setText("9000")

        binding.startStopButton.setOnClickListener {
            if (tracking) stopTracking() else requestCameraAndStart()
        }
    }

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        binding.statusText.text = "Loading pose model…"
        binding.startStopButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val modelPath = withContext(Dispatchers.IO) {
                    ModelDownloader.ensureModel(applicationContext) { pct ->
                        runOnUiThread { binding.statusText.text = "Downloading model… $pct%" }
                    }
                }

                val host = binding.ipInput.text.toString().ifBlank { "127.0.0.1" }
                val port = binding.portInput.text.toString().toIntOrNull() ?: 9000
                oscClient = OscClient(host, port)

                poseTracker = PoseTracker(
                    context = applicationContext,
                    modelPath = modelPath,
                    osc = oscClient!!,
                    onFpsUpdate = { fps ->
                        runOnUiThread { binding.fpsText.text = "%.1f fps".format(fps) }
                    },
                    onTrackingQuality = { visible, total ->
                        runOnUiThread {
                            binding.qualityText.text = "Tracked joints: $visible / $total"
                        }
                    }
                )

                bindCameraUseCases()

                tracking = true
                binding.statusText.text = "Tracking — sending OSC to $host:$port"
                binding.startStopButton.text = "Stop"
            } catch (e: Exception) {
                binding.statusText.text = "Failed to start: ${e.message}"
            } finally {
                binding.startStopButton.isEnabled = true
            }
        }
    }

    private fun bindCameraUseCases() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, poseTracker!!) }

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA

            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopTracking() {
        tracking = false
        cameraProvider?.unbindAll()
        poseTracker?.close()
        oscClient?.close()
        poseTracker = null
        oscClient = null
        binding.statusText.text = "Stopped"
        binding.startStopButton.text = "Start Tracking"
        binding.fpsText.text = ""
        binding.qualityText.text = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        if (tracking) stopTracking()
        cameraExecutor.shutdown()
    }
}

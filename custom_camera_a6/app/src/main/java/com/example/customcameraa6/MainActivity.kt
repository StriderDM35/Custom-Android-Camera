package com.example.customcameraa6

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraUnavailableException
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.customcameraa6.databinding.ActivityMainBinding
import com.google.common.util.concurrent.ListenableFuture
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraProvider: ProcessCameraProvider
    private var camera: Camera? = null
    private var cameraControl: CameraControl? = null
    private var availableCameras: MutableList<Pair<String, CameraSelector>> = mutableListOf()
    private lateinit var cameraSpinner: Spinner

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        cameraSpinner = viewBinding.cameraSpinner
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }
        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }
        viewBinding.focusRing.visibility = ImageView.VISIBLE

        cameraExecutor = Executors.newSingleThreadExecutor()
    }


    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            // Stop the current recording session.
            curRecording.stop()
            recording = null
            return
        }

        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED)
                {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        Log.e(TAG, "Starting camera now...")

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // ImageCapture use case
            imageCapture = ImageCapture.Builder()
                .build()

            // VideoCapture use case
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Detect available cameras
            detectAvailableCameras()

            // Select back camera as a default
            // DEFAULT_BACK_CAMERA and DEFAULT_FRONT_CAMERA are logical cameras
            // The app interacts with the logical cameras
            // If there are multiple back cameras, those will be physical cameras
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            bindCameraUseCases(cameraSelector, preview)
            // Select back camera as a default
//            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
//
//            // List of cameras on the Xperia 1 iii
////            Camera@1ee61ad[id=0]                         OPEN
////            Camera@6ca3bf[id=4]                          UNKNOWN
////            Camera@814e6e1[id=2]                         UNKNOWN
////            Camera@a15e292[id=3]                         UNKNOWN
////            Camera@9d1625c[id=1]                         UNKNOWN
//
//                    try {
//                // Unbind use cases before rebinding
//                cameraProvider.unbindAll()
//
//                // Bind use cases to camera
//                // Otherwise, it results in a "Not bound to a valid camera" error
//                val camera = cameraProvider.bindToLifecycle(
//                    this, cameraSelector, preview, imageCapture, videoCapture)
//
//                // Store camera control
//                val cameraControl = camera.cameraControl
//                val cameraInfo = camera.cameraInfo
//
//                // Set tap-to-focus listener
//                setTapToFocus(cameraControl, cameraInfo)

//            } catch(exc: Exception) {
//                Log.e(TAG, "Use case binding failed", exc)
//            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun setTapToFocus(cameraControl: CameraControl, cameraInfo: CameraInfo){
        viewBinding.viewFinder.setOnTouchListener { _, event ->
            // MotionEvent.ACTION_DOWN listens for finger taps
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Uses viewFinder.meteringPointFactory to convert touch coordinates to a MeteringPoint that the camera can understand.
                val factory = viewBinding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)

                // Resets focus after 3 seconds (if unable to acquire focus)
                val action = FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                // Show visual feedback
                showFocusRing(event.x, event.y)

                // Triggers autofocus
                // To get this to work, we need to use a addListener method that takes a Runnable and an Executor as parameters.
                // Inside the Runnable, we can then use future.get() to retrieve the FocusMeteringResult and check its isFocusSuccessful property
                val future: ListenableFuture<FocusMeteringResult> = cameraControl.startFocusAndMetering(action)
                future.addListener({
                    try {
                        val result: FocusMeteringResult = future.get()
                        runOnUiThread {
                            if (result.isFocusSuccessful) {
                                Log.d("FocusMetering", "Focus worked!")
                                updateFocusRingColor(Color.GREEN)
                            } else {
                                Log.d("FocusMetering", "Focus failed!")
                                updateFocusRingColor(Color.RED)
                            }
                            hideFocusRingWithDelay()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Log.e("FocusMetering", "Error during focus", e)
                            updateFocusRingColor(Color.RED)
                            hideFocusRingWithDelay()
                        }
                    }
                }, ContextCompat.getMainExecutor(this))
            }
            return@setOnTouchListener true
        }
    }

    private fun updateFocusRingColor(color: Int) {
        val backgroundDrawable = viewBinding.focusRing.background
        Log.d("TAG", "focusRing background: $backgroundDrawable")

        // Check if the background is a GradientDrawable
        if (backgroundDrawable is GradientDrawable) {
            // Modify the stroke color
            backgroundDrawable.setStroke(1, color) // 1 is the width of the stroke
            viewBinding.focusRing.invalidate() // Redraw the view
        } else {
            Log.d("TAG", "The background is not a GradientDrawable.")
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        // Log.d("FocusRing", "Tap: ($x, $y)")
        //  X and Y should follow the screen dimensions
        // For Xperia 1 iii, max X will be 1080 and max Y will be 2520
        // Top left is (0,0) and bottom right is (1080, 2520)
        // For our layout, x-520 and y-1220 seems to center the focus ring around our tapping point quite nicely
        viewBinding.focusRing.apply {
            translationX = x - 520
            translationY = y - 1220
            visibility = View.VISIBLE
            animate()
                .scaleX(1.5f)
                .scaleY(1.5f)
                .setDuration(300)
                .setListener(null)
                .start()
        }
    }

    // Implement a function to cause ring fade-out after 0.5 seconds
    private fun hideFocusRingWithDelay() {
        // This ensures that the code runs on the UI thread and avoids errors
        Handler(Looper.getMainLooper()).postDelayed({
            // Reset the focus ring color to its initial state (transparent or default)
            updateFocusRingColor(Color.GREEN)
            viewBinding.focusRing.visibility = View.GONE
        }, 1000)
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun detectAvailableCameras() {
        availableCameras.clear()
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

                // For Xperia 1 iii, lensFacing = 1 means rear camera while lensFacing = 0 means front camera
                // Camera IDs range from 0 to 5, but only 0 and 2 have hardware level of 1
                // Camera ID of 1 is actually the front facing camera
                Log.d(TAG, "Camera ID: $cameraId, Lens Facing: $lensFacing, Hardware Level: $hardwareLevel")

                // Remove cameraId '0' because that is the main wide angle camera - cameraId "2" refers to the same camera!
                if (cameraId != "0" && (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED || hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL)) {
                    // Log camera ID and lens facing for debugging
                    Log.d(TAG, "Camera ID: $cameraId, Lens Facing: $lensFacing")
                    // Create CameraSelector using the camera ID
                    val cameraSelector = CameraSelector.Builder().addCameraFilter { cameraInfos ->
                        cameraInfos.filter {
                            Camera2CameraInfo.from(it).cameraId == cameraId
                        }
                    }.build()

                    // Check if the camera is available
                    if (cameraProvider.hasCamera(cameraSelector)) {
                        availableCameras.add(Pair(cameraId, cameraSelector))
                        Log.d(TAG, "Added camera ID: $cameraId")
                    }
                    else {Log.d(TAG, "Camera ID $cameraId not available")}
                }
            }
            populateCameraSpinner()
        } catch (e: CameraInfoUnavailableException) {
            Log.e(TAG, "Camera information unavailable", e)
        } catch (e: CameraUnavailableException) {
            Log.e(TAG, "Camera unavailable", e)
        }
    }

    private fun populateCameraSpinner() {
        val cameraDescriptions = availableCameras.map {
            cameraPair -> val cameraId = cameraPair.first
            when (cameraId) {
                "1" -> "Front Camera"
                "2" -> "24mm: Wide Angle"
                "3" -> "70-105mm: Telephoto"
                "4" -> "16mm: Ultra Wide Angle"
                else -> "Unknown cameraId $cameraId"
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameraDescriptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cameraSpinner.adapter = adapter

        cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedCameraSelector = availableCameras[position]
                // Rebind use cases with the selected camera
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                    }
                bindCameraUseCases(selectedCameraSelector.second, preview)
                // Enable zoom after camera is selected and bound
                enableOpticalZoom(cameraPair.first)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // Do nothing
            }
        }
    }

    private fun bindCameraUseCases(cameraSelector: CameraSelector, preview: Preview) {
        try {
            // Unbind use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera
            // Otherwise, it results in a "Not bound to a valid camera" error
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, videoCapture)

            // Store camera control
            cameraControl = camera?.cameraControl

            // Set tap-to-focus listener
            camera?.cameraInfo?.let { setTapToFocus(cameraControl!!, it) }

        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun enableOpticalZoom(cameraId: String) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            // Check if the camera supports optical zoom (SCALER_AVAILABLE_OPTICAL_ZOOM)
            val opticalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_OPTICAL_ZOOM)

            // Only proceed if optical zoom is supported and if the cameraId is "3"
            if (opticalZoom != null && cameraId == "3") {
                // Get the maximum zoom ratio from the camera characteristics
                val maxOpticalZoom = opticalZoom.toFloat()

                // Add this logic in a UI element, such as SeekBar, for the user to adjust zoom ratio
                val zoomSeekBar: SeekBar = findViewById(R.id.zoomSeekBar) // Assuming you have a SeekBar in your layout
                zoomSeekBar.max = (maxOpticalZoom * 100).toInt()  // Set max zoom based on camera's optical zoom
                zoomSeekBar.progress = 100  // Start at 1x zoom (progress scale of 100)

                zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val zoomRatio = progress / 100f  // Convert SeekBar progress to zoom ratio
                        // Apply the zoom ratio using CameraControl
                        camera?.let { cam ->
                            val cameraControl = cam.cameraControl
                            cameraControl.setZoomRatio(zoomRatio)
                            Log.d(TAG, "Zoom set to $zoomRatio for camera $cameraId")
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            } else {
                Log.d(TAG, "Optical zoom not supported on camera $cameraId or cameraId is not '3'")
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error accessing camera for zoom", e)
        }
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        )
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(
                    baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                startCamera()
                Toast.makeText(baseContext, "Permission request granted!", Toast.LENGTH_SHORT).show()
            }
        }
}

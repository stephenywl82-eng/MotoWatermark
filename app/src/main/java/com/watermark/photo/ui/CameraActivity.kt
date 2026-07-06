package com.watermark.photo.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.watermark.photo.R
import com.watermark.photo.core.WatermarkEngine
import com.watermark.photo.data.ExifParamsExtractor
import com.watermark.photo.data.WatermarkInfo
import com.watermark.photo.databinding.ActivityCameraBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private lateinit var cameraExecutor: ExecutorService
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var targetCaptureSize: Size? = null

    private var currentStyle = 10

    // Lens management
    private var lensId = 0                     // index into lensList
    private val lensList = mutableListOf<LensInfo>()

    private data class LensInfo(
        val id: Int,              // 0-based index for cycling
        val cameraId: String,     // camera2 id string
        val label: String,        // "Main" / "Ultra" / "Tele" / "Front"
        val focalMm: Float,
        val isFront: Boolean
    )

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentStyle = intent.getIntExtra("style", 10)
        binding.chipStyle.text = "Style $currentStyle"

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            enumerateLenses()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnCapture.setOnClickListener { takePhoto() }

        binding.btnSwitchCamera.setOnClickListener { cycleLens() }

        binding.btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                else -> ImageCapture.FLASH_MODE_AUTO
            }
            imageCapture?.flashMode = flashMode
        }

        binding.btnGallery.setOnClickListener {
            finish()
        }

        // Zoom slider
        binding.zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    camera?.cameraControl?.setLinearZoom(progress / 1000f)
                    binding.zoomLabel.text = "${String.format("%.1f", progress / 10f)}%"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ── Enumerate all available camera lenses ──

    private fun enumerateLenses() {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        var index = 0
        lensList.clear()

        for (id in cameraManager.cameraIdList) {
            val ch = cameraManager.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f

            val label = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_BACK -> {
                    // Use focal length heuristic for back cameras
                    when {
                        focal < 1f -> "Main"
                        focal < 5f -> "Ultra"
                        focal < 12f -> "Main"
                        else -> "Tele"
                    }
                }
                else -> "Unknown"
            }

            lensList.add(
                LensInfo(index, id, label, focal, facing == CameraCharacteristics.LENS_FACING_FRONT)
            )

            index++
        }

        // Sort: back cameras first (ultra→main→tele), then front
        lensList.sortWith(compareBy<LensInfo> { it.isFront }.thenBy { it.focalMm })

        // Re-assign sequential ids
        for (i in lensList.indices) lensList[i] = lensList[i].copy(id = i)

        lensId = lensList.indexOfFirst { !it.isFront && it.label == "Main" }
        if (lensId < 0) lensId = 0

        updateLensLabel()
        startCamera()
    }

    private fun cycleLens() {
        lensId = (lensId + 1) % lensList.size
        updateLensLabel()
        startCamera()
    }

    private fun updateLensLabel() {
        val lens = lensList[lensId]
        binding.lensLabel.text = when (lens.label) {
            "Ultra" -> "0.5x"
            "Main" -> "1x"
            "Tele" -> "${lens.focalMm.roundToInt()}x"
            "Front" -> "Front"
            else -> lens.label
        }
    }

    /** Find the closest supported capture size to 12MP (≈4000×3000) */
    private fun pickTargetCaptureSize(lens: LensInfo): Size? {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val ch = cm.getCameraCharacteristics(lens.cameraId)
        val configMap = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = configMap.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: return null

        val targetMp = 12_000_000L
        val targetAspect = 4f / 3f

        return sizes.minByOrNull { sz ->
            val pixels = sz.width.toLong() * sz.height
            val aspect = sz.width.toFloat() / sz.height
            val mpDiff = Math.abs(pixels - targetMp)
            val aspectPenalty = if (Math.abs(aspect - targetAspect) > 0.1f) mpDiff / 4 else 0L
            mpDiff + aspectPenalty
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val lens = lensList.getOrNull(lensId) ?: return@addListener

            // Pick closest 12MP capture size
            targetCaptureSize = pickTargetCaptureSize(lens)

            // Build CameraSelector from camera2 id
            val selector = CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { info ->
                        Camera2CameraInfo.from(info).cameraId == lens.cameraId
                    }
                }
                .build()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = binding.previewView.surfaceProvider }

            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
            targetCaptureSize?.let { captureBuilder.setTargetResolution(it) }
            imageCapture = captureBuilder.build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture)

                // Configure zoom slider
                val zoomRange = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                if (zoomRange > 1.01f) {
                    binding.zoomSeekBar.max = 1000
                    binding.zoomSeekBar.progress = 0
                    binding.zoomContainer.visibility = android.view.View.VISIBLE
                } else {
                    binding.zoomContainer.visibility = android.view.View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        // Create temp file in cache
        val photoFile = java.io.File(
            cacheDir,
            "moto_cam_${System.currentTimeMillis()}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val jpegBytes = photoFile.readBytes()
                    mainScope.launch {
                        processAndWatermark(jpegBytes)
                        photoFile.delete()
                    }
                }

                override fun onError(ex: ImageCaptureException) {
                    mainScope.launch {
                        Toast.makeText(
                            this@CameraActivity,
                            "Capture failed: ${ex.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private suspend fun processAndWatermark(jpegBytes: ByteArray) {
        binding.progressBar.visibility = android.view.View.VISIBLE

        try {
            val (resultBitmap, params) = withContext(Dispatchers.Default) {
                // 1. Read EXIF orientation and decode
                val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val rawBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    ?: throw IOException("Cannot decode JPEG")

                // 2. Rotate to correct orientation
                val rotated = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(rawBitmap, 90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(rawBitmap, 180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(rawBitmap, 270f)
                    else -> rawBitmap
                }
                if (rotated != rawBitmap) rawBitmap.recycle()

                // 3. Scale down if > 14MP (safety net — shouldn't fire at 12MP target)
                val finalBitmap = if (rotated.width * rotated.height > 14_000_000) {
                    val scale = Math.sqrt(
                        (14_000_000.0 / (rotated.width * rotated.height)).toDouble()
                    ).toFloat()
                    val scaled = Bitmap.createScaledBitmap(
                        rotated,
                        (rotated.width * scale).toInt(),
                        (rotated.height * scale).toInt(),
                        true
                    )
                    if (rotated != scaled) rotated.recycle()
                    scaled
                } else rotated

                // 4. Extract params from EXIF
                val realParams = try {
                    ExifParamsExtractor.extract(finalBitmap) { ByteArrayInputStream(jpegBytes) }
                } catch (e: Exception) {
                    null
                }

                // 5. Build WatermarkInfo
                val leftLogo = BitmapFactory.decodeResource(resources, R.drawable.logo_left)
                val rightLogo = BitmapFactory.decodeResource(resources, R.drawable.logo_right)

                val info = WatermarkInfo(
                    brandLogo = leftLogo,
                    rightLogo = rightLogo,
                    deviceName = realParams?.model ?: "",
                    focalLength = realParams?.focalLength?.takeIf { it.isNotBlank() } ?: "",
                    aperture = realParams?.aperture?.takeIf { it.isNotBlank() } ?: "",
                    shutterSpeed = realParams?.shutterSpeed?.takeIf { it.isNotBlank() } ?: "",
                    iso = realParams?.iso?.takeIf { it.isNotBlank() } ?: "",
                    date = realParams?.dateTaken ?: "",
                    isMotorola = realParams?.isMotorola ?: false,
                    showParams = true,
                    showDate = true,
                    style = currentStyle
                )

                // 6. Render watermark
                val result = WatermarkEngine.render(finalBitmap, info, this@CameraActivity)

                Pair(result, info)
            }

            // 7. Save to gallery
            val photoUri: Uri
            withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "MotoCam_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                }
                photoUri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw IOException("Cannot create MediaStore entry")

                val bos = ByteArrayOutputStream()
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 98, bos)
                contentResolver.openOutputStream(photoUri)?.use { it.write(bos.toByteArray()) }
                    ?: throw IOException("Cannot open output stream")
            }

            withContext(Dispatchers.Main) {
                val resultIntent = Intent().apply {
                    putExtra("captured_uri", photoUri.toString())
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@CameraActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            binding.progressBar.visibility = android.view.View.GONE
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

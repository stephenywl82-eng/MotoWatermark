package com.watermark.photo.ui

import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.watermark.photo.R
import com.watermark.photo.core.WatermarkEngine
import com.watermark.photo.data.ExifParamsExtractor
import com.watermark.photo.data.WatermarkInfo
import com.watermark.photo.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentBitmap: Bitmap? = null
    private var watermarkedBitmap: Bitmap? = null
    private var currentRotation: Int = 0
    private var currentImageBytes: ByteArray? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var pickMedia: ActivityResultLauncher<String>
    private lateinit var pickMultipleMedia: ActivityResultLauncher<Array<String>>
    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    private lateinit var requestCameraPermission: ActivityResultLauncher<String>
    private var rawPhotoUri: Uri? = null
    private var rawPhotoPath: String? = null
    private var showParams = true
    private var showDate = true
    private var richSaturation = false
    private var originalColor = true
    private var barbiePink = false
    private var currentStyle = 1
    private var manualPickColor: Int? = null
    private var useWhiteText: Boolean? = null
    private var useWhiteBg = false
    private var ownerName = ""
    private var showLogo = true
    private var usePng = true
    private var isPickMode = false             // 鏄惁澶勪簬鍙栬壊妯″紡
    /** ChipGroup 瀛怌hip鍒楄〃 */
    private fun android.view.ViewGroup.chipChildren() =
        (0 until childCount).mapNotNull { getChildAt(it) as? com.google.android.material.chip.Chip }

    private var pickChipId = View.generateViewId()  // 鍔ㄦ€佸彇鑹睠hip ID
    private lateinit var prefs: SharedPreferences

    /** Decode image with inSampleSize to avoid OOM on large photos */
    private fun decodeToMaxMp(bytes: ByteArray, maxMP: Int = 50): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val maxPixels = maxMP * 1_000_000L
        val srcPixels = opts.outWidth.toLong() * opts.outHeight.toLong()
        if (srcPixels <= maxPixels) {
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw IOException("Cannot decode image")
        }
        // Downsample to target size
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / srcPixels).toFloat()
        val targetW = (opts.outWidth * scale).toInt()
        val targetH = (opts.outHeight * scale).toInt()
        // inSampleSize avoids full-res 192MB decode on 50MP photos
        val maxDim = maxOf(opts.outWidth, opts.outHeight)
        var sampleSize = 1
        while (maxDim / sampleSize > 4096) sampleSize *= 2
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sampleSize
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IOException("Cannot decode image")
        val scaled = Bitmap.createScaledBitmap(sampled, targetW, targetH, true)
        sampled.recycle()
        return scaled
    }

    /** v1.1.5: 全屏沉浸，隐藏状态栏与导航栏，可滑动临时唤出 */
    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableImmersiveMode()

        prefs = getSharedPreferences("moto_wm_prefs", MODE_PRIVATE)
        showParams = prefs.getBoolean("show_params", true)
        showDate = prefs.getBoolean("show_date", true)
        showLogo = prefs.getBoolean("show_logo", true)
        usePng = prefs.getBoolean("use_png", true)
        showDate = prefs.getBoolean("show_date", true)
        richSaturation = prefs.getBoolean("rich_sat", false)
        originalColor = prefs.getBoolean("original_color", false)
        barbiePink = prefs.getBoolean("barbie_pink", false)
        currentStyle = prefs.getInt("style", 1)
        ownerName = prefs.getString("owner_name", "") ?: ""
        usePng = prefs.getBoolean("use_png", true)

        // 鎭㈠鎺т欢鐘舵€?        binding.switchParams.isChecked = showParams
        binding.switchDate.isChecked = showDate
        binding.switchLogo.isChecked = showLogo
        // v1.1.5: 非留存模式(2/5/6/7/8/9)一律迁移到 Standard(1)
        if (currentStyle != 1 && currentStyle != 3 && currentStyle != 4 && currentStyle != 10 && currentStyle != 11) {
            currentStyle = 1
            prefs.edit().putInt("style", currentStyle).apply()
        }
        when (currentStyle) {
            3 -> binding.chipStyle3.isChecked = true
            4 -> binding.chipStyle4.isChecked = true
            10 -> binding.chipStyle10.isChecked = true
            11 -> binding.chipStyle11.isChecked = true
            else -> binding.chipStyle1.isChecked = true
        }
        binding.colorChipGroup.visibility = View.VISIBLE
        // 馃敶 鍔ㄦ€佹彃鍏?鍙栬壊" chip
        val pickChip = com.google.android.material.chip.Chip(this).apply {
            id = pickChipId
            text = "\uD83C\uDFA8 Pick"
            isCheckable = true
            isChecked = isPickMode
            chipStrokeWidth = 1f * resources.displayMetrics.density
            setChipStrokeColorResource(com.google.android.material.R.color.m3_chip_stroke_color)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        binding.colorChipGroup.addView(pickChip)

        binding.tvCenterHint.visibility = View.VISIBLE

        pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { loadAndProcessImage(it) }
        }

        pickMultipleMedia = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris: List<Uri>? ->
            if (!uris.isNullOrEmpty()) {
                if (uris.size == 1) {
                    loadAndProcessImage(uris[0])
                } else {
                    batchProcessImages(uris)
                }
            }
        }

        cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && rawPhotoPath != null) {
                val file = java.io.File(rawPhotoPath!!)
                if (file.exists() && file.length() > 0) {
                    val uri = Uri.fromFile(file)
                    loadAndProcessImage(uri)
                }
            }
        }

        requestCameraPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        binding.fabSelect.setOnClickListener {
            pickMedia.launch("image/*")
        }

        binding.fabSelect.setOnLongClickListener {
            pickMultipleMedia.launch(arrayOf("image/*"))
            true
        }

        // MD3 SwitchMaterial for params/date
        binding.switchParams.setOnCheckedChangeListener { _, isChecked ->
            showParams = isChecked
            prefs.edit().putBoolean("show_params", isChecked).apply()
            applyWatermarkAndShow()
        }
        binding.switchDate.setOnCheckedChangeListener { _, isChecked ->
            showDate = isChecked
            prefs.edit().putBoolean("show_date", isChecked).apply()
            applyWatermarkAndShow()
        }

        // PNG / JPEG switch
        binding.switchLogo.setOnCheckedChangeListener { _, isChecked ->
            showLogo = isChecked
            prefs.edit().putBoolean("show_logo", isChecked).apply()
            applyWatermarkAndShow()
        }

        binding.switchPng.setOnCheckedChangeListener { _, isChecked ->
            usePng = isChecked
            prefs.edit().putBoolean("use_png", isChecked).apply()
        }

        // Style chip group
        binding.styleChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentStyle = when {
                checkedIds.contains(R.id.chipStyle1) -> 1
                checkedIds.contains(R.id.chipStyle3) -> 3
                checkedIds.contains(R.id.chipStyle4) -> 4
                checkedIds.contains(R.id.chipStyle10) -> 10
                checkedIds.contains(R.id.chipStyle11) -> 11
                else -> 1
            }
            prefs.edit().putInt("style", currentStyle).apply()
            // 妯″紡2/3闅愯棌棰滆壊妯″紡chip锛屼繚鐣欏彇鑹瞔hip
            val colorModeChips = binding.colorChipGroup.chipChildren()
                .filter { false }
            colorModeChips.forEach { it.visibility = if (currentStyle == 1 || currentStyle == 4 || currentStyle == 5 || currentStyle == 6 || currentStyle == 7 || currentStyle == 8 || currentStyle == 9 || currentStyle == 10 || currentStyle == 11) View.VISIBLE else View.GONE }
            binding.colorChipGroup.visibility = View.VISIBLE
            updateStylePreview()
            applyWatermarkAndShow()
        }

        // Color mode chip group (single selection, allow deselect)
        binding.colorChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            // 濡傛灉閫変腑浜嗗彇鑹茶姱鐗囷紝娓呮帀棰滆壊妯″紡鑺墖
            if (checkedIds.contains(pickChipId)) {
                isPickMode = true
                // 鍙栨秷鍏朵粬棰滆壊妯″紡
                binding.colorChipGroup.chipChildren()
                    .filter { false }
                    .forEach { it.isChecked = false }
            } else if (checkedIds.isEmpty() || checkedIds.all { it == pickChipId }) {
                isPickMode = false
            } else {
                isPickMode = false
                manualPickColor = null
                binding.colorChipGroup.chipChildren()
                    .find { it.id == pickChipId }?.isChecked = false
                richSaturation = false
                originalColor = true
                barbiePink = false
                prefs.edit()
                    .putBoolean("rich_sat", richSaturation)
                    .putBoolean("original_color", originalColor)
                    .putBoolean("barbie_pink", barbiePink)
                    .apply()
                applyWatermarkAndShow()
                return@setOnCheckedStateChangeListener
            }
            richSaturation = false
            originalColor = true
            barbiePink = false
            prefs.edit()
                .putBoolean("rich_sat", richSaturation)
                .putBoolean("original_color", originalColor)
                .putBoolean("barbie_pink", barbiePink)
                .apply()
            applyWatermarkAndShow()
        }

        // White text switch
        binding.switchWhiteText.setOnCheckedChangeListener { _, isChecked ->
            useWhiteText = if (isChecked) true else null
            applyWatermarkAndShow()
        }

        // Owner name input
        binding.etOwnerName.setText(ownerName)
        binding.etOwnerName.doAfterTextChanged { text ->
            ownerName = text?.toString()?.trim() ?: ""
            prefs.edit().putString("owner_name", ownerName).apply()
            if (currentBitmap != null) applyWatermarkAndShow()
        }

        // White background switch
        binding.switchWhiteBg.setOnCheckedChangeListener { _, isChecked ->
            useWhiteBg = isChecked
            // 白底时自动黑字
            useWhiteText = if (isChecked) false else null
            binding.switchWhiteText.isChecked = isChecked
            applyWatermarkAndShow()
        }

        // 馃帹 鎵嬪姩鍙栬壊瑙︽懜鐩戝惉
        binding.ivPreview.setOnTouchListener { view, event ->
            if (!isPickMode) return@setOnTouchListener false
            if (event.action != android.view.MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            val bitmap = currentBitmap ?: return@setOnTouchListener false
            val imageView = view as android.widget.ImageView
            val matrix = imageView.imageMatrix
            val values = FloatArray(9)
            matrix.getValues(values)
            val scaleX = values[android.graphics.Matrix.MSCALE_X]
            val scaleY = values[android.graphics.Matrix.MSCALE_Y]
            val transX = values[android.graphics.Matrix.MTRANS_X]
            val transY = values[android.graphics.Matrix.MTRANS_Y]
            val bmpX = ((event.x - transX) / scaleX).toInt()
            val bmpY = ((event.y - transY) / scaleY).toInt()
            if (bmpX < 0 || bmpY < 0 || bmpX >= bitmap.width || bmpY >= bitmap.height) return@setOnTouchListener false
            manualPickColor = bitmap.getPixel(bmpX, bmpY)
            Toast.makeText(
                this,
                "Pick: #%06X (%d,%d)".format(0xFFFFFF and manualPickColor!!, bmpX, bmpY),
                Toast.LENGTH_SHORT
            ).show()
            applyWatermarkAndShow()
            true
        }

        updateStylePreview()

        binding.fabSave.setOnClickListener {
            saveWatermarkImage()
        }

        binding.fabRotate.setOnClickListener {
            rotateImage()
        }

        // Handle external share
        when {
            intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true ->
                (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { loadAndProcessImage(it) }
            intent?.action == Intent.ACTION_SEND_MULTIPLE && intent.type?.startsWith("image/") == true -> {
                val uris = intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
                    ?.mapNotNull { it as? Uri } ?: emptyList()
                if (uris.isNotEmpty()) batchProcessImages(uris)
            }
        }
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  鎵归噺澶勭悊
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    private fun batchProcessImages(uris: List<Uri>) {
        val total = uris.size
        var completed = 0
        var failed = 0

        mainScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.fabSave.visibility = View.GONE
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = "Batch: 0 / $total"

            for ((index, uri) in uris.withIndex()) {
                try {
                    val (bitmap, bytes) = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = input.readBytes()
                            val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                            val orientation = exif.getAttributeInt(
                                android.media.ExifInterface.TAG_ORIENTATION,
                                android.media.ExifInterface.ORIENTATION_NORMAL
                            )
                            val decoded = decodeToMaxMp(bytes)
                            val rotation = when (orientation) {
                                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                else -> 0f
                            }
                            val rotated = if (rotation != 0f) {
                                val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
                                val bm = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                                decoded.recycle()
                                bm
                            } else decoded
                            Pair(rotated, bytes)
                        } ?: throw IOException("Cannot open image stream")
                    }

                    val watermarked = withContext(Dispatchers.Default) {
                        val info = buildWatermarkInfoFromBytes(bitmap, bytes).copy(
                            style = currentStyle,
                            richSaturation = richSaturation,
                            originalColor = originalColor,
                            barbiePink = barbiePink,
                            manualPickColor = manualPickColor,
                            useWhiteText = useWhiteText
                        )
                        WatermarkEngine.render(bitmap, info, this@MainActivity)
                    }

                    withContext(Dispatchers.IO) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "MotoWM_${System.currentTimeMillis()}_$index${if (usePng) ".png" else ".jpg"}")
                            put(MediaStore.Images.Media.MIME_TYPE, if (usePng) "image/png" else "image/jpeg")
                            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                        }
                        val uriOut = contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                        ) ?: throw IOException("Cannot create MediaStore entry")
                        contentResolver.openOutputStream(uriOut)?.use { output ->
                            watermarked.compress(if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, output)
                        } ?: throw IOException("Cannot open output stream")

                        if (bitmap != watermarked) bitmap.recycle()
                        watermarked.recycle()
                    }

                    completed++
                } catch (e: Exception) {
                    failed++
                    e.printStackTrace()
                }

                val progress = index + 1
                binding.tvStatus.text = "Batch: $progress / $total (OK:$completed Fail:$failed)"
            }

            if (completed > 0) {
                binding.tvStatus.text = "Done! Saved $completed, Failed $failed"
            } else {
                binding.tvStatus.text = "All $total images failed!"
            }

            binding.progressBar.visibility = View.GONE
            Toast.makeText(this@MainActivity, "Done: $completed saved, $failed failed", Toast.LENGTH_LONG).show()
        }
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  鍗曞紶澶勭悊
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    private fun loadAndProcessImage(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.fabSave.visibility = View.GONE
        binding.tvStatus.visibility = View.GONE

        mainScope.launch {
            try {
                val (bitmap, bytes) = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                        val orientation = exif.getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL
                        )
                        val decoded = decodeToMaxMp(bytes)
                        val rotation = when (orientation) {
                            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                        val rotated = if (rotation != 0f) {
                            val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
                            val bm = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                            decoded.recycle()
                            bm
                        } else decoded
                        Pair(rotated, bytes)
                    } ?: throw IOException("Cannot open image stream")
                }
                currentBitmap = bitmap
                currentImageBytes = bytes
                binding.ivPreview.setImageBitmap(bitmap)

                // Show preview, hide logo/hint
                binding.tvCenterHint.visibility = View.GONE
                binding.logoArea.visibility = View.GONE
                binding.previewCard.visibility = View.VISIBLE

                applyWatermarkAndShow()

                binding.fabRotate.visibility = View.VISIBLE
                binding.fabSave.visibility = View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun applyWatermarkAndShow() {
        val bitmap = currentBitmap ?: return
        mainScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            watermarkedBitmap = withContext(Dispatchers.Default) {
                val info = buildWatermarkInfo(bitmap).copy(
                    showParams = showParams,
                    showDate = showDate,
                    showLogo = showLogo,
                    style = currentStyle,
                    richSaturation = richSaturation,
                    originalColor = originalColor,
                    barbiePink = barbiePink,
                    manualPickColor = manualPickColor,
                    useWhiteText = useWhiteText,
                    useWhiteBg = useWhiteBg,
                    ownerName = ownerName
                )
                WatermarkEngine.render(bitmap, info, this@MainActivity)
            }
            binding.ivPreview.setImageBitmap(watermarkedBitmap)
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun rotateImage() {
        val bitmap = currentBitmap ?: return
        currentRotation = (currentRotation + 90) % 360

        mainScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val rotated = withContext(Dispatchers.Default) {
                val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            currentBitmap = rotated
            applyWatermarkAndShow()
        }
    }

    private fun buildWatermarkInfo(source: Bitmap): WatermarkInfo {
        val leftLogo = BitmapFactory.decodeResource(resources, R.drawable.logo_left)
        val rightLogo = BitmapFactory.decodeResource(resources, R.drawable.logo_right)

        val realParams = currentImageBytes?.let { bytes ->
            try {
                ExifParamsExtractor.extract(source) { java.io.ByteArrayInputStream(bytes) }
            } catch (e: Exception) {
                null
            }
        }

        return WatermarkInfo(
            brandLogo = leftLogo,
            rightLogo = rightLogo,
            deviceName = realParams?.model ?: "",
            focalLength = realParams?.focalLength?.takeIf { it.isNotBlank() } ?: "",
            aperture = realParams?.aperture?.takeIf { it.isNotBlank() } ?: "",
            shutterSpeed = realParams?.shutterSpeed?.takeIf { it.isNotBlank() } ?: "",
            iso = realParams?.iso?.takeIf { it.isNotBlank() } ?: "",
            date = realParams?.dateTaken ?: "",
            isMotorola = realParams?.isMotorola ?: false
        )
    }

    private fun buildWatermarkInfoFromBytes(source: Bitmap, imageBytes: ByteArray): WatermarkInfo {
        val leftLogo = BitmapFactory.decodeResource(resources, R.drawable.logo_left)
        val rightLogo = BitmapFactory.decodeResource(resources, R.drawable.logo_right)

        val realParams = try {
            ExifParamsExtractor.extract(source) { java.io.ByteArrayInputStream(imageBytes) }
        } catch (e: Exception) {
            null
        }

        return WatermarkInfo(
            brandLogo = leftLogo,
            rightLogo = rightLogo,
            deviceName = realParams?.model ?: "",
            focalLength = realParams?.focalLength?.takeIf { it.isNotBlank() } ?: "",
            aperture = realParams?.aperture?.takeIf { it.isNotBlank() } ?: "",
            shutterSpeed = realParams?.shutterSpeed?.takeIf { it.isNotBlank() } ?: "",
            iso = realParams?.iso?.takeIf { it.isNotBlank() } ?: "",
            date = realParams?.dateTaken ?: "",
            isMotorola = realParams?.isMotorola ?: false
        )
    }

    private fun launchCamera() {
        val photoFile = java.io.File(cacheDir, "moto_raw_${System.currentTimeMillis()}.jpg")
        photoFile.createNewFile()
        rawPhotoUri = if (android.os.Build.VERSION.SDK_INT >= 24) {
            androidx.core.content.FileProvider.getUriForFile(
                this@MainActivity,
                "${packageName}.fileprovider",
                photoFile
            )
        } else Uri.fromFile(photoFile)
        rawPhotoPath = photoFile.absolutePath

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            setClassName("com.motorola.camera3", "com.motorola.camera.Camera")
            putExtra(MediaStore.EXTRA_OUTPUT, rawPhotoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        cameraLauncher.launch(intent)
    }

    private fun saveWatermarkImage() {
        val bitmap = watermarkedBitmap ?: return
        mainScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "MotoWM_${System.currentTimeMillis()}${if (usePng) ".png" else ".jpg"}")
                        put(MediaStore.Images.Media.MIME_TYPE, if (usePng) "image/png" else "image/jpeg")
                        put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    }
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                } ?: throw IOException("Cannot create MediaStore entry")

                contentResolver.openOutputStream(uri)?.use { output ->
                    bitmap.compress(if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, output)
                } ?: throw IOException("Cannot open output stream")

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateStylePreview() {
        val previewWidth = 320
        val previewHeight = 120
        val previewBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(previewBitmap)

        if (currentStyle == 10) {
            // Camera Info Bar preview — bottom full-width bar
            canvas.drawColor(android.graphics.Color.parseColor("#2a3038"))
            val barT = 88f
            // gradient
            val bgP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(180, 0, 0, 0)
            }
            canvas.drawRect(0f, barT, 320f, 120f, bgP)
            // accent line
            val lineP = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(180, 100, 160, 220)
                strokeWidth = 1.5f
            }
            canvas.drawLine(12f, barT, 308f, barT, lineP)
            // left: logo placeholder + name
            val nl = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(230, 255, 255, 255)
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.LEFT
            }
            canvas.drawText("motorola", 14f, barT + 20f, nl)
            nl.textSize = 11f
            canvas.drawText("ThinkPhone 25", 68f, barT + 20f, nl)
            // right: params + date
            val pr = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(180, 255, 255, 255)
                textSize = 8f
                typeface = android.graphics.Typeface.DEFAULT
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            canvas.drawText("24mm  f/1.6  1/125s  ISO320", 306f, barT + 16f, pr)
            pr.textSize = 6.5f
            pr.alpha = 130
            canvas.drawText("2025-06-16", 306f, barT + 26f, pr)
        } else if (currentStyle == 11) {
            // F1 Logo preview
            canvas.drawColor(android.graphics.Color.parseColor("#2a3038"))
            val barT = 88f
            val bgP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(180, 0, 0, 0)
            }
            canvas.drawRect(0f, barT, 320f, 120f, bgP)
            val lineP = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(180, 255, 180, 50)
                strokeWidth = 1.5f
            }
            canvas.drawLine(12f, barT, 308f, barT, lineP)
            val nl = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(230, 255, 255, 255)
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.LEFT
            }
            canvas.drawText("motorola", 14f, barT + 20f, nl)
            nl.textSize = 11f
            canvas.drawText("ThinkPhone 25", 68f, barT + 20f, nl)
            val pr = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(160, 255, 255, 255)
                textSize = 9f
                typeface = android.graphics.Typeface.DEFAULT
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            canvas.drawText("F1", 306f, barT + 20f, pr)
        } else if (currentStyle == 3) {
            canvas.drawColor(android.graphics.Color.parseColor("#555555"))
            val bgPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#4a6fa5") }
            canvas.drawRect(0f, 0f, 140f, 60f, bgPaint)
            bgPaint.color = android.graphics.Color.parseColor("#6b8e5a")
            canvas.drawRect(140f, 60f, 240f, 120f, bgPaint)
            bgPaint.color = android.graphics.Color.parseColor("#8a7a6b")
            canvas.drawRect(240f, 0f, 320f, 60f, bgPaint)
            val tinyText = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            val tinySub = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(180, 255, 255, 255)
                textSize = 8f
                typeface = android.graphics.Typeface.DEFAULT
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            canvas.drawText("Edge 50 Ultra", 312f, 110f, tinyText)
            if (showParams) canvas.drawText("35mm f/1.8 ISO400", 312f, 119f, tinySub)
            if (showDate) canvas.drawText("2026:05:07", 312f, 127f, tinySub)
        } else if (currentStyle == 4) {
            // v1.1.5 Classic: bottom bar preview
            canvas.drawColor(android.graphics.Color.parseColor("#2a2a2a"))
            val barPaint = android.graphics.Paint().apply { color = android.graphics.Color.argb(225, 18, 18, 18) }
            canvas.drawRect(0f, 84f, 320f, 120f, barPaint)
            val lp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            canvas.drawText("motorola", 12f, 100f, lp)
            lp.textSize = 12f
            canvas.drawText("Edge 50 Ultra", 66f, 100f, lp)
            val rp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(215, 255, 255, 255)
                textSize = 8f
                typeface = android.graphics.Typeface.DEFAULT
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            if (showParams) canvas.drawText("24mm f/1.6 1/125s ISO320", 310f, 96f, rp)
            if (showDate) canvas.drawText("2025-06-16", 310f, 110f, rp)
        } else {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#CC000000")
            }
            canvas.drawRect(0f, 0f, previewWidth.toFloat(), previewHeight.toFloat(), paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 24f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText("Motorola Edge 50 Neo", 20f, 45f, paint)
            paint.textSize = 18f
            paint.typeface = android.graphics.Typeface.DEFAULT
            canvas.drawText("35mm f/1.8 1/120 ISO200", 20f, 75f, paint)
        }

        binding.ivStylePreview.setImageBitmap(previewBitmap)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
        watermarkedBitmap?.recycle()
    }
}





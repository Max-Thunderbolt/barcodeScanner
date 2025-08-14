package com.example.barcodescanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MainActivity : ComponentActivity() {

	private lateinit var previewView: PreviewView
	private lateinit var barcodeText: TextView
	private lateinit var cameraExecutor: ExecutorService

	private val client by lazy { OkHttpClient() }
	private var hasHandledScan: Boolean = false

	private val barcodeOptions by lazy {
		BarcodeScannerOptions.Builder()
			.setBarcodeFormats(
				Barcode.FORMAT_EAN_13,
				Barcode.FORMAT_EAN_8,
				Barcode.FORMAT_UPC_A,
				Barcode.FORMAT_UPC_E,
				Barcode.FORMAT_CODE_128
			)
			.build()
	}
	private val scanner by lazy { BarcodeScanning.getClient(barcodeOptions) }

	private val requestPermission = registerForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { granted ->
		if (granted) startCamera()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContentView(R.layout.barcodescanner)

		previewView = findViewById(R.id.preview_view)
		barcodeText = findViewById(R.id.barcode_text)
		cameraExecutor = Executors.newSingleThreadExecutor()

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
			== PackageManager.PERMISSION_GRANTED
		) {
			startCamera()
		} else {
			requestPermission.launch(Manifest.permission.CAMERA)
		}
	}

	// Start the camera and set up the preview and analysis
	@OptIn(ExperimentalGetImage::class)
	private fun startCamera() {
		val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
		cameraProviderFuture.addListener({
			val cameraProvider = cameraProviderFuture.get()

			// Set up the preview
			val preview = Preview.Builder().build().also {
				it.setSurfaceProvider(previewView.surfaceProvider)
			}

			// Set up the analysis
			val analysis = ImageAnalysis.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.build()

			// Set up the analyzer
			analysis.setAnalyzer(cameraExecutor) { imageProxy ->
				val mediaImage = imageProxy.image
				if (mediaImage == null) {
					imageProxy.close()
					return@setAnalyzer
				}
				val rotation = imageProxy.imageInfo.rotationDegrees
				val image = InputImage.fromMediaImage(mediaImage, rotation)

				if (hasHandledScan) {
					imageProxy.close()
					return@setAnalyzer
				}

				// Scan the image for barcodes
				scanner.process(image)
					.addOnSuccessListener { barcodes ->
						if (barcodes.isNotEmpty()) {
							// Prefer numeric barcodes; ignore QR/text
							val code = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.all(Char::isDigit) } }
								?: barcodes.first().rawValue
							if (!code.isNullOrEmpty()) {
								hasHandledScan = true
								runOnUiThread {
                                    barcodeText.text = code
								}
								fetchOpenFoodFacts(code)
							}
						}
					}
					.addOnFailureListener {
						// no-op
					}
					.addOnCompleteListener {
						imageProxy.close()
					}
			}

			// Bind the camera to the lifecycle
			try {
				cameraProvider.unbindAll()
				cameraProvider.bindToLifecycle(
					this,
					CameraSelector.DEFAULT_BACK_CAMERA,
					preview,
					analysis
				)
			} catch (e: Exception) {
				// no-op
			}
		}, ContextCompat.getMainExecutor(this))
	}

	// Fetch product information from Open Food Facts API
	private fun fetchOpenFoodFacts(barcode: String) {
		lifecycleScope.launch {
			val result = withContext(Dispatchers.IO) {
				val url = "https://world.openfoodfacts.org/api/v2/product/$barcode.json"
				val request = Request.Builder().url(url).get().build()
				try {
					client.newCall(request).execute().use { response ->
						if (!response.isSuccessful) {
							return@withContext "Error ${response.code}"
						}
						val body = response.body?.string().orEmpty()
						if (body.isEmpty()) return@withContext "Empty response"

						val root = JSONObject(body)
						val status = root.optString("status", "")
						val statusInt = root.optInt("status", 0)
						if (status == "success" || statusInt == 1) {
							val product = root.optJSONObject("product")
							val name = product?.optString("product_name").orEmpty()
							val brand = product?.optString("brands").orEmpty()
							val quantity = product?.optString("quantity").orEmpty()
							val disp = listOf(name, brand, quantity).filter { it.isNotBlank() }.joinToString(" • ")
							if (disp.isNotBlank()) disp else "Product found (no name)"
						} else {
							"Not found on Open Food Facts"
						}
					}
				} catch (_: Exception) {
					"Network error"
				}
			}
			barcodeText.text = "$barcode\n$result"
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		cameraExecutor.shutdown()
	}
}
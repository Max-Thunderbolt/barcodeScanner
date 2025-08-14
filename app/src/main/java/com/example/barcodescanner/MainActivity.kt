package com.example.barcodescanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
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
import java.io.File
import org.json.JSONArray

class MainActivity : ComponentActivity() {

	private lateinit var previewView: PreviewView
	private lateinit var barcodeText: TextView
	private lateinit var refreshButton: Button
	private lateinit var viewFilesButton: Button
	private lateinit var exportFilesButton: Button
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
		refreshButton = findViewById(R.id.refresh_button)
		viewFilesButton = findViewById(R.id.view_files_button)
		exportFilesButton = findViewById(R.id.export_files_button)
		cameraExecutor = Executors.newSingleThreadExecutor()

		// Set up refresh button click listener
		refreshButton.setOnClickListener {
			resetScanning()
		}

		// Set up view files button click listener
		viewFilesButton.setOnClickListener {
			showFileContents()
		}

		// Set up export files button click listener
		exportFilesButton.setOnClickListener {
			exportFilesToDesktop()
		}

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
			== PackageManager.PERMISSION_GRANTED
		) {
			startCamera()
		} else {
			requestPermission.launch(Manifest.permission.CAMERA)
		}
	}

	private fun saveApiResponse(barcode: String, apiResponse: String) {
		val filePath = File(filesDir, "api_responses.json")
		if (!filePath.exists()) {
			filePath.createNewFile()
		}
		
		// Create a JSON object with timestamp and the full API response
		val responseData = JSONObject().apply {
			put("barcode", barcode)
			put("timestamp", System.currentTimeMillis())
			put("api_response", JSONObject(apiResponse))
		}
		
		// Read existing content and append new response
		val fileContent = if (filePath.exists()) filePath.readText() else ""
		val jsonArray = if (fileContent.isNotEmpty()) {
			try {
				JSONObject(fileContent).getJSONArray("responses")
			} catch (e: Exception) {
				JSONObject().put("responses", JSONArray()).getJSONArray("responses")
			}
		} else {
			JSONObject().put("responses", JSONArray()).getJSONArray("responses")
		}
		
		jsonArray.put(responseData)
		val finalContent = JSONObject().put("responses", jsonArray).toString(2)
		filePath.writeText(finalContent)
	}

	private fun saveProduct(barcode: String, name: String, brand: String, quantity: String) {
		val filePath = File(filesDir, "products.json")
		if (!filePath.exists()) {
			filePath.createNewFile()
		}
		val product = JSONObject().apply {
			put("barcode", barcode)
			put("name", name)
			put("brand", brand)
			put("quantity", quantity)
		}
		val fileContent = filePath.readText()
		val newContent = fileContent + product.toString()
		filePath.writeText(newContent)
	}

	// Reset scanning state to allow scanning new items
	private fun resetScanning() {
		hasHandledScan = false
		barcodeText.text = "Ready to scan..."
	}

	// Show the contents of saved files
	private fun showFileContents() {
		val apiResponseFile = File(filesDir, "api_responses.json")
		val productsFile = File(filesDir, "products.json")
		
		val apiContent = if (apiResponseFile.exists()) apiResponseFile.readText() else "No API responses saved yet"
		val productsContent = if (productsFile.exists()) productsFile.readText() else "No products saved yet"
		
		val combinedContent = """
			=== API RESPONSES ===
			$apiContent
			
			=== PRODUCTS ===
			$productsContent
		""".trimIndent()
		
		barcodeText.text = combinedContent
	}

	// Export files to external storage (accessible from desktop)
	private fun exportFilesToDesktop() {
		try {
			val apiResponseFile = File(filesDir, "api_responses.json")
			val productsFile = File(filesDir, "products.json")
			
			// Get the Downloads directory
			val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
			if (!downloadsDir.exists()) {
				downloadsDir.mkdirs()
			}
			
			// Copy files to Downloads folder
			if (apiResponseFile.exists()) {
				val destFile = File(downloadsDir, "barcode_scanner_api_responses.json")
				apiResponseFile.copyTo(destFile, overwrite = true)
			}
			
			if (productsFile.exists()) {
				val destFile = File(downloadsDir, "barcode_scanner_products.json")
				productsFile.copyTo(destFile, overwrite = true)
			}
			
			Toast.makeText(this, "Files exported to Downloads folder", Toast.LENGTH_LONG).show()
			barcodeText.text = "Files exported to:\n${downloadsDir.absolutePath}\n\nCheck your Downloads folder!"
			
		} catch (e: Exception) {
			Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
			barcodeText.text = "Export failed: ${e.message}"
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
							val errorResponse = "{\"error\": \"HTTP ${response.code}\", \"barcode\": \"$barcode\"}"
							saveApiResponse(barcode, errorResponse)
							return@withContext "Error ${response.code}"
						}
						val body = response.body?.string().orEmpty()
						if (body.isEmpty()) {
							val emptyResponse = "{\"error\": \"Empty response\", \"barcode\": \"$barcode\"}"
							saveApiResponse(barcode, emptyResponse)
							return@withContext "Empty response"
						}

						// Save the complete API response
						saveApiResponse(barcode, body)

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
				} catch (e: Exception) {
					val errorResponse = "{\"error\": \"Network error: ${e.message}\", \"barcode\": \"$barcode\"}"
					saveApiResponse(barcode, errorResponse)
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
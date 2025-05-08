package com.example.facerecognition;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.example.facerecognition.utils.ImageUtils;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.tensorflow.lite.Interpreter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FaceRecognitionApp";
    private static final String[] CAMERA_PERMISSIONS = {Manifest.permission.CAMERA};
    private static final String READ_IMAGES_PERMISSION =
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    ? Manifest.permission.READ_MEDIA_IMAGES
                    : Manifest.permission.READ_EXTERNAL_STORAGE;
    private static final int CAMERA_REQUEST_CODE = 10;
    private static final int STORAGE_REQUEST_CODE = 11;
    private static final int EMBEDDING_TIMEOUT_MS = 10000;

    private PreviewView previewView;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private Interpreter tfliteInterpreter;
    private int inputImageWidth = 112;
    private int inputImageHeight = 112;
    private int embeddingSize = 128;
    private float[] comparedFaceEmbedding = null;
    private Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    private final ActivityResultLauncher<Intent> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri selectedImageUri = result.getData().getData();
                            if (selectedImageUri != null) {
                                processSelectedImage(selectedImageUri);
                            } else {
                                Toast.makeText(this, "Error selecting image.", Toast.LENGTH_SHORT).show();
                            }

    //                            Intent displayIntent = new Intent(this, DisplayImageActivity.class);
    //                            displayIntent.putExtra(DisplayImageActivity.EXTRA_IMAGE_URI, selectedImageUri.toString());
    //                            startActivity(displayIntent);
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        Button selectImageButton = findViewById(R.id.selectImageButton);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize Face Detector
        FaceDetectorOptions faceDetectorOptions =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .build();
        faceDetector = FaceDetection.getClient(faceDetectorOptions);

        // Load TFLite model
        try {
            tfliteInterpreter = new Interpreter(loadModelFile("mobile_facenet_model.tflite"));
            getInputOutputDetails();
        } catch (IOException e) {
            Toast.makeText(this, "Error loading TFLite model", Toast.LENGTH_LONG).show();
            Log.e("MODEL", "Error loading TFLite model: " + e.getMessage());
            finish();
        }

        // Request permissions
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, CAMERA_PERMISSIONS, CAMERA_REQUEST_CODE);
        }

        selectImageButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, READ_IMAGES_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED) {
                openImageChooser();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{READ_IMAGES_PERMISSION}, STORAGE_REQUEST_CODE);
            }
        });

        setupEmbeddingTimeout();
    }

    private void setupEmbeddingTimeout() {
        timeoutRunnable = () -> {
            comparedFaceEmbedding = null;
            Toast.makeText(this, "Comparison embedding timed out.", Toast.LENGTH_SHORT).show();
        };
    }

    private ByteBuffer loadModelFile(String modelPath) throws IOException {
        // Implement your asset loading here
        AssetManager assetManager = getAssets();
        InputStream inputStream = assetManager.open(modelPath);
        int fileLength = inputStream.available(); // Get the size of the input stream
        ByteBuffer modelBuffer = ByteBuffer.allocateDirect(fileLength);
        modelBuffer.order(ByteOrder.nativeOrder());
        byte[] buffer = new byte[fileLength];
        inputStream.read(buffer); // Read all bytes from the input stream
        modelBuffer.put(buffer); // Put the bytes into the ByteBuffer
        modelBuffer.rewind();
        inputStream.close();
        return modelBuffer;
    }

    private void getInputOutputDetails() {
        int inputTensorIndex = 0; // Assuming only one input tensor
        int[] inputShape = tfliteInterpreter.getInputTensor(inputTensorIndex).shape();
        inputImageHeight = inputShape[1];
        inputImageWidth = inputShape[2];

        int outputTensorIndex = 0; // Assuming only one output tensor for embeddings
        int[] outputShape = tfliteInterpreter.getOutputTensor(outputTensorIndex).shape();
        embeddingSize = outputShape[1];
    }

    private boolean allPermissionsGranted() {
        for (String permission : CAMERA_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openImageChooser();
            } else {
                Toast.makeText(this, "Storage permission denied.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 860))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy imageProxy) {
                        if (isProcessing.compareAndSet(false, true)) {
                            // If not currently processing, start the process for this frame
                            detectFacesAndRecognize(imageProxy);
                        } else {
                            // If already processing, drop this frame by closing the ImageProxy immediately.
                            // This is crucial to prevent the buffer from overflowing.
                            imageProxy.close();
                        }
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                Toast.makeText(this, "Error starting camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e(TAG, "CameraX setup failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void detectFacesAndRecognize(ImageProxy imageProxy) {
        InputImage inputImage = null;
        try {
            // Get the underlying android.media.Image. This is only valid until imageProxy.close() is called.
            Image mediaImage = imageProxy.getImage();
            if (mediaImage == null) {
                Log.e(TAG, "ImageProxy media image is null.");
                imageProxy.close();
                isProcessing.set(false);
                return;
            }

            inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.getImageInfo().getRotationDegrees()
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to create InputImage", e);
            imageProxy.close();
            isProcessing.set(false);
            return;
        }


        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    try {
                        if (!faces.isEmpty()) {
                            for (Face face : faces) {
                                Bitmap imageBitmap = ImageUtils.imageProxyToBitmap(imageProxy);
                                if(imageBitmap == null) {
                                    imageProxy.close();
                                    isProcessing.set(false);
                                    return;
                                }
                                Bitmap faceBitmap = ImageUtils.cropAndScaleBitmap(
                                        imageBitmap,
                                        face.getBoundingBox(),
                                        inputImageWidth,
                                        inputImageHeight
                                );
                                if (faceBitmap != null) {
                                    float[] currentEmbedding = getFaceEmbedding(faceBitmap);
                                    if (currentEmbedding != null) {
                                        if (comparedFaceEmbedding != null) {
                                            float distance = calculateDistance(currentEmbedding, comparedFaceEmbedding);
                                            Log.d(TAG, "Face distance: " + distance);
                                            if (distance <= 1.0f) { // Adjust threshold as needed
                                                // UI updates must be on the main thread
                                                runOnUiThread(() -> {
                                                    Toast.makeText(MainActivity.this, "Face Matched!", Toast.LENGTH_SHORT).show();
                                                });
                                                comparedFaceEmbedding = null; // Reset after successful match
                                                // Ensure timeoutHandler and timeoutRunnable are managed safely
                                                // if they interact with UI or activity lifecycle
                                                if (timeoutHandler != null && timeoutRunnable != null) {
                                                    timeoutHandler.removeCallbacks(timeoutRunnable);
                                                }
                                            }
                                        }
                                    }
                                    // Recycle faceBitmap if it's no longer needed to free memory
                                    if (!faceBitmap.isRecycled()) {
                                        faceBitmap.recycle();
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Catch any exceptions within the success listener's logic
                        Log.e(TAG, "Error during face recognition logic", e);
                    } finally {
                        imageProxy.close();
                        isProcessing.set(false);
                    }
                })
                .addOnFailureListener(e -> {
                    // Handle errors from ML Kit face detection
                    Log.e(TAG, "Face detection failed", e);
                    imageProxy.close();
                    isProcessing.set(false);
                });
    }

    private void openImageChooser() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        imagePickerLauncher.launch(intent);
    }

    private void processSelectedImage(Uri imageUri) {
        try {
            Bitmap bitmap = ImageUtils.uriToBitmap(this, imageUri);
            if (bitmap != null) {
                InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
                faceDetector.process(inputImage)
                        .addOnSuccessListener(faces -> {
                            if (!faces.isEmpty()) {
                                if (faces.size() > 1) {
                                    Toast.makeText(this, "Multiple faces detected in the selected image. Please select an image with only one face.", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                Face firstFace = faces.get(0);
                                Bitmap croppedFace = ImageUtils.cropAndScaleBitmap(
                                        bitmap,
                                        firstFace.getBoundingBox(),
                                        inputImageWidth,
                                        inputImageHeight
                                );
                                if (croppedFace != null) {
                                    comparedFaceEmbedding = getFaceEmbedding(croppedFace);
                                    if (comparedFaceEmbedding != null) {
                                        Toast.makeText(this, "Face for comparison loaded.", Toast.LENGTH_SHORT).show();
                                        timeoutHandler.postDelayed(timeoutRunnable, EMBEDDING_TIMEOUT_MS);
                                    } else {
                                        Toast.makeText(this, "Error getting embedding from selected image.", Toast.LENGTH_SHORT).show();
                                        comparedFaceEmbedding = null;
                                    }
                                } else {
                                    Toast.makeText(this, "Error cropping face from selected image.", Toast.LENGTH_SHORT).show();
                                    comparedFaceEmbedding = null;
                                }
                            } else {
                                Toast.makeText(this, "No face detected in the selected image.", Toast.LENGTH_SHORT).show();
                                comparedFaceEmbedding = null;
                            }
                        })
                        .addOnFailureListener(e -> {
                            Toast.makeText(this, "Error detecting face in selected image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            comparedFaceEmbedding = null;
                        });
            } else {
                Toast.makeText(this, "Error decoding selected image.", Toast.LENGTH_SHORT).show();
                comparedFaceEmbedding = null;
            }
        } catch (IOException e) {
            Toast.makeText(this, "Error processing selected image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            comparedFaceEmbedding = null;
        }
    }

    private float[] getFaceEmbedding(Bitmap faceBitmap) {
        if (tfliteInterpreter == null) {
            Log.e(TAG, "TFLite interpreter not initialized.");
            return null;
        }
        ByteBuffer inputBuffer = ImageUtils.preprocessImage(faceBitmap, inputImageWidth, inputImageHeight);
        float[][] outputEmbedding = new float[1][embeddingSize];
        Map<Integer, Object> outputMap = new HashMap<>();
        outputMap.put(0, outputEmbedding);
        Object[] inputArray = {inputBuffer};
        try {
            tfliteInterpreter.runForMultipleInputsOutputs(inputArray, outputMap);
            return outputEmbedding[0];
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error running TFLite model: " + e.getMessage());
            return null;
        }
    }

    private float calculateCosineSimilarity(float[] embedding1, float[] embedding2) {
        // Implement your similarity calculation here (e.g., cosine similarity)
        if (embedding1 == null || embedding2 == null || embedding1.length != embedding2.length) {
            return 0.0f; // Or handle error appropriately
        }
        float dotProduct = 0;
        float norm1 = 0;
        float norm2 = 0;
        for (int i = 0; i < embedding1.length; i++) {
            dotProduct += embedding1[i] * embedding2[i];
            norm1 += embedding1[i] * embedding1[i];
            norm2 += embedding2[i] * embedding2[i];
        }
        return (float) (dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2)));
    }

    private float calculateDistance(float[] embedding1, float[] embedding2) {
        if (embedding1 == null || embedding2 == null || embedding1.length != embedding2.length) {
            return -1.0f; // Or handle error appropriately
        }
        float distance = 0;
        for (int i = 0; i < embedding1.length; i++) {
            distance += (embedding1[i] - embedding2[i]) * (embedding1[i] - embedding2[i]);
        }
        return (float) Math.sqrt(distance);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (tfliteInterpreter != null) {
            tfliteInterpreter.close();
        }
        timeoutHandler.removeCallbacks(timeoutRunnable);
    }
}
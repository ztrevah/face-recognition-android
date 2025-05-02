package com.example.facerecognition;
import androidx.appcompat.app.AppCompatActivity;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;
import com.example.facerecognition.utils.ImageUtils;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DisplayImageActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "EXTRA_IMAGE_URI"; // Same key as in MainActivity

    private ImageView imageViewDisplay;
    private ProgressBar progressBarLoading;
    private ExecutorService executorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display_image);

        imageViewDisplay = findViewById(R.id.image_view_display);
        progressBarLoading = findViewById(R.id.progress_bar_loading);

        // Initialize ExecutorService
        executorService = Executors.newSingleThreadExecutor(); // Or Executors.newCachedThreadPool() etc.

        // Get the image Uri from the Intent
        Uri imageUri = null;
        if (getIntent().hasExtra(EXTRA_IMAGE_URI)) {
            String uriString = getIntent().getStringExtra(EXTRA_IMAGE_URI);
            if (uriString != null) {
                imageUri = Uri.parse(uriString);
            }
        }

        if (imageUri != null) {
            // Load and display the image from the Uri using the executor
            loadImage(imageUri);
        } else {
            Toast.makeText(this, "Failed to get image Uri", Toast.LENGTH_SHORT).show();
            // Optionally finish this activity or show an error image
            finish();
        }
    }

    private void loadImage(Uri uri) {
        // Show loading indicator on the UI thread
        runOnUiThread(() -> {
            progressBarLoading.setVisibility(View.VISIBLE);
            imageViewDisplay.setVisibility(View.GONE); // Hide image view while loading
        });

        // Submit the loading task to the background executor
        executorService.submit(() -> {
            // This code runs on a background thread
            Bitmap bitmap = null; // Use the helper function
            try {
                bitmap = ImageUtils.uriToBitmap(this, uri);
            } catch (IOException e) {
                return;
            }

            // Update the UI on the main thread after loading
            Bitmap finalBitmap = bitmap;
            runOnUiThread(() -> {
                progressBarLoading.setVisibility(View.GONE); // Hide loading indicator
                imageViewDisplay.setVisibility(View.VISIBLE); // Show image view

                if (finalBitmap != null) {
                    imageViewDisplay.setImageBitmap(finalBitmap);
                } else {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                    // Optionally set a placeholder image or finish the activity
                    // finish();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Shut down the executor service when the activity is destroyed
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow(); // Attempt to stop executing tasks
        }
    }
}
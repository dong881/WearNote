package com.example.wearnote.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.example.wearnote.R
import java.io.File
import java.io.FileWriter

/**
 * Helper class to guide developers through Google API Console setup
 */
object GoogleApiSetupHelper {

    private const val TAG = "GoogleApiSetupHelper"

    /**
     * Shows a guide for setting up Google API Console
     */
    fun showSetupInstructions(context: Context) {
        try {
            val instructions = """
                # Google API Console Setup Instructions
                
                You're seeing this because your app needs to be registered on Google Cloud Console to use Google Drive API.
                
                ## Setup Steps:
                
                1. Go to https://console.cloud.google.com/
                2. Create a new project or select an existing one
                3. Enable the Google Drive API:
                   - In the side menu, navigate to "APIs & Services" > "Library"
                   - Search for "Google Drive API" and enable it
                4. Create OAuth 2.0 credentials:
                   - Go to "APIs & Services" > "Credentials"
                   - Click "Create Credentials" and select "OAuth client ID"
                   - Select "Android" as the application type
                   - Enter your app's package name: com.example.wearnote
                   - Generate an SHA-1 certificate fingerprint and enter it
                5. Download the credentials and place the google-services.json file in your app directory
                
                For detailed instructions, visit: https://developers.google.com/drive/api/guides/enable-sdk
            """.trimIndent()
            
            // Create a file with instructions in the app's files directory
            val file = File(context.getExternalFilesDir(null), "google_api_setup.md")
            FileWriter(file).use { it.write(instructions) }
            
            // Show a toast with the location of the instructions
            Toast.makeText(
                context,
                "Setup instructions saved to: ${file.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
            
            // Log the instructions
            Log.i(TAG, "Google API Setup Instructions:\n$instructions")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing setup instructions", e)
        }
    }

    /**
     * Opens the Google Cloud Console in a browser
     */
    fun openGoogleCloudConsole(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/"))
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Google Cloud Console", e)
        }
    }
}

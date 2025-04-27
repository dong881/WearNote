package com.example.wearnote

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.wearnote.auth.AuthManager
import com.example.wearnote.viewmodel.DriveViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity {
    private lateinit var driveViewModel: DriveViewModel
    private lateinit var authManager: AuthManager
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        driveViewModel = ViewModelProvider(this)[DriveViewModel::class.java]
        authManager = AuthManager(this)
        
        // Set up observers
        driveViewModel.isAuthorized.observe(this) { isAuthorized ->
            if (isAuthorized) {
                driveViewModel.refreshNotesList()
            } else {
                showLoginPrompt()
            }
        }

        // Handle auth redirect if coming from browser
        if (intent.hasExtra("auth_success")) {
            val success = intent.getBooleanExtra("auth_success", false)
            if (success) {
                driveViewModel.checkAuthStatus()
                showToast("Successfully logged in")
            } else {
                showToast("Login failed")
            }
        }
    }

    private fun showLoginPrompt() {
        // Implement login UI for Wear OS
        // This could be a simple button or dialog
        startAuth()
    }

    private fun startAuth() {
        // Get the sign-in client and start the sign-in flow
        val googleSignInClient = authManager.getGoogleSignInClient()
        startActivityForResult(googleSignInClient.signInIntent, AuthManager.RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == AuthManager.RC_SIGN_IN) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                
                // Handle successful sign-in
                val authCode = account?.serverAuthCode
                if (authCode != null) {
                    lifecycleScope.launch {
                        val success = authManager.handleAuthorizationResponse(authCode)
                        if (success) {
                            driveViewModel.checkAuthStatus()
                            showToast("Successfully logged in")
                        } else {
                            showToast("Login failed")
                        }
                    }
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Google Sign-In Failed: ${e.statusCode} - ${e.message}")
                Log.e(TAG, "Developer error (${e.statusCode}) - Check CLIENT_ID or SHA-1 fingerprint configuration")
                showToast("Login failed: ${e.statusCode}")
            }
        }
    }

    private fun showToast(message: String) {
        // Implement a method to show toast messages on Wear OS
        // For example, using a custom toast or a small overlay text
    }
}
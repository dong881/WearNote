package com.example.wearnote

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var driveViewModel: DriveViewModel
    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        driveViewModel = ViewModelProvider(this)[DriveViewModel::class.java]
        authManager = AuthManager(this)

        driveViewModel.isAuthorized.observe(this) { isAuthorized ->
            if (isAuthorized) {
                driveViewModel.refreshNotesList()
            } else {
                showLoginPrompt()
            }
        }

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
        // Implement a login UI suitable for Wear OS
    }

    private fun startAuth() {
        authManager.startAuthorizationFlow(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == AuthManager.RC_SIGN_IN) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)

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
                Log.e("MainActivity", "Google sign-in failed", e)
                showToast("Login failed: ${e.statusCode}")
            }
        }
    }

    private fun showToast(message: String) {
        // Implement a method to show toast messages
    }
}
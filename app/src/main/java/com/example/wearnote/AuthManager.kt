package com.example.wearnote

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

class AuthManager(private val context: Context) {

    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        const val RC_SIGN_IN = 9001
    }

    fun configureGoogleSignIn(gso: GoogleSignInOptions) {
        googleSignInClient = GoogleSignIn.getClient(context, gso)
    }
    
    // Initialize with default configuration if not explicitly configured
    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.android_client_id))
            .requestServerAuthCode(context.getString(R.string.android_client_id))
            .requestEmail()
            .build()
        
        googleSignInClient = GoogleSignIn.getClient(context, gso)
    }

    fun startAuthorizationFlow(activity: ComponentActivity) {
        val signInIntent = googleSignInClient.signInIntent
        activity.startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    suspend fun handleAuthorizationResponse(authCode: String): Boolean {
        // Your existing code to handle the authorization response
        return true  // Replace with your actual implementation
    }
}
package com.example.wearnote.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.wearnote.MainActivity
import com.example.wearnote.R
import com.example.wearnote.utils.PreferenceManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

class DriveAuthActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "DriveAuthActivity"
        private const val RC_SIGN_IN = 9001
    }
    
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var preferenceManager: PreferenceManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drive_auth)
        
        preferenceManager = PreferenceManager(this)
        
        // Configure sign-in to request the user's ID, email address, and basic profile
        // and Drive scope for file uploads
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
            
        // Build a GoogleSignInClient with the options specified by gso
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        // Start the sign-in flow immediately
        signIn()
    }
    
    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == RC_SIGN_IN) {
            // The Task returned from this call is always completed, no need to attach a listener
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                // Signed in successfully
                handleSignInSuccess(account)
            } catch (e: ApiException) {
                // Sign in failed
                Log.w(TAG, "signInResult:failed code=" + e.statusCode)
                Toast.makeText(this, "Google Sign In failed: ${e.message}", Toast.LENGTH_SHORT).show()
                handleSignInFailure()
            }
        }
    }
    
    private fun handleSignInSuccess(account: GoogleSignInAccount) {
        Log.d(TAG, "signInResult:success")
        Toast.makeText(this, "Google Sign In successful", Toast.LENGTH_SHORT).show()
        
        // Save that credentials are set up
        preferenceManager.setDriveCredentialsSetup(true)
        
        // Return to MainActivity
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
    
    private fun handleSignInFailure() {
        // Save that credentials are not set up
        preferenceManager.setDriveCredentialsSetup(false)
        
        // Return to MainActivity
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
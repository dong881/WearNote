package com.example.wearnote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

class GoogleDriveAuthActivity : ComponentActivity() {
    private lateinit var googleSignInClient: GoogleSignInClient
    private companion object {
        private const val TAG = "GoogleDriveAuthActivity"
        private const val RC_SIGN_IN = 9001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure sign-in to request Drive scope
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        
        // Build a GoogleSignInClient with the options
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        
        setContent {
            AuthScreen()
        }
        
        // Check existing permissions
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))) {
            // Already have permissions
            Log.d(TAG, "Already have Drive permissions")
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        
        // Start sign-in flow
        startSignIn()
    }
    
    @Composable
    fun AuthScreen() {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Requesting Google Drive access",
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Button(
                onClick = { startSignIn() },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = androidx.compose.ui.graphics.Color(0xFF4285F4)
                )
            ) {
                Text("Sign In")
            }
        }
    }
    
    private fun startSignIn() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                Log.d(TAG, "signInResult:success ${account?.email}")
                setResult(Activity.RESULT_OK)
            } catch (e: ApiException) {
                Log.w(TAG, "signInResult:failed code=${e.statusCode}")
                setResult(Activity.RESULT_CANCELED)
            }
            finish()
        }
    }
}

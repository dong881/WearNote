package com.example.wearnote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.api.services.drive.DriveScopes

class GoogleSignInActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private val isLoading = mutableStateOf(false)
    
    companion object {
        private const val TAG = "GoogleSignInActivity"
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
            SignInScreen()
        }
        
        // Check if already signed in
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))) {
            // Already have permissions
            Log.d(TAG, "Already have Drive permissions")
            returnResult(account)
        }
    }
    
    @Composable
    fun SignInScreen() {
        val loading = remember { isLoading }
        
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (loading.value) {
                CircularProgressIndicator(
                    modifier = Modifier.size(50.dp),
                    strokeWidth = 4.dp
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Google Drive Access",
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                    
                    Button(
                        onClick = { startSignIn() },
                        modifier = Modifier
                            .padding(8.dp)
                            .size(80.dp)
                            .clip(CircleShape),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF4285F4) // Google blue
                        )
                    ) {
                        Text(
                            text = "Sign In",
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
    
    private fun startSignIn() {
        isLoading.value = true
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        isLoading.value = false
        
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            handleSignInResult(task)
        }
    }
    
    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            Log.d(TAG, "signInResult:success ${account.email}")
            
            // Check if we have the right scope
            if (GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))) {
                returnResult(account)
            } else {
                // Request additional permissions
                Log.d(TAG, "Requesting Drive permission")
                GoogleSignIn.requestPermissions(
                    this,
                    RC_SIGN_IN,
                    account,
                    Scope(DriveScopes.DRIVE_FILE)
                )
            }
            
        } catch (e: ApiException) {
            Log.w(TAG, "signInResult:failed code=${e.statusCode}")
            Toast.makeText(
                this,
                "Sign-in failed: ${e.statusCode}",
                Toast.LENGTH_SHORT
            ).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }
    
    private fun returnResult(account: GoogleSignInAccount) {
        val resultIntent = Intent().apply {
            putExtra("google_account", account.email)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}

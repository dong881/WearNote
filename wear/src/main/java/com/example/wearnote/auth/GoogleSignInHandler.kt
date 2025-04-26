package com.example.wearnote.auth

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.services.drive.DriveScopes

class GoogleSignInHandler(private val context: Context) {
    
    companion object {
        private const val TAG = "GoogleSignInHandler"
    }
    
    private val googleSignInClient: GoogleSignInClient
    private val driveScope = DriveScopes.DRIVE_FILE
    
    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(driveScope))
            .build()
            
        googleSignInClient = GoogleSignIn.getClient(context, gso)
    }
    
    fun signIn(launcher: ActivityResultLauncher<Intent>) {
        val signInIntent = googleSignInClient.signInIntent
        launcher.launch(signInIntent)
    }
    
    fun signOut() {
        googleSignInClient.signOut()
    }
    
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null
    }
    
    fun isSignedInWithScope(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null && GoogleSignIn.hasPermissions(
            account,
            com.google.android.gms.common.api.Scope(driveScope)
        )
    }
}
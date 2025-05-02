package com.example.wearnote.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.*
import com.example.wearnote.R
import com.example.wearnote.model.PendingUpload
import com.example.wearnote.service.PendingUploadsManager
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.animation.animateColorAsState

private const val TAG = "PendingUploadsScreen"

@Composable
fun PendingUploadsScreen(onBackClick: () -> Unit) {
    // Setup
    val context = LocalContext.current
    val pendingUploads by PendingUploadsManager.pendingUploadsFlow.collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    // Define theme colors
    val primaryColor = Color(0xFF5E35B1) // Deep purple 
    val secondaryColor = Color(0xFF4CAF50) // Green
    val errorColor = Color(0xFFF44336) // Red
    val surfaceColor = Color(0xFF303030) // Dark surface
    val backgroundColor = Color(0xFF121212) // Dark background

    // Force a refresh of the pending uploads list when this screen is displayed
    LaunchedEffect(Unit) {
        PendingUploadsManager.initialize(context)
    }

    // Screen content
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Replace the circular fixed-size container with a full-width container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp), // Reduced padding for more space
            contentAlignment = Alignment.Center
        ) {
            // Main content
            if (pendingUploads.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_check),
                        contentDescription = "No pending uploads",
                        tint = secondaryColor,
                        modifier = Modifier.size(36.dp) // Increased icon size
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "全部同步完成",
                        color = Color.White,
                        fontSize = 18.sp, // Increased font size
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Enhanced Button with animation
                    AnimatedButton(
                        onClick = onBackClick,
                        backgroundColor = primaryColor,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(40.dp) // Larger button
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp) // Larger icon
                            )
                            
                            Spacer(modifier = Modifier.width(4.dp))
                            
                            Text(
                                text = "返回",
                                fontSize = 16.sp // Larger text
                            )
                        }
                    }
                }
            } else {
                // List of pending uploads
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "等待同步的檔案",
                            color = Color.White,
                            fontSize = 18.sp, // Increased size
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    items(pendingUploads) { upload ->
                        PendingUploadItem(
                            pendingUpload = upload,
                            primaryColor = primaryColor,
                            errorColor = errorColor,
                            onRetry = {
                                coroutineScope.launch {
                                    PendingUploadsManager.retryUpload(context, upload)
                                }
                            },
                            onDelete = {
                                PendingUploadsManager.removePendingUpload(context, upload.filePath)
                                // Also delete local file if it exists
                                val file = File(upload.filePath)
                                if (file.exists()) {
                                    file.delete()
                                }
                            }
                        )
                    }
                    
                    item {
                        AnimatedButton(
                            onClick = onBackClick,
                            backgroundColor = primaryColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(40.dp) // Larger button
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_arrow_back),
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp) // Larger icon
                                )
                                
                                Spacer(modifier = Modifier.width(4.dp))
                                
                                Text(
                                    text = "返回",
                                    fontSize = 16.sp // Larger text
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingUploadItem(
    pendingUpload: PendingUpload,
    primaryColor: Color,
    errorColor: Color,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    // Add debug log to track upload issues
    val context = LocalContext.current
    LaunchedEffect(pendingUpload) {
        if (pendingUpload.failureReason != null) {
            Log.d(TAG, "Item showing failed upload: ${pendingUpload.fileName}, reason: ${pendingUpload.failureReason}")
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp), // Reduced vertical padding
        contentColor = Color.White,
        onClick = { /* Card click action */ },
        shape = RoundedCornerShape(8.dp) // Smaller corner radius
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp) // Slightly increased padding
        ) {
            // File name and type
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon based on upload type
                Icon(
                    painter = when(pendingUpload.uploadType) {
                        PendingUpload.UploadType.DRIVE -> painterResource(id = R.drawable.ic_cloud_upload)
                        PendingUpload.UploadType.AI_PROCESSING -> painterResource(id = R.drawable.ic_psychology)
                        PendingUpload.UploadType.BOTH -> painterResource(id = R.drawable.ic_cloud_upload)
                    },
                    contentDescription = "Upload type",
                    tint = primaryColor,
                    modifier = Modifier.size(22.dp) // Increased from 18dp
                )
                
                Spacer(modifier = Modifier.width(4.dp)) // Slightly increased
                
                // Modified text display to show beginning and end with ellipsis in middle
                val filename = pendingUpload.displayName
                val displayText = if (filename.length > 16) {
                    "${filename.take(3)}...${filename.takeLast(7)}"
                } else {
                    filename
                }
                
                Text(
                    text = displayText,
                    color = Color.White,
                    fontSize = 14.sp, // Increased from 12sp
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp), // Increased from 1dp
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Retry button with animation
                AnimatedSmallButton(
                    onClick = onRetry,
                    backgroundColor = Color(0xFF525252),
                    modifier = Modifier.size(30.dp) // Increased from 26dp
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_refresh),
                        contentDescription = "Retry upload",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp) // Increased from 20dp
                    )
                }
                
                // Delete button with animation
                AnimatedSmallButton(
                    onClick = onDelete,
                    backgroundColor = Color(0xFF525252),
                    modifier = Modifier.size(30.dp) // Increased from 26dp
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = "Delete upload",
                        tint = errorColor,
                        modifier = Modifier.size(24.dp) // Increased from 20dp
                    )
                }
            }
        }
    }
}

// Function to trigger vibration with pattern
private fun vibrateDevice(context: Context, durationMs: Long = 20, isClickEffect: Boolean = false) {
    try {
        if (isClickEffect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use a double-pulse pattern for clicks for better feedback
            val pattern = longArrayOf(0, 20, 60, 20)
            val amplitudes = intArrayOf(0, 255, 0, 255)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                val vibrationEffect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
                vibrator.vibrate(vibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                val vibrationEffect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
                vibrator.vibrate(vibrationEffect)
            }
        } else {
            // Use the existing simple vibration for other cases
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            durationMs,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error vibrating device", e)
    }
}

// Use more visible animations for small buttons with haptic feedback
@Composable
fun AnimatedSmallButton(
    onClick: () -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState()
    
    // Track if button was recently clicked for extra animation
    var wasJustClicked by remember { mutableStateOf(false) }
    
    // Extra flash effect after click
    val flashAlpha by animateFloatAsState(
        targetValue = if (wasJustClicked) 0f else if (isPressed.value) 0.6f else 0f,
        animationSpec = tween(
            durationMillis = if (wasJustClicked) 300 else 100,
            easing = if (wasJustClicked) FastOutSlowInEasing else LinearEasing
        )
    )
    
    // More dramatic pulse animation
    val pulseAnim = rememberInfiniteTransition()
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f, // More dramatic pulse
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    // Apply pulse only when pressed with more dramatic scaling
    val finalScale = if (wasJustClicked) 
                        1.2f  // Pop out effect after click
                     else if (isPressed.value) 
                        pulseScale * 0.6f  // More dramatic shrink when pressed
                     else 
                        1f
    
    // Trigger haptic feedback on press
    LaunchedEffect(isPressed.value) {
        if (isPressed.value) {
            vibrateDevice(context, 15) // Shorter, quicker vibration for press
        }
    }
    
    // More dramatic rotation with overshoot
    val rotation by animateFloatAsState(
        targetValue = if (wasJustClicked) 360f else if (isPressed.value) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy, // More bouncy animation
            stiffness = Spring.StiffnessMediumLow
        )
    )
    
    // Flash effect with dramatically different color
    val color by animateColorAsState(
        targetValue = if (wasJustClicked)
                        Color(0xFFE0BBE4) // Light purple flash when released
                      else if (isPressed.value) 
                        Color(0xFFDDDDDD) // Almost white when pressed
                      else 
                        backgroundColor,
        animationSpec = tween(durationMillis = 150) // Faster color change
    )
    
    // Create our own highlight effect 
    val highlightColor = Color.White.copy(alpha = flashAlpha)
    
    // Handle click with animations
    fun handleClick() {
        wasJustClicked = true
        vibrateDevice(context, 40, true) // Pattern vibration for click
        onClick()
        
        // Reset the "just clicked" state after animation completes
        kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.delay(300)
            wasJustClicked = false
        }
    }
    
    Box(
        modifier = modifier
            .scale(finalScale)
            .clip(CircleShape)
            .background(color)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { handleClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Add a highlight overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(highlightColor)
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .graphicsLayer {
                    rotationZ = rotation
                },
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

// Enhanced animation for larger buttons with haptic feedback
@Composable
fun AnimatedButton(
    onClick: () -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState()
    
    // Pulse animation when pressed
    val pulseAnim = rememberInfiniteTransition()
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    
    // Apply pulse only when pressed
    val finalScale = if (isPressed.value) pulseScale * 0.9f else 1f
    
    // Trigger haptic feedback on press
    LaunchedEffect(isPressed.value) {
        if (isPressed.value) {
            vibrateDevice(context)
        }
    }
    
    // Shadow elevation animation
    val elevation by animateFloatAsState(
        targetValue = if (isPressed.value) 1f else 12f,  // More dramatic elevation change
        animationSpec = tween(durationMillis = 100)
    )
    
    // Color animation with glow effect
    val buttonColor by animateColorAsState(
        targetValue = if (isPressed.value) {
            Color(0xFF00BCD4)  // Bright teal/cyan when pressed
        } else {
            backgroundColor
        },
        animationSpec = tween(durationMillis = 100)
    )
    
    // Create our own highlight effect instead of using rememberRipple
    val highlightColor = if (isPressed.value) Color.White.copy(alpha = 0.2f) else Color.Transparent
    
    Box(
        modifier = modifier
            .scale(finalScale)
            .clip(RoundedCornerShape(12.dp))
            .graphicsLayer {
                this.shadowElevation = elevation
                this.shape = RoundedCornerShape(12.dp)
            }
            .background(buttonColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // Don't use default indication
                onClick = {
                    vibrateDevice(context, 40, true)  // Use pattern vibration
                    onClick()
                }
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Add a highlight overlay when pressed
        if (isPressed.value) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(highlightColor)
            )
        }
        
        content()
    }
}

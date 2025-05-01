package com.example.wearnote.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
                    
                    Button(
                        onClick = onBackClick,
                        colors = ButtonDefaults.buttonColors(backgroundColor = primaryColor),
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
                        Button(
                            onClick = onBackClick,
                            colors = ButtonDefaults.buttonColors(backgroundColor = primaryColor),
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
                // Retry button
                SmallButton(
                    onClick = onRetry,
                    modifier = Modifier.size(30.dp) // Increased from 26dp
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_refresh),
                        contentDescription = "Retry upload",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp) // Increased from 20dp
                    )
                }
                
                // Delete button
                SmallButton(
                    onClick = onDelete,
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

@Composable
fun SmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF525252)) // Slightly lighter for better visibility
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp), // Apply padding to the Box instead of using contentPadding
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

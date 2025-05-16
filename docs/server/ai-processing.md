# AI Processing Integration

This document explains how to integrate WearNote with an AI processing server for converting audio recordings to text summaries.

## Overview

WearNote can send recordings to an external AI processing service to convert speech to text and generate summaries. This enables smartwatch users to create readable notes from voice recordings without manual transcription.

## Server Requirements

To integrate with WearNote, your AI processing server should:

1. Accept HTTP POST requests with audio file data
2. Process WAV or MP3 audio files
3. Return results in JSON format
4. Support authentication (API key or OAuth)
5. Handle asynchronous processing if needed

## API Specification

### Endpoint

Your server should provide an endpoint for receiving audio files:

```
POST https://your-ai-service.com/api/process-audio
```

### Headers

```
Content-Type: multipart/form-data
Authorization: Bearer YOUR_API_KEY
```

### Request Body

The request is sent as multipart/form-data with the following parts:

| Parameter | Type | Description |
|-----------|------|-------------|
| `file` | File | The audio file to process |
| `fileName` | String | Original filename of the audio |
| `fileId` | String | Google Drive file ID (optional) |
| `languageCode` | String | Primary language in recording (e.g., "en-US") |
| `options` | JSON | Processing options (see below) |

#### Example Options Object

```json
{
  "summarize": true,
  "transcribe": true,
  "extractKeypoints": true,
  "maxSummaryLength": 500
}
```

### Response Format

The server should respond with JSON in the following format:

```json
{
  "success": true,
  "fileId": "original_file_id_if_provided",
  "results": {
    "transcription": "Full text transcription of the audio...",
    "summary": "Concise summary of the content...",
    "keypoints": [
      "First key point extracted from the content",
      "Second key point extracted from the content",
      "Additional key points..."
    ],
    "confidence": 0.92,
    "processingTimeMs": 3245
  }
}
```

In case of errors:

```json
{
  "success": false,
  "error": {
    "code": "processing_failed",
    "message": "Failed to process audio due to poor audio quality",
    "details": "Additional error details if available"
  }
}
```

## Integration Steps

### 1. Configure AI Service Settings

Add your AI service configuration to WearNote:

```kotlin
object AIServiceConfig {
    const val API_ENDPOINT = "https://your-ai-service.com/api/process-audio"
    const val API_KEY = "your_api_key_here" // Store securely!
    const val DEFAULT_LANGUAGE = "en-US"
    
    val defaultOptions = mapOf(
        "summarize" to true,
        "transcribe" to true,
        "extractKeypoints" to true,
        "maxSummaryLength" to 500
    )
}
```

### 2. Implement the AI Processing Client

WearNote's existing code handles sending recordings to your AI service:

```kotlin
suspend fun processAudioFile(context: Context, filePath: String, driveFileId: String?): AIProcessingResult {
    return withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext AIProcessingResult.Error("File not found")
            }
            
            // Create multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", 
                    file.name,
                    file.asRequestBody("audio/mpeg".toMediaType())
                )
                .addFormDataPart("fileName", file.name)
                .apply {
                    if (driveFileId != null) {
                        addFormDataPart("fileId", driveFileId)
                    }
                }
                .addFormDataPart("languageCode", AIServiceConfig.DEFAULT_LANGUAGE)
                .addFormDataPart("options", JSONObject(AIServiceConfig.defaultOptions).toString())
                .build()
                
            // Create and execute request
            val request = Request.Builder()
                .url(AIServiceConfig.API_ENDPOINT)
                .post(requestBody)
                .header("Authorization", "Bearer ${AIServiceConfig.API_KEY}")
                .build()
                
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
                
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
                val json = JSONObject(responseBody)
                if (json.optBoolean("success", false)) {
                    val results = json.getJSONObject("results")
                    return@withContext AIProcessingResult.Success(
                        transcription = results.optString("transcription", ""),
                        summary = results.optString("summary", ""),
                        keypoints = parseKeypoints(results.optJSONArray("keypoints")),
                        confidence = results.optDouble("confidence", 0.0),
                        processingTimeMs = results.optLong("processingTimeMs", 0)
                    )
                } else {
                    val error = json.optJSONObject("error")
                    val message = error?.optString("message") ?: "Unknown error"
                    return@withContext AIProcessingResult.Error(message)
                }
            } else {
                return@withContext AIProcessingResult.Error("API request failed: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio file", e)
            return@withContext AIProcessingResult.Error("Processing error: ${e.message}")
        }
    }
}
```

### 3. Handle AI Processing Results

After receiving AI processing results, WearNote will:

1. Display a notification with the summary
2. Store results in the app database (optional)
3. Update the Google Drive file with the results (optional)

```kotlin
when (val result = processAudioFile(context, filePath, driveFileId)) {
    is AIProcessingResult.Success -> {
        // Show notification with summary
        showSummaryNotification(context, result.summary, driveFileId)
        
        // Optional: Store results
        storeSummaryInDatabase(driveFileId, result)
        
        // Optional: Update Drive file description
        updateDriveFileWithSummary(context, driveFileId, result.summary)
        
        // Return success
        return true
    }
    is AIProcessingResult.Error -> {
        // Show error notification
        showErrorNotification(context, result.message)
        
        // Add to pending uploads for retry
        PendingUploadsManager.addPendingUpload(
            PendingUpload(
                fileName = File(filePath).name,
                filePath = filePath,
                uploadType = PendingUpload.UploadType.AI_PROCESSING,
                fileId = driveFileId,
                failureReason = result.message
            )
        )
        
        // Return failure
        return false
    }
}
```

## Setting Up Your Own AI Processing Server

You have several options for implementing the AI processing server:

### 1. Using Pre-built Speech-to-Text Services

Implement a server that acts as a middleware between WearNote and services like:
- Google Cloud Speech-to-Text API
- Amazon Transcribe
- Microsoft Azure Speech Services
- OpenAI Whisper API

Example using Google Cloud Speech-to-Text with Node.js:

```javascript
const express = require('express');
const multer = require('multer');
const speech = require('@google-cloud/speech');
const { TextAnalyticsClient } = require('@azure/ai-text-analytics');
const app = express();
const upload = multer({ dest: 'uploads/' });

// Initialize clients
const speechClient = new speech.SpeechClient();
const textAnalyticsClient = new TextAnalyticsClient(
  "https://your-text-analytics-endpoint.com",
  { key: "your-text-analytics-key" }
);

app.post('/api/process-audio', upload.single('file'), async (req, res) => {
  try {
    // Step 1: Transcribe audio using Google Speech-to-Text
    const audioBytes = fs.readFileSync(req.file.path).toString('base64');
    const audio = { content: audioBytes };
    const config = {
      languageCode: req.body.languageCode || 'en-US',
      enableAutomaticPunctuation: true,
    };
    
    const [response] = await speechClient.recognize({ audio, config });
    const transcription = response.results
      .map(result => result.alternatives[0].transcript)
      .join('\n');
      
    // Step 2: Generate summary using Text Analytics
    const summarizationResult = await textAnalyticsClient.extractSummary([{ id: "1", text: transcription }]);
    const summary = summarizationResult[0].sentences.map(s => s.text).join(' ');
    
    // Step 3: Extract key points
    const keyPhraseResult = await textAnalyticsClient.extractKeyPhrases([{ id: "1", text: transcription }]);
    const keypoints = keyPhraseResult[0].keyPhrases;
    
    // Return results
    res.json({
      success: true,
      fileId: req.body.fileId,
      results: {
        transcription,
        summary,
        keypoints,
        confidence: response.results[0].alternatives[0].confidence,
        processingTimeMs: Date.now() - req.startTime
      }
    });
  } catch (error) {
    console.error('Error processing audio:', error);
    res.status(500).json({
      success: false,
      error: {
        code: 'processing_failed',
        message: error.message,
        details: error.details || null
      }
    });
  } finally {
    // Clean up uploaded file
    fs.unlinkSync(req.file.path);
  }
});

app.listen(3000, () => {
  console.log('AI processing server running on port 3000');
});
```

### 2. Using Self-hosted Models

For better privacy and control, you can self-host models:
- [Whisper](https://github.com/openai/whisper) for transcription
- [BART](https://huggingface.co/facebook/bart-large-cnn) or [T5](https://huggingface.co/t5-base) for summarization
- [KeyBERT](https://github.com/MaartenGr/KeyBERT) for keyword extraction

Example Docker Compose setup:

```yaml
version: '3'

services:
  ai-processing-api:
    build: ./api
    ports:
      - "3000:3000"
    volumes:
      - ./uploads:/app/uploads
    environment:
      - MODEL_PATH=/app/models
      - MAX_AUDIO_LENGTH=900
      - MAX_SUMMARY_LENGTH=500
      
  whisper-service:
    image: onerahmet/openai-whisper-asr-webservice:latest
    ports:
      - "9000:9000"
    volumes:
      - ./models:/app/models
    environment:
      - ASR_MODEL=base
      - ASR_ENGINE=openai_whisper
      
  summarization-service:
    build: ./summarization
    ports:
      - "9001:9001"
    volumes:
      - ./models:/app/models
```

## Testing Your Integration

1. **Manual Testing**:
   - Create test audio recordings
   - Send them to your AI service endpoint using tools like Postman
   - Verify response format matches expected structure

2. **Integration Testing**:
   - Modify WearNote to point to your test server
   - Record audio and check if processing works end-to-end
   - Verify handling of various error conditions

3. **Performance Testing**:
   - Test with recordings of different lengths
   - Measure processing time and resource usage
   - Optimize for wearable device constraints

## Troubleshooting

### Common Issues

1. **File Upload Failures**:
   - Check server logs for file size limits
   - Verify network connectivity from device to server
   - Check server CORS configuration

2. **Processing Errors**:
   - Audio quality might be too poor for accurate transcription
   - Language detection may be incorrect
   - Server might have resource constraints

3. **Integration Errors**:
   - Verify API endpoint URL is correct
   - Check authentication headers are properly set
   - Ensure JSON parsing handles all possible response formats

## Security Considerations

1. **API Authentication**:
   - Use strong API keys or OAuth tokens
   - Implement rate limiting to prevent abuse
   - Consider IP whitelisting for additional security

2. **Data Privacy**:
   - Store audio files securely and temporarily
   - Delete files after processing
   - Implement encryption for sensitive content

3. **User Privacy**:
   - Obtain clear user consent for audio processing
   - Provide privacy policy explaining data handling
   - Allow users to opt out of AI processing

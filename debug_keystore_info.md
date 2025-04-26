# Debugging Keystore Information

## Current Error

You're encountering a Google Sign-In error code 10 (DEVELOPER_ERROR) because the SHA-1 fingerprint of your signing certificate doesn't match what's registered in the Google Cloud Console.

## Current Configuration

- **Package Name**: com.example.wearnote
- **Client ID**: 961715726121-lqm36ao22hs3vm7b1vseidh68suft09e.apps.googleusercontent.com
- **SHA-1 in Google Console**: 53:FF:45:CB:0B:68:0C:DA:15:94:C4:00:8D:FC:55:7E:EA:2A:76:B6
- **Current App SHA-1**: DA:39:A3:EE:5E:6B:4B:0D:32:55:BF:EF:95:60:18:90:AF:D8:07:09

## Solutions

### Option 1: Add your development SHA-1 to Google Cloud Console

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Select your project
3. Go to APIs & Services > Credentials
4. Edit your OAuth Client ID
5. Add the SHA-1 fingerprint from your logs: `DA:39:A3:EE:5E:6B:4B:0D:32:55:BF:EF:95:60:18:90:AF:D8:07:09`

### Option 2: Use the registered keystore

If you need to use a specific keystore that matches the SHA-1 registered in Google Cloud:

1. Obtain the keystore file with fingerprint `53:FF:45:CB:0B:68:0C:DA:15:94:C4:00:8D:FC:55:7E:EA:2A:76:B6`
2. Configure your project to use this keystore by adding to your app's build.gradle:

```gradle
android {
    // ...existing config...
    
    signingConfigs {
        debug {
            storeFile file("path/to/your/keystore.jks")
            storePassword "your-store-password"
            keyAlias "your-key-alias"
            keyPassword "your-key-password"
        }
    }
    
    buildTypes {
        debug {
            signingConfig signingConfigs.debug
        }
    }
}
```

## Commands for Generating SHA-1

To get the SHA-1 fingerprint from your keystore:

```bash
keytool -list -v -keystore [path-to-keystore] -alias [alias-name] -storepass [store-password] -keypass [key-password]
```

For the default debug keystore:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Different OS locations for debug.keystore:
- Windows: %USERPROFILE%\.android\debug.keystore
- macOS/Linux: ~/.android/debug.keystore

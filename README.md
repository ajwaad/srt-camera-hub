# SRT Camera Hub

A low-latency, multi-device SRT streaming ecosystem that connects an Android camera directly to OBS Studio via a local relay hub.

## Architecture & Components

The repository is divided into three main components:

### 1. Android Cam (`/android-cam`)
The mobile client that captures camera output and streams it over the local network via the Secure Reliable Transport (SRT) protocol.
* **Tech Stack:** Kotlin, Android SDK, Gradle
* **Building:** Open the folder in Android Studio, sync dependencies, and build the APK.

### 2. Desktop Hub (`/desktop-hub`)
A lightweight Node.js relay server that manages connections, coordinates SRT streams, and provides a local dashboard.
* **Tech Stack:** Node.js, `ws` (WebSockets)
* **Running:**
  ```bash
  cd desktop-hub
  npm install
  npm start
  ```

### 3. OBS Plugin (`/obs-plugin`)
A custom C-based OBS Studio plugin (`obs-srt-source.dll`) designed to ingest the incoming SRT stream with minimal latency.
* **Tech Stack:** C, CMake, OBS Studio Plugin API
* **Building:** Build using CMake (x64 architecture).
* **Installation:** Run `build-scripts/quick-install.bat` as **Administrator** to quickly copy the built DLL to your OBS Studio plugins directory (`C:\Program Files\obs-studio\obs-plugins\64bit\`).

## Quick Start
1. Start the Desktop Hub (`npm start` inside `/desktop-hub`).
2. Build and install the OBS plugin. Open OBS and add the new SRT Source to your scene.
3. Install the Android app and start broadcasting to the Hub's IP address.

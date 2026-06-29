# 🎥 SRT Camera Hub 🚀

Turn your phone into a **high-octane, ultra-low-latency** wireless camera for OBS Studio! No more tripping over cables. 

Welcome to the **SRT Camera Hub** – a multi-device streaming ecosystem that beams crisp video from your Android phone straight into your OBS stream via the Secure Reliable Transport (SRT) protocol. ⚡

---

## 🧩 What's inside the box?

This repo is basically a 3-piece combo meal. Here is what you get:

### 📱 1. Android Cam (`/android-cam`)
Your phone is now a professional camera. This mobile client captures your beautiful face and yeets it over your local network using SRT.
* **Tech Magic:** Kotlin, Android SDK, Gradle
* **How to cook:** Open it in Android Studio, sync up, and build that APK!

### 💻 2. Desktop Hub (`/desktop-hub`)
The traffic controller! A snappy Node.js relay server that catches the video streams from your phone and organizes the chaos with a sleek local dashboard.
* **Tech Magic:** Node.js, WebSockets (`ws`)
* **How to cook:**
  ```bash
  cd desktop-hub
  npm install
  npm start
  ```
  *(Boom, server is running!)*

### 🎬 3. OBS Plugin (`/obs-plugin`)
The grand finale. A custom C-based OBS plugin (`obs-srt-source.dll`) that catches the stream from the Hub and drops it right into your OBS scene faster than you can say "latency".
* **Tech Magic:** C, CMake, OBS Studio Plugin API
* **How to cook:** 
  1. Build with CMake (x64). 
  2. Run `build-scripts/quick-install.bat` as **Administrator**. We'll teleport the DLL directly into your OBS plugins folder. 

---

## 🚀 Quick Start Guide

Want to get streaming ASAP? We got you.

1. **Fire up the Hub:** Run `npm start` inside `/desktop-hub`. 
2. **Inject the OBS Plugin:** Build it and run the install script. Open OBS and add your shiny new **SRT Source** to your scene.
3. **Go Live:** Install the Android app, point it at the Hub's IP address, and hit broadcast!

---
*Happy Streaming! 🎮✨*

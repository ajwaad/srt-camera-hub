# OBS Studio SRT Source Plugin (`obs-srt-source`)

A custom C++ plugin for **OBS Studio** that receives ultra-low-latency video streams via the **Secure Reliable Transport (SRT)** protocol and automatically communicates live Tally state with the Desktop Hub.

---

## 🌟 Key Features

- **SRT Video Receiver**: Receives H.264 video over SRT (listening mode or caller mode).
- **Automated Tally Status API**: Notifies the Desktop Hub when the source becomes active in the OBS Program scene (`ON AIR`) or inactive (`OFF AIR`).
- **Low Latency Options**: Configurable latency buffer (default: 20ms for local LAN).

---

## 📦 Installation

- **Installer (Recommended)**: Run `install-srt-plugin.exe` from the root `releases/` directory.
- **Manual Copy**: Copy `obs-srt-source.dll` into your OBS Studio plugin directory (`C:\Program Files\obs-studio\obs-plugins\64bit\`).

---

## 🔨 Building from Source

### Prerequisites
- Windows 10/11 x64
- Visual Studio 2022 (with C++ Desktop workload)
- CMake 3.28+

### Build Commands
```bash
cmake -B build_x64 -S .
cmake --build build_x64 --config RelWithDebInfo
```

Compiled DLL output: `build_x64/RelWithDebInfo/obs-srt-source.dll`.


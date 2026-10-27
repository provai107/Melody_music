# JioSaavn API Backend Setup Guide for Pop!_OS

This directory contains the open-source FastAPI backend for JioSaavn song search and metadata retrieval, based on [naveennamani/JioSaavnAPI](https://github.com/naveennamani/JioSaavnAPI).

---

## 1. System Requirements (Pop!_OS / Ubuntu)

Pop!_OS 22.04 LTS or newer ships with Python 3.10+ (or Python 3.12).

Ensure `python3` and `python3-venv` are installed:
```bash
sudo apt update
sudo apt install -y python3 python3-venv python3-pip
```

---

## 2. Setting Up Virtual Environment

From this directory (`~/Desktop/tpm/jiosaavn-api`):

```bash
cd ~/Desktop/tpm/jiosaavn-api

# 1. Create a virtual environment
python3 -m venv .venv

# 2. Activate the virtual environment
source .venv/bin/activate

# 3. Upgrade pip and install dependencies
pip install --upgrade pip
pip install -r requirements.txt
```

---

## 3. Running the Backend Server

### Option A: Direct Python Execution (Runs on port 5000)
```bash
source .venv/bin/activate
python3 app.py
```

### Option B: Using Uvicorn Directly (Recommended for Development)
```bash
source .venv/bin/activate
uvicorn app:app --host 0.0.0.0 --port 5000 --reload
```

---

## 4. Verifying the API

Open another terminal or browser:
- **Interactive Swagger Docs**: [http://127.0.0.1:5000/docs](http://127.0.0.1:5000/docs)
- **Search Endpoint Test**:
  ```bash
  curl "http://127.0.0.1:5000/search?query=arijit"
  ```

---

## 5. Connecting Android Devices to the Backend

### Case 1: Android Studio Emulator
The Android emulator runs on an internal virtual network. To access a service running on the host machine:
- Use the loopback alias: **`http://10.0.2.2:5000`**
- This is the default in the Android app's settings.

### Case 2: Physical Android Device on the Same Wi-Fi Network
1. Find your Pop!_OS machine's local IP address:
   ```bash
   hostname -I | awk '{print $1}'
   # Example output: 192.168.1.45
   ```
2. Allow incoming traffic on port 5000 through the Pop!_OS firewall (if UFW is enabled):
   ```bash
   sudo ufw allow 5000/tcp
   ```
3. In the Android app, enter the backend URL in the settings box:
   ```
   http://192.168.1.45:5000
   ```
4. Tap **Save Base URL**.

---

## 6. Important Notice Regarding Unofficial APIs

> [!WARNING]
> - This backend relies on **unofficial JioSaavn endpoints**. Upstream endpoint changes, payload format updates, or rate limits may occur without notice.
> - **Full-length song playback is not guaranteed** for all catalog items. Some tracks may only return 96kbps AAC previews or JioTune snippets.
> - The Android application validates playback URLs before attempting playback and handles expired or restricted media streams gracefully.

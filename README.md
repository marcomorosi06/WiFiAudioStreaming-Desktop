# WiFi Audio Streamer (Desktop)  
[![Available on GitHub](https://img.shields.io/badge/Available%20on-GitHub-181717?style=for-the-badge&logo=github)](https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop)  

Turn your computer into a **wireless audio transmitter or receiver**.  
This application allows you to send your PC's audio to any device on the same local network, or listen to audio from another device.

---

## 📸 Overview  

![Screenshot of the application](INSERT_SCREENSHOT_URL_HERE.png)  

---

## ✨ Key Features  
* **Server & Client Modes**: Use the app to **send** (Server) or **receive** (Client) audio.  
* **Automatic Discovery**: Clients on the local network automatically find available servers, with no need for manual IP address entry.  
* **Unicast & Multicast Support**: Choose between direct streaming to a single device (Unicast) or simultaneous transmission to multiple clients (Multicast), ideal for multi-room audio.  
* **Detailed Audio Configuration**: Customize your audio experience with options for sample rate, bit depth, channels (mono/stereo), and buffer size.  
* **Modern Interface**: The interface, developed with **Jetpack Compose for Desktop**, is clean and easy to use.  
* **Multi-Server Support**: You can use multiple servers on the same network by changing the **connection port** in the settings. Make sure the port is configured identically on both the client and the server.  

---

## 📥 Required Driver  
To use the program, you must install the free driver **VB-CABLE Virtual Audio Device**.  
[**Click here to download VB-CABLE**](https://vb-audio.com/Cable/index.htm)  

* **Server Mode**: Select `CABLE Input` as your audio output device.  
* **Client Mode**: You can select your physical headphones/speakers or, alternatively, `CABLE Output`.  

---

## 📱 Android Version  
The project is also available for **Android**!  
Turn your smartphone into a portable audio receiver or transmitter.  

<a href="INSERT_ANDROID_PROJECT_LINK_HERE">  
<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">  
</a>  

---

## 🚀 Getting Started  

### How to Send Audio (Server Mode)  
1.  Start the app and select **Send (Server)**.  
2.  In **Server Configuration**, choose the **main audio source** to transmit (e.g., *Stereo Mix* or system output).  
3.  Set **VB-CABLE** as the output device.  
4.  Select either **Multicast** (for multiple clients) or **Unicast** (for a single client).  
5.  (Optional) If necessary, configure a custom **connection port**.  
6.  Click on **Start Server**.  

### How to Receive Audio (Client Mode)  
1.  Start the app and select **Receive (Client)**.  
2.  In **Client Configuration**, choose your output device (headphones, speakers, etc.).  
3.  The app will automatically search for and display active servers on the network.  
4.  Select a server from the list to connect.  
5.  (Optional) If the server uses a non-standard port, enter it here.  

---

## 🛠️ Building from Source  

To build the project from source code:  
1.  Clone the repository:  
    `git clone https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop.git`  
2.  Navigate to the project directory:  
    `cd WiFiAudioStreaming-Desktop`  
3.  Run the application with Gradle:  
    `./gradlew run`  
    *(On Windows, use `gradlew.bat run`)* ---

## 💻 Tech Stack  
* **Language**: Kotlin  
* **UI Framework**: Jetpack Compose for Desktop  
* **Networking**: Ktor Networking (UDP sockets)  
* **Audio Handling**: Java Sound API  

---

## 📄 License  
This project is released under the MIT License. For more details, see the `LICENSE` file.

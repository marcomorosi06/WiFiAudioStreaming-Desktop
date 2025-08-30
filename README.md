# WiFi Audio Streamer (Desktop)  
[![Available on GitHub](https://img.shields.io/badge/Available%20on-GitHub-181717?style=for-the-badge&logo=github)](https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop)  

Trasforma il tuo computer in un **trasmettitore o ricevitore audio wireless**.  
Questa applicazione ti permette di inviare l'audio del tuo PC a qualsiasi dispositivo sulla stessa rete locale, oppure di ascoltare da un altro dispositivo, il tutto con **bassa latenza e alta qualità garantite**.  

---

## 📸 Overview  

![Screenshot of the application](INSERT_SCREENSHOT_URL_HERE.png)  

---

## ✨ Funzionalità Principali  
* **Modalità Server e Client**: Invia (Server) o ricevi (Client) audio in base alle tue esigenze.  
* **Rilevamento Automatico**: I client sulla rete locale rilevano automaticamente i server disponibili, senza bisogno di inserire manualmente gli indirizzi IP.  
* **Supporto Unicast & Multicast**: Scegli tra lo streaming diretto a un singolo dispositivo (Unicast) o la trasmissione simultanea a più client (Multicast), ideale per l'audio multi-stanza.  
* **Configurazione Audio Dettagliata**: Personalizza la tua esperienza audio con opzioni per sample rate, bit depth, canali (mono/stereo) e buffer size.  
* **Interfaccia Intuitiva**: L'interfaccia, sviluppata con **Jetpack Compose for Desktop**, è moderna e semplice da usare.  
* **Supporto Multi-Server**: È possibile utilizzare più server sulla stessa rete modificando la **porta di connessione** nelle impostazioni. Assicurati che la porta sia configurata in modo identico sia sul client che sul server.  

---

## 📥 Driver Necessario  
Per utilizzare il programma, devi installare il driver gratuito **VB-CABLE Virtual Audio Device**.  
[**Clicca qui per scaricare VB-CABLE**](https://vb-audio.com/Cable/index.htm)  

* **Modalità Server**: Seleziona `CABLE Input` come dispositivo di output audio.  
* **Modalità Client**: Puoi selezionare le tue cuffie/altoparlanti fisici o, in alternativa, `CABLE Output`.  

---

## 📱 Versione Android  
Il progetto è disponibile anche per **Android**!  
Trasforma il tuo smartphone in un ricevitore o trasmettitore audio portatile.  

<a href="INSERT_ANDROID_PROJECT_LINK_HERE">  
<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">  
</a>  

---

## 🚀 Guida all'Uso  

### Come Inviare Audio (Modalità Server)  
1.  Avvia l'app e seleziona **Send (Server)**.  
2.  In **Server Configuration**, scegli la **sorgente audio principale** da trasmettere (es. *Stereo Mix* o l'uscita di sistema).  
3.  Imposta il **VB-CABLE** come dispositivo di output.  
4.  Seleziona la modalità **Multicast** (per più client) o **Unicast** (per un singolo client).  
5.  (Opzionale) Se necessario, configura una **porta di connessione** personalizzata.  
6.  Clicca su **Start Server**.  

### Come Ricevere Audio (Modalità Client)  
1.  Avvia l'app e seleziona **Receive (Client)**.  
2.  In **Client Configuration**, scegli il tuo dispositivo di output (cuffie, altoparlanti, ecc.).  
3.  L'app cercherà e mostrerà automaticamente i server attivi sulla rete.  
4.  Seleziona un server dalla lista per connetterti.  
5.  (Opzionale) Se il server utilizza una porta non standard, inseriscila.  

---

## 🛠️ Compilazione da Sorgente  

Per compilare il progetto dal codice sorgente:  
1.  Clona il repository:  
    `git clone https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop.git`  
2.  Accedi alla directory del progetto:  
    `cd WiFiAudioStreaming-Desktop`  
3.  Avvia l'applicazione con Gradle:  
    `./gradlew run`  
    *(Su Windows, usa `gradlew.bat run`)* ---

## 💻 Stack Tecnologico  
* **Linguaggio**: Kotlin  
* **UI Framework**: Jetpack Compose for Desktop  
* **Networking**: Ktor Networking (UDP sockets)  
* **Gestione Audio**: Java Sound API  

---

## 📄 Licenza  
Questo progetto è rilasciato sotto licenza MIT. Per maggiori dettagli, consulta il file `LICENSE`.

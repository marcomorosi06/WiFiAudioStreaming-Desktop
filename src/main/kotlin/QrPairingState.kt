/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 */

import kotlinx.coroutines.flow.MutableStateFlow

enum class PairingError { INVALID, EXPIRED, SELF }

/**
 * Stato del pairing QR. Sul Desktop non esiste un ViewModel: questo oggetto fa
 * da sorgente unica per invito, errori e richieste di scansione, e la UI lo
 * osserva come farebbe con un ViewModel su Android.
 */
object QrPairingState {

    val invite = MutableStateFlow<QrInvite?>(null)
    val pendingPairing = MutableStateFlow<PairingPayload?>(null)
    val pairingError = MutableStateFlow<PairingError?>(null)
    val scannerVisible = MutableStateFlow(false)
    val noCameraVisible = MutableStateFlow(false)

    @Volatile
    private var encryptionForcedByInvite = false

    /**
     * La chiave del pairing QR, e solo lei: vive qui, in memoria, per la durata
     * di questo processo. Non finisce nel file di configurazione ne' nella
     * custodia dell'OS, e non viene ripresa al prossimo avvio. Un invito vale
     * finche' resta acceso il server che l'ha emesso; chiuso quello, la chiave
     * non esiste piu' da nessuna parte e i dispositivi vanno riappaiati.
     *
     * Quello che si conserva e' solo la scelta del modo QR, che non e' un
     * segreto. La passphrase scritta a mano continua a vivere in
     * [AppSettings.manualAuthKey], separata da questa.
     */
    @Volatile
    private var sessionKey: String = ""

    fun sessionKey(): String = sessionKey

    fun newSessionKey(): String = WfasAuth.randomPairingKey().also { sessionKey = it }

    fun adoptSessionKey(key: String) { sessionKey = key }

    fun clearSessionKey() { sessionKey = "" }

    /**
     * All'avvio il modo QR non ha ancora una chiave: quella della sessione
     * precedente non e' stata salvata. Ne conia una subito, perche' un server
     * in modo chiave senza chiave non potrebbe accettare nessuno - e in
     * multicast la cifratura si rifiuterebbe proprio di partire.
     */
    fun seedSessionKey(settings: AppSettings): AppSettings {
        if (!SecurityMode.isQrPairing(settings.securityMode, settings.qrPairingEnabled)) {
            clearSessionKey()
            return settings
        }
        return settings.copy(authKey = newSessionKey())
    }

    fun dismissInvite() { invite.value = null }
    fun dismissPendingPairing() { pendingPairing.value = null }
    fun clearError() { pairingError.value = null }
    fun closeScanner() { scannerVisible.value = false }
    fun dismissNoCamera() { noCameraVisible.value = false }

    fun requestScan() {
        if (!WebcamSource.probablyAvailable()) {
            noCameraVisible.value = true
            return
        }
        scannerVisible.value = true
    }

    private fun isOwnInvite(payload: PairingPayload): Boolean {
        if (payload.isMulticast) {
            val ourKey = NetworkHandler_v1.mcastSession.value?.key
            return !ourKey.isNullOrBlank() && ourKey == payload.keyBase64
        }
        return NetAddr.isSelfAddress(payload.ip)
    }

    private fun isExpired(payload: PairingPayload): Boolean {
        val now = System.currentTimeMillis() / 1000
        return now - WfasPairingUri.CLOCK_SKEW_SECONDS > payload.expEpochSeconds
    }

    /** Ritorna il payload se e' utilizzabile, altrimenti alza l'errore adatto. */
    fun accept(raw: String): PairingPayload? {
        val payload = WfasPairingUri.parse(raw)
        if (payload == null) {
            pairingError.value =
                if (WfasPairingUri.isExpiredUri(raw)) PairingError.EXPIRED else PairingError.INVALID
            return null
        }
        if (isOwnInvite(payload)) {
            pairingError.value = PairingError.SELF
            return null
        }
        return payload
    }

    fun submitScanned(raw: String): PairingPayload? {
        scannerVisible.value = false
        return accept(raw)
    }

    /** Deep link: chiede conferma invece di connettere subito. */
    fun submitDeepLink(raw: String): Boolean {
        val payload = WfasPairingUri.parse(raw)
        if (payload == null) {
            if (WfasPairingUri.isExpiredUri(raw)) {
                pairingError.value = PairingError.EXPIRED
                return true
            }
            return false
        }
        if (isOwnInvite(payload)) {
            pairingError.value = PairingError.SELF
            return true
        }
        pendingPairing.value = payload
        return true
    }

    fun confirmPending(): PairingPayload? {
        val payload = pendingPairing.value ?: return null
        pendingPairing.value = null
        if (isOwnInvite(payload)) { pairingError.value = PairingError.SELF; return null }
        if (isExpired(payload)) { pairingError.value = PairingError.EXPIRED; return null }
        return payload
    }

    fun prepareClientForPairing(payload: PairingPayload) {
        NetworkHandler_v1.clearEpochMismatch()
        NetworkHandler_v1.clearInviteRejected()
        NetworkHandler_v1.expectedMcastEpoch = payload.mcastEpoch
        NetworkHandler_v1.clientPresharedKey = payload.keyBase64
        NetworkHandler_v1.clientKeyFromInvite = true
    }

    /**
     * Genera un invito. La chiave e' sempre nuova, tranne in multicast quando il
     * gruppo ha gia' una chiave di sessione: li' invitare non deve espellere
     * nessuno, cosa che resta compito esclusivo di [forceNewKey].
     *
     * [applySettings] serve a tenere allineato lo stato in memoria di chi
     * chiama (la finestra, o il server CLI): la chiave che ci passa non va
     * salvata da nessuna parte, e [SettingsRepository] la scarta apposta.
     */
    fun generateInvite(
        settings: AppSettings,
        localIp: String,
        port: Int,
        multicast: Boolean,
        forceNewKey: Boolean,
        applySettings: (AppSettings) -> Unit
    ) {
        val ip = if (multicast) NetworkHandler_v1.MULTICAST_GROUP_IP else localIp
        if (ip.isBlank() || ip == "0.0.0.0") return

        var next = settings
        var encryptionForced = false
        if (multicast && !settings.encryptionEnabled) {
            next = next.copy(encryptionEnabled = true)
            encryptionForced = true
            encryptionForcedByInvite = true
        }

        val reuse = multicast && !forceNewKey && sessionKey.isNotBlank()

        val key = if (reuse) {
            (NetworkHandler_v1.mcastSession.value?.key?.takeIf { it.isNotBlank() } ?: sessionKey)
                .also { adoptSessionKey(it) }
        } else {
            newSessionKey()
        }

        next = next.copy(
            securityMode = SecurityMode.KEY.name,
            authKey = key,
            qrPairingEnabled = true
        )
        if (next != settings) applySettings(next)

        NetworkHandler_v1.configureSecurity(
            SecurityMode.KEY.name, key, next.encryptionEnabled
        )

        var epoch: Long? = null
        if (multicast) {
            if (!reuse || encryptionForced) NetworkHandler_v1.rekeyMulticast(key)
            epoch = NetworkHandler_v1.mcastSession.value?.takeIf { it.encrypted }?.epoch
        }

        val exp = System.currentTimeMillis() / 1000 + WfasPairingUri.PAIRING_TTL_SECONDS
        val mode = if (multicast) WfasPairingUri.MODE_MULTICAST else WfasPairingUri.MODE_UNICAST

        invite.value = QrInvite(
            uri = WfasPairingUri.buildAppLink(ip, port, mode, key, exp, epoch),
            key = key,
            ip = ip,
            port = port,
            multicast = multicast,
            expEpochSeconds = exp,
            encryptionForced = encryptionForced
        )
    }

    /** Ripristina la cifratura se l'avevamo accesa noi per rendere sensato un invito. */
    fun restoreForcedEncryption(
        settings: AppSettings,
        applySettings: (AppSettings) -> Unit,
        isMulticast: Boolean = false
    ) {
        if (!encryptionForcedByInvite) return
        encryptionForcedByInvite = false
        invite.value = null
        val locked = SecurityMode.encryptionForcedStored(
            settings.securityMode,
            settings.qrPairingEnabled,
            isMulticast
        )
        if (settings.encryptionEnabled && !locked) {
            applySettings(settings.copy(encryptionEnabled = false))
        }
    }
}

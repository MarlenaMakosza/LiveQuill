package dev.handnotes.desktop

import java.util.Locale

/** UI texts. English by default, Polish when the system language is Polish. */
object Strings {
    private val pl = Locale.getDefault().language == "pl"

    private fun t(en: String, pl: String) = if (this.pl) pl else en

    val follow = t("follow", "śledź")
    val fitAll = t("Fit all", "Wszystko")
    val light = t("Light", "Jasny")
    val dark = t("Dark", "Ciemny")
    val pinned = t("Pinned", "Przypięte")
    val pin = t("Pin", "Przypnij")
    val options = t("Options", "Opcje")
    val settings = t("Settings", "Ustawienia")
    val serverAddress = t("Server address", "Adres serwera")
    val token = t("Token", "Token")
    val cancel = t("Cancel", "Anuluj")
    val save = t("Save", "Zapisz")

    fun status(s: Viewer.Status) = when (s) {
        Viewer.Status.CONNECTING -> t("connecting…", "łączę…")
        Viewer.Status.ONLINE -> "online"
        Viewer.Status.OFFLINE -> "offline"
        Viewer.Status.BAD_TOKEN -> t("bad token", "zły token")
        Viewer.Status.BAD_ADDRESS -> t("bad address", "zły adres")
    }
}

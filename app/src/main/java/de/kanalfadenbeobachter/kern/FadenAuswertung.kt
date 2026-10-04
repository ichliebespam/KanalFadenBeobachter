package de.kanalfadenbeobachter.kern
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.net.URI
import java.security.MessageDigest


fun pruefsumme256(wert: String): String = MessageDigest.getInstance("SHA-256").digest(wert.toByteArray()).joinToString("") { "%02x".format(it) }
data class FadenAdresse(val brett: String, val kennung: String) {
    val schluessel get() = "$brett/res$kennung"
    val adresse get() = "https://kohlchan.net/$brett/res/$kennung.html"
    val ordner get() = "res$kennung"
    companion object {
        fun auswerten(wert: String): FadenAdresse {
            val u = URI(wert.trim())
            require(u.scheme == "https" && u.host?.lowercase() == "kohlchan.net" && u.port in listOf(-1,443) && u.userInfo == null) { "Bitte eine HTTPS-Fadenadresse von kohlchan.net eingeben." }
            val m = Regex("^/([a-zA-Z0-9_]+)/res/([0-9]{1,18})\\.html$").matchEntire(u.path) ?: error("Ungültige Fadenadresse")
            return FadenAdresse(m.groupValues[1], m.groupValues[2])
        }
    }
}
data class Medium(val beitragskennung: String, val laufnummer: Int, val adresse: String, val ursprungsname: String, val medientyp: String, val lokalerPfad:String="") {
    val erweiterung: String get() = URI(adresse).path.substringAfterLast('.', "bin").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
    val pfad: String get() = lokalerPfad.ifEmpty {"media/$erweiterung/${pruefsumme256(adresse)}.$erweiterung"}
}
data class Beitrag(val kennung: String, val name: String, val datum: String, val betreff: String, val nachricht: String, val medien: List<Medium>)
data class Fadenstand(val faden: FadenAdresse, val titel: String, val beitraege: List<Beitrag>) {
    val medien get() = beitraege.flatMap { it.medien }
    val inhaltspruefsumme get() = pruefsumme256(beitraege.joinToString("\n") { p ->
        listOf(p.kennung,p.name,p.datum,p.betreff,p.nachricht,p.medien.joinToString { "${it.adresse}|${it.ursprungsname}|${it.medientyp}" }).joinToString("\u0000")
    })
}
class NichtGefunden : Exception("Faden nicht gefunden (404)")
class UngueltigeSeite(nachricht: String) : Exception(nachricht)
object FadenAuswertung {
    fun auswerten(faden: FadenAdresse, seitentext: String): Fadenstand {
        val dokument = Jsoup.parse(seitentext, faden.adresse)
        if (dokument.select(".opCell").isEmpty() && dokument.title().trim() == "File not found" &&
            dokument.select("header h2").any { it.text().trim() == "404" } &&
            dokument.select("header p").any { it.text().trim() == "Error: Not Found" } &&
            dokument.select("header img.notFoundImg[src=/.static/images/404.png]").isNotEmpty()) throw NichtGefunden()
        if (dokument.selectFirst("#threadIdentifier")?.attr("value") != faden.kennung ||
            dokument.selectFirst("#boardIdentifier")?.attr("value") != faden.brett) throw UngueltigeSeite("Keine gültige Fadenseite: Kennung fehlt oder stimmt nicht überein")
        val startbeitrag = dokument.select(".opCell")
        if (startbeitrag.size != 1 || startbeitrag.first()!!.id() != faden.kennung) throw UngueltigeSeite("Startbeitrag fehlt")
        val beitraege = dokument.select(".opCell, .postCell").map { el ->
            // Die HTML-Reparatur kann Antworten innerhalb des Startbeitrags anordnen.
            // Nur Felder übernehmen, die unmittelbar zu diesem Beitrag gehören.
            fun zugehoerige(auswahlregel: String) = el.select(auswahlregel).filter { knoten ->
                knoten.parents().firstOrNull { it.hasClass("opCell") || it.hasClass("postCell") } === el
            }
            val kennung = el.id()
            if (!kennung.matches(Regex("[0-9]+")) || el.attr("data-boarduri") != faden.brett) throw UngueltigeSeite("Ungültige Beitragskennung")
            val nachricht = zugehoerige(".divMessage").firstOrNull() ?: throw UngueltigeSeite("Unvollständiger Beitrag $kennung")
            val bereinigt = Jsoup.clean(nachricht.html(), faden.adresse, Safelist.basic().addTags("span", "s", "del", "pre", "code"), org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
            val medien = zugehoerige(".uploadCell").mapIndexed { laufnummer, anhang ->
                val elfe = anhang.selectFirst("a.imgLink") ?: throw UngueltigeSeite("Anhang ohne Original-Elfe")
                val adresse = elfe.absUrl("href")
                val ressourcenadresse = runCatching { URI(adresse) }.getOrNull()
                if (ressourcenadresse?.scheme != "https" || ressourcenadresse.host != "kohlchan.net" || !ressourcenadresse.path.startsWith("/.media/")) throw UngueltigeSeite("Unerwartete Medien-Adresse")
                Medium(kennung, laufnummer, adresse, anhang.selectFirst("a.originalNameLink")?.attr("download").orEmpty().ifEmpty { ressourcenadresse.path.substringAfterLast('/') }, elfe.attr("data-filemime"))
            }
            Beitrag(kennung, zugehoerige(".linkName").firstOrNull()?.text().orEmpty(), zugehoerige(".labelCreated").firstOrNull()?.text().orEmpty(), zugehoerige(".labelSubject").firstOrNull()?.text().orEmpty(), bereinigt, medien)
        }
        if (beitraege.map { it.kennung }.distinct().size != beitraege.size) throw UngueltigeSeite("Doppelte Beitragsnummern")
        return Fadenstand(faden, dokument.title(), beitraege)
    }
}

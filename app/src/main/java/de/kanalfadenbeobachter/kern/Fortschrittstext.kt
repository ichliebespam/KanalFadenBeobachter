package de.kanalfadenbeobachter.kern
import java.util.Locale


/** Auch kleine Bilder erhalten eine aussagekräftige Größenangabe. */
object Fortschrittstext {
    fun groesse(wert:Long):String {
        if(wert<0)return "unbekannt"
        if(wert<1024)return "$wert B"
        val einheiten=arrayOf("KiB","MiB","GiB","TiB")
        var n=wert/1024.0;var i=0
        while(n>=1024 && i<einheiten.lastIndex){n/=1024;i++}
        return String.format(Locale.GERMANY,"%.1f %s",n,einheiten[i])
    }
    fun uebertragung(empfangen:Long,gesamt:Long):String {
        if(gesamt<=0)return "${groesse(empfangen)} geladen · Gesamtgröße unbekannt"
        val prozent=(empfangen.toDouble()/gesamt*100).toInt().coerceIn(0,100)
        return "${groesse(empfangen)} / ${groesse(gesamt)} · $prozent %"
    }
    fun anzahlmeldung(gesichert:Int,gesamt:Int,fehlend:Int):String =
        "$gesichert/$gesamt Medien gesichert · ${(gesamt-gesichert-fehlend).coerceAtLeast(0)} offen" +
            if(fehlend>0) " · $fehlend nicht verfügbar" else ""
}

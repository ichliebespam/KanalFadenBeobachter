package de.kanalfadenbeobachter.kern

import java.util.Locale

/** Empfangene Nutzdaten, ohne HTTP-Kopfzeilen und Verschlüsselungsaufschlag. */
class Abrufbilanz {
    var seitenbytes=0L; private set
    var medienbytes=0L; private set
    var gesicherteMedien=0; private set
    fun seiteEmpfangen(anzahl:Long) {require(anzahl>=0);seitenbytes+=anzahl}
    fun mediumEmpfangen(anzahl:Long) {require(anzahl>=0);medienbytes+=anzahl}
    fun mediumGesichert() {gesicherteMedien++}
    fun meldung(bezeichnung:String,abgeschlossen:Boolean):String? {
        if(medienbytes==0L && gesicherteMedien==0)return null
        return "$bezeichnung ${if(abgeschlossen) "abgeschlossen" else "unterbrochen"}: ${Uebertragungsangaben.groesse(seitenbytes+medienbytes)} insgesamt empfangen · davon ${Uebertragungsangaben.groesse(medienbytes)} Medien, ${Uebertragungsangaben.groesse(seitenbytes)} Fadenseiten · $gesicherteMedien Medien gesichert"
    }
}

object Uebertragungsangaben {
    fun groesse(anzahl:Long):String {
        if(anzahl<0)return "Größe unbekannt"
        if(anzahl<1000)return "$anzahl Byte"
        val einheiten=arrayOf("KB","MB","GB","TB")
        var wert=anzahl/1000.0;var stelle=0
        while(wert>=1000 && stelle<einheiten.lastIndex){wert/=1000;stelle++}
        return String.format(Locale.GERMANY,"%.2f %s",wert,einheiten[stelle])
    }
    fun dauer(millisekunden:Long):String = if(millisekunden<100) "unter 0,1 Sekunden" else String.format(Locale.GERMANY,"%.1f Sekunden",millisekunden/1000.0)
    fun beginn(name:String,gesamt:Long,fortgesetzt:Long,versuch:Int):String =
        "Start $name · ${groesse(gesamt)}${if(fortgesetzt>0) " · bereits vorhanden: ${groesse(fortgesetzt)}" else ""} · Versuch $versuch"
    fun gespeichert(pfad:String,empfangen:Long,millisekunden:Long,gesamt:Long):String =
        "gespeichert $pfad · ${groesse(empfangen)} in ${dauer(millisekunden)} übertragen" + if(gesamt!=empfangen) " · vollständige Datei: ${groesse(gesamt)}" else ""
}

object Fadenaufnahme {
    fun pruefen(faden:FadenAdresse,vorhandene:List<FadenAdresse>) {
        require(vorhandene.none {it.schluessel==faden.schluessel}) {"${faden.ordner}: Adresse bereits vorhanden, möglicherweise unter den entfernten Fäden."}
        require(vorhandene.none {it.kennung==faden.kennung}) {"${faden.ordner}: Diese Nummer existiert bereits auf einem anderen Brett; Ordnerüberschneidung verhindert."}
    }
}

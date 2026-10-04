package de.kanalfadenbeobachter.kern

import org.junit.Assert.*
import org.junit.Test

class AbrufbilanzPruefung {
    @Test fun gleicheFadenadresseMitSprungmarkeWirdAbgewiesen() {
        val vorhanden=FadenAdresse.auswerten("https://kohlchan.net/b/res/12345678.html")
        val nochmals=FadenAdresse.auswerten("https://kohlchan.net/b/res/12345678.html#87654321")
        val fehler=runCatching {Fadenaufnahme.pruefen(nochmals,listOf(vorhanden))}.exceptionOrNull()
        assertTrue(fehler is IllegalArgumentException)
        assertTrue(fehler!!.message!!.contains("bereits vorhanden"))
    }
    @Test fun neueFadenadresseWirdAngenommen() {
        Fadenaufnahme.pruefen(FadenAdresse("b","12345679"),listOf(FadenAdresse("b","12345678")))
    }
    @Test fun gleicheNummerAufAnderemBrettWirdAbgewiesen() {
        assertTrue(runCatching {Fadenaufnahme.pruefen(FadenAdresse("int","12345678"),listOf(FadenAdresse("b","12345678")))}.isFailure)
    }
    @Test fun datenmengeWaehltPassendeDezimaleEinheit() {
        assertEquals("999 Byte",Uebertragungsangaben.groesse(999))
        assertEquals("1,00 KB",Uebertragungsangaben.groesse(1000))
        assertEquals("1,50 MB",Uebertragungsangaben.groesse(1500000))
        assertEquals("2,50 GB",Uebertragungsangaben.groesse(2500000000L))
    }
    @Test fun unbekannteGroesseWirdNichtAlsNullAusgegeben() {
        assertTrue(Uebertragungsangaben.beginn("Bild.jpg",-1,0,1).contains("Größe unbekannt"))
        assertTrue(Uebertragungsangaben.beginn("Bild.jpg",2048,0,1).contains("2,05 KB"))
    }
    @Test fun fortsetzungUnterscheidetEmpfangVonGesamtgroesse() {
        val meldung=Uebertragungsangaben.gespeichert("Bild.jpg",500000,2500,2000000)
        assertTrue(meldung.contains("500,00 KB in 2,5 Sekunden übertragen"))
        assertTrue(meldung.contains("vollständige Datei: 2,00 MB"))
        assertTrue(Uebertragungsangaben.beginn("Bild.jpg",2000000,1500000,2).contains("bereits vorhanden: 1,50 MB"))
    }
    @Test fun sehrSchnelleDateiHatKeineGerundeteNulldauer() {
        assertEquals("unter 0,1 Sekunden",Uebertragungsangaben.dauer(2))
        assertEquals("0,1 Sekunden",Uebertragungsangaben.dauer(100))
    }
    @Test fun bilanzZaehltSeitenUndFehlversucheMit() {
        val bilanz=Abrufbilanz()
        bilanz.seiteEmpfangen(1000)
        bilanz.mediumEmpfangen(400) // Übertragener Teil eines fehlgeschlagenen Versuchs.
        bilanz.mediumEmpfangen(1000) // Vollständiger neuer Versuch.
        bilanz.mediumGesichert()
        val meldung=bilanz.meldung("Einzelabruf",true)!!
        assertTrue(meldung.contains("2,40 KB insgesamt empfangen"))
        assertTrue(meldung.contains("1,40 KB Medien"))
        assertTrue(meldung.contains("1 Medien gesichert"))
    }
    @Test fun unterbrocheneUebertragungErhaeltTeilbilanz() {
        val bilanz=Abrufbilanz();bilanz.mediumEmpfangen(700)
        assertTrue(bilanz.meldung("Hintergrundabruf",false)!!.contains("unterbrochen: 700 Byte"))
        assertEquals(0,bilanz.gesicherteMedien)
    }
    @Test fun ohneMedienKeinZusatzprotokollUndNaechsterAbrufBeginntBeiNull() {
        val vorherig=Abrufbilanz();vorherig.mediumEmpfangen(9000)
        val danach=Abrufbilanz();danach.seiteEmpfangen(1000)
        assertNull(danach.meldung("Intervallabruf",true))
        assertEquals(0L,danach.medienbytes)
        assertEquals(9000L,vorherig.medienbytes)
    }
}

package de.kanalfadenbeobachter.kern
import org.junit.Assert.*
import org.junit.Test


class FortschrittstextPruefung {
    @Test fun kleineBilderBehaltenIhreGroesse() {
        assertEquals("850 B",Fortschrittstext.groesse(850))
        assertEquals("42,5 KiB",Fortschrittstext.groesse(43520))
        assertEquals("1,5 MiB",Fortschrittstext.groesse(1572864))
    }
    @Test fun kleineBilderWerdenNichtAufNullGerundet() {
        assertEquals("21,3 KiB / 42,5 KiB · 50 %",Fortschrittstext.uebertragung(21760,43520))
        assertEquals("42,5 KiB / 42,5 KiB · 100 %",Fortschrittstext.uebertragung(43520,43520))
    }
    @Test fun unbekannteGroesseWirdKlarBenannt() {
        assertEquals("850 B geladen · Gesamtgröße unbekannt",Fortschrittstext.uebertragung(850,-1))
        assertFalse(Fortschrittstext.uebertragung(850,0).contains("%"))
    }
    @Test fun fehlendeMedienWerdenGetrenntGezaehlt() {
        assertEquals("120/300 Medien gesichert · 177 offen · 3 nicht verfügbar",Fortschrittstext.anzahlmeldung(120,300,3))
    }
    @Test fun fertigeWarteschlangeHatZusammenfassung() {
        assertEquals("145/145 Medien gesichert · 0 offen",Fortschrittstext.anzahlmeldung(145,145,0))
    }
}

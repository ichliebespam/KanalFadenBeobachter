package de.kanalfadenbeobachter.kern

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

class EinstellungsPruefung {
    private fun faden(vararg medien:Medium)=Fadenstand(FadenAdresse("b","12345678"),"Beispiel",listOf(Beitrag("12345678","","","","Text",medien.toList())))
    private fun medium(kennung:String="12345678",adresse:String="https://kohlchan.net/.media/a.jpg",name:String="Bild.jpg",nummer:Int=0)=Medium(kennung,nummer,adresse,name,"image/jpeg")
    @Test fun beitragsnummerUndOriginalnameWerdenVerwendet() {
        val stand=MedienDateinamen.zuordnen(faden(medium()),emptyMap(),true)
        assertEquals("media/jpg/12345678_Bild.jpg",stand.medien.single().pfad)
    }
    @Test fun gleicheNamenInEinemBeitragBleibenGetrennt() {
        val stand=MedienDateinamen.zuordnen(faden(medium(),medium(adresse="https://kohlchan.net/.media/b.jpg",nummer=1)),emptyMap(),true)
        assertEquals(listOf("media/jpg/12345678_Bild.jpg","media/jpg/12345678_Bild_2.jpg"),stand.medien.map {it.pfad})
    }
    @Test fun gleicheAdresseWirdNurEinmalAbgelegt() {
        val stand=MedienDateinamen.zuordnen(faden(medium(),medium(kennung="12345679")),emptyMap(),true)
        assertEquals(1,stand.medien.map {it.pfad}.distinct().size)
    }
    @Test fun bestehendeDateinamenBleibenErhalten() {
        val anhang=medium()
        val alt="media/jpg/bereitsgesichert.jpg"
        val stand=MedienDateinamen.zuordnen(faden(anhang),mapOf(anhang.adresse to alt),true)
        assertEquals(alt,stand.medien.single().pfad)
    }
    @Test fun pruefsummennamenBleibenDieVoreinstellung() {
        val anhang=medium()
        assertEquals(anhang.pfad,MedienDateinamen.zuordnen(faden(anhang),emptyMap(),false).medien.single().pfad)
    }
    @Test fun namenKoennenKeinenOrdnerVerlassen() {
        val name=MedienDateinamen.bereinigen("../../a\\b\u0000?\"#.jpg")
        assertFalse(name.contains('/'));assertFalse(name.contains('\\'));assertFalse(name.contains('\u0000'));assertFalse(name.contains('"'))
        assertTrue(MedienDateinamen.bereinigen("😀".repeat(100)).toByteArray().size<=180)
    }
    @Test fun sonderzeichenWerdenInArchivElfenGeschuetzt() {
        val stand=MedienDateinamen.zuordnen(faden(medium(name="a&b.jpg")),emptyMap(),true)
        assertTrue(ArchivDarstellung.darstellen(stand,"heute").contains("12345678_a&amp;b.jpg"))
    }
    @Test fun volleMinuteEnthaeltImmerSekunden() {
        assertEquals("23:45:00",Zeitangaben.uhrzeit(Instant.parse("2026-10-04T23:45:00Z").toEpochMilli(),ZoneOffset.UTC))
        assertEquals("00:00:00",Zeitangaben.uhrzeit(0,ZoneOffset.UTC))
    }
    @Test fun restzeitRundetAufUndWirdNieNegativ() {
        assertEquals(2L,Zeitangaben.restsekunden(2001,1000))
        assertEquals(1L,Zeitangaben.restsekunden(2000,1000))
        assertEquals(0L,Zeitangaben.restsekunden(1000,2000))
    }
    @Test fun zufallspauseBleibtInnerhalbDerAuswahl() {
        val zufall=Random(7)
        assertEquals(0L,Zeitangaben.zufallspause(0,zufall))
        for(grenze in 1..10)repeat(1000){assertTrue(Zeitangaben.zufallspause(grenze,zufall) in 0L..grenze*1000L)}
    }
}

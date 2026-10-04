package de.kanalfadenbeobachter.kern
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup


class FadenAuswertungsPruefung {
    private val faden=FadenAdresse("b","10196674")
    private fun beispieldatei(name:String)=javaClass.getResource("/$name")!!.readText()
    private fun beispiel()=FadenAuswertung.auswerten(faden,beispieldatei("thread.html"))
    @Test fun gelieferterFadenWirdVollstaendigErfasst() {
        val s=beispiel()
        assertEquals(501,s.beitraege.size)
        assertEquals(360,s.beitraege.count {it.medien.isEmpty()})
        assertEquals(157,s.medien.size)
        assertEquals(145,s.medien.map {it.adresse}.distinct().size)
        assertEquals(setOf("jpg","png","webp","gif","mp3","mp4","webm"),s.medien.map {it.erweiterung}.toSet())
        assertEquals(4,s.beitraege.first().medien.size)
        assertTrue(s.medien.all {it.beitragskennung in s.beitraege.map {p->p.kennung}})
    }
    @Test(expected=NichtGefunden::class) fun echteFehlerseiteWirdErkannt() {FadenAuswertung.auswerten(faden,beispieldatei("not-found.html"))}
    @Test(expected=UngueltigeSeite::class) fun nichtErreichbareSeiteLoeschtKeinenFaden() {FadenAuswertung.auswerten(faden,"<html><head><title>Site Unavailable</title></head><body>Unable to access this site.</body></html>")}
    @Test(expected=UngueltigeSeite::class) fun falscherFadenWirdAbgewiesen() {FadenAuswertung.auswerten(FadenAdresse("b","888888"),beispieldatei("thread.html"))}
    @Test fun geloeschterTextbeitragErzeugtFassung() {
        val seitentext=beispieldatei("thread.html");val dokument=Jsoup.parse(seitentext)
        dokument.select(".postCell").first {it.select(".uploadCell").isEmpty()}.remove()
        val s=FadenAuswertung.auswerten(faden,dokument.html())
        assertEquals(500,s.beitraege.size);assertEquals(157,s.medien.size);assertNotEquals(beispiel().inhaltspruefsumme,s.inhaltspruefsumme)
    }
    @Test fun neuerTextbeitragErzeugtFassung() {
        val dokument=Jsoup.parse(beispieldatei("thread.html"));val p=dokument.select(".postCell").first {it.select(".uploadCell").isEmpty()}.clone()
        p.attr("id","99999999");p.selectFirst(".divMessage")!!.text("Neuer Textbeitrag ohne Bild")
        dokument.select(".postCell").last()!!.after(p)
        val s=FadenAuswertung.auswerten(faden,dokument.html());assertEquals(502,s.beitraege.size);assertEquals(157,s.medien.size);assertNotEquals(beispiel().inhaltspruefsumme,s.inhaltspruefsumme)
    }
    @Test fun ersetzungTrotzGleicherBeitragszahlErkannt() {
        val dokument=Jsoup.parse(beispieldatei("thread.html"));dokument.select(".postCell").first()!!.attr("id","99999999")
        val s=FadenAuswertung.auswerten(faden,dokument.html());assertEquals(501,s.beitraege.size);assertNotEquals(beispiel().inhaltspruefsumme,s.inhaltspruefsumme)
    }
    @Test fun oberflaechenAenderungenErzeugenKeineFassung() {
        val dokument=Jsoup.parse(beispieldatei("thread.html"));dokument.head().append("<script>let changing=1234</script>");dokument.selectFirst("#bannerImage")?.attr("src","/other-banner")
        assertEquals(beispiel().inhaltspruefsumme,FadenAuswertung.auswerten(faden,dokument.html()).inhaltspruefsumme)
    }
    @Test fun bearbeitungErzeugtFassung() {
        val dokument=Jsoup.parse(beispieldatei("thread.html"));dokument.selectFirst(".divMessage")!!.appendText("bearbeitet")
        assertNotEquals(beispiel().inhaltspruefsumme,FadenAuswertung.auswerten(faden,dokument.html()).inhaltspruefsumme)
    }
    @Test fun gleicheNamenErgebenKeineKollision() {
        val medien=beispiel().medien
        val nachPfad=medien.groupBy {it.pfad}
        assertTrue(nachPfad.values.all {eintraege->eintraege.map {it.adresse}.distinct().size==1})
        assertEquals(145,nachPfad.size)
    }
    @Test fun archivHatRichtigePfadeUndKeinenAktivenQuelltext() {
        val s=beispiel();val seitentext=ArchivDarstellung.darstellen(s,"2026-10-04T05:00:00Z")
        val dokument=Jsoup.parse(seitentext)
        assertEquals(501,dokument.select("article").size)
        assertTrue(dokument.select("script,iframe,form").isEmpty())
        assertTrue(dokument.select("img,video,audio").all {it.attr("src").startsWith("../media/")})
        val vorherig=Jsoup.parse(ArchivDarstellung.zurAltenFassung(seitentext))
        assertTrue(vorherig.select("img,video,audio").all {it.attr("src").startsWith("../../media/")})
    }
    @Test fun kurzeNummernUndSprungmarkenWerdenAngenommen() {assertEquals("888888",FadenAdresse.auswerten("https://kohlchan.net/b/res/888888.html#12").kennung)}
    @Test(expected=IllegalArgumentException::class) fun fremderAnbieterWirdAbgewiesen() {FadenAdresse.auswerten("https://kohlchan.net.evil.test/b/res/10196674.html")}
    @Test(expected=UngueltigeSeite::class) fun fehlenderInhaltIstKeineMassenloeschung() {
        val dokument=Jsoup.parse(beispieldatei("thread.html"));dokument.selectFirst(".divMessage")!!.remove();FadenAuswertung.auswerten(faden,dokument.html())
    }
    @Test fun fehlerwoerterImBeitragSindHarmlos() {
        val dokument=Jsoup.parse(beispieldatei("thread.html"));dokument.selectFirst(".divMessage")!!.text("404 Error: Not Found File not found")
        assertEquals(501,FadenAuswertung.auswerten(faden,dokument.html()).beitraege.size)
    }
}

package de.kanalfadenbeobachter.kern

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import java.nio.file.Files
import java.net.URI

class ArchivMedienpfadPruefung {
    private fun stand(m:Medium)=Fadenstand(FadenAdresse("b","12345678"),"Test",listOf(Beitrag("12345678","","","","Text",listOf(m))))
    private fun medium(ext:String="jpg",mime:String="image/jpeg",name:String="Foto.jpg")=
        Medium("12345678",0,"https://kohlchan.net/.media/datei.$ext",name,mime)

    @Test fun beitragsnamenVerweisenAufTatsaechlicheDateienAuchMitSonderzeichen() {
        val wurzel=Files.createTempDirectory("archiv-links")
        try {
            for((ext,mime,name) in listOf(Triple("jpg","image/jpeg","Grüße & Urlaub.jpg"),Triple("webm","video/webm","Mein Film.webm"),Triple("mp3","audio/mpeg","Ton + Musik.mp3"))) {
                val s=MedienDateinamen.zuordnen(stand(medium(ext,mime,name)),emptyMap(),true)
                val datei=wurzel.resolve(s.medien.single().pfad)
                Files.createDirectories(datei.parent);Files.write(datei,byteArrayOf(1))
                for(alt in listOf(false,true)) {
                    val htmlDatei=wurzel.resolve(if(alt)"html/old/faden.html" else "html/faden.html")
                    val html=Jsoup.parse(ArchivDarstellung.darstellen(s,"heute",alt))
                    val links=html.select("figure [href],figure [src]")
                    assertTrue(links.isNotEmpty())
                    for(el in links) {
                        val href=el.attr(if(el.hasAttr("src"))"src" else "href")
                        assertEquals(datei.toUri(),htmlDatei.toUri().resolve(URI(href)))
                        assertTrue(Files.exists(java.nio.file.Paths.get(htmlDatei.toUri().resolve(href))))
                    }
                }
            }
        } finally {wurzel.toFile().deleteRecursively()}
    }
    @Test fun bestehendeZuordnungHatVorrangVorAktuellerNamenseinstellung() {
        val m=medium()
        val s=MedienDateinamen.zuordnen(stand(m),mapOf(m.adresse to "media/jpg/12345678_Bestehend.jpg"),false)
        assertTrue(ArchivDarstellung.darstellen(s,"heute").contains("../media/jpg/12345678_Bestehend.jpg"))
    }
    @Test fun kollidierendeOriginalnamenBehaltenIhreEindeutigenLinks() {
        val a=medium()
        val b=a.copy(laufnummer=1,adresse="https://kohlchan.net/.media/zweites.jpg")
        val s=stand(a).let {it.copy(beitraege=listOf(it.beitraege.single().copy(medien=listOf(a,b))))}
        val html=Jsoup.parse(ArchivDarstellung.darstellen(MedienDateinamen.zuordnen(s,emptyMap(),true),"heute"))
        assertEquals(listOf("../media/jpg/12345678_Foto.jpg","../media/jpg/12345678_Foto_2.jpg"),html.select("img").map {it.attr("src")})
    }
}

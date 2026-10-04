package de.kanalfadenbeobachter.kern
import org.junit.Assert.*
import org.junit.Test

class ArchivverzeichnisPruefung {
    @Test fun zuordnungErhaeltDateinamenUndGroesseMitCsvSonderzeichen() {
        val text="source_url,original_name,local_path,status,bytes,sha256\r\n\"https://kohlchan.net/.media/a.jpg\",\"a,\"\"b\"\"\nc.jpg\",\"media/jpg/123_a_b.jpg\",\"done\",\"12345\",\"abc\"\r\n"
        val eintrag=Archivverzeichnis.lesen(text).values.single()
        assertEquals("media/jpg/123_a_b.jpg",eintrag.pfad)
        assertEquals(12345L,eintrag.groesse)
        assertEquals("abc",eintrag.pruefsumme)
    }
    @Test fun fremdePfadeUndUnfertigeZuordnungenWerdenNichtAlsFertigUebernommen() {
        val text="source_url,local_path,status,bytes,sha256\na,media/../falsch,done,400,x\nb,media/jpg/b.jpg.writing,done,400,x\nc,media/jpg/c.jpg,pending,0,\n"
        val eintraege=Archivverzeichnis.lesen(text)
        assertEquals(setOf("c"),eintraege.keys)
        assertEquals(0L,eintraege.getValue("c").groesse)
    }
}

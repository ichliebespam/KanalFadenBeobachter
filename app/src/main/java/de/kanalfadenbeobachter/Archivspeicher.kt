package de.kanalfadenbeobachter
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import de.kanalfadenbeobachter.kern.*


/** Der gewählte Archivordner enthält die eigentlichen Dateien. */
class Archivspeicher(private val umgebung:Context) {
    private val zwischenspeicher=HashMap<String,DocumentFile>()
    private fun wurzel():DocumentFile {
        val ressourcenadresse=umgebung.getSharedPreferences("settings",0).getString("tree",null) ?: error("Zuerst einen Archivordner auswählen")
        return DocumentFile.fromTreeUri(umgebung,Uri.parse(ressourcenadresse))?.takeIf {it.canWrite()} ?: error("Archivordner nicht zugänglich; Ordner erneut freigeben")
    }
    @Synchronized fun zwischenspeicherLeeren()=zwischenspeicher.clear()
    @Synchronized private fun verzeichnisSuchen(pfad:String):DocumentFile {
        zwischenspeicher[pfad]?.let {medienkennung(it,pfad);return it}
        var verzeichnis=wurzel()
        var aufgebaut=""
        if(pfad.isNotEmpty()) pfad.split('/').forEach {teil -> require(teil.isNotEmpty() && teil!=".." && teil!="."); verzeichnis=verzeichnis.findFile(teil)?.takeIf {it.isDirectory} ?: verzeichnis.createDirectory(teil) ?: error("Ordner nicht anlegbar: $teil");aufgebaut=if(aufgebaut.isEmpty())teil else "$aufgebaut/$teil";medienkennung(verzeichnis,aufgebaut) }
        medienkennung(verzeichnis,pfad)
        zwischenspeicher[pfad]=verzeichnis
        return verzeichnis
    }
    private fun medienkennung(verzeichnis:DocumentFile,pfad:String) {
        if("media" !in pfad.split('/'))return
        val vorhanden=verzeichnis.findFile(".nomedia")
        if(umgebung.getSharedPreferences("settings",0).getBoolean("medienAusblenden",true)) {
            if(vorhanden==null) {
                val kennung=verzeichnis.createFile("application/octet-stream",".nomedia") ?: error(".nomedia nicht anlegbar")
                if(kennung.name!=".nomedia" && !kennung.renameTo(".nomedia"))error(".nomedia nicht benennbar")
            }
        } else if(vorhanden!=null && !vorhanden.delete()) error(".nomedia nicht entfernbar")
    }
    @Synchronized fun medienschutzAnwenden(ordner:List<String>) {
        fun besuchen(verzeichnis:DocumentFile,pfad:String) {
            medienkennung(verzeichnis,pfad)
            verzeichnis.listFiles().filter {it.isDirectory}.forEach {besuchen(it,pfad+"/"+it.name.orEmpty())}
        }
        val wurzel=wurzel()
        ordner.forEach {name -> wurzel.findFile(name)?.findFile("media")?.let {besuchen(it,"$name/media")} }
    }
    /** Sucht ausschließlich; legt beim Prüfen keine Verzeichnisse an. */
    @Synchronized private fun dokumentSuchen(pfad:String):DocumentFile? {
        var dokument=wurzel()
        for(teil in pfad.split('/')) {
            require(teil.isNotEmpty() && teil!="." && teil!=".." && !teil.contains('\\'))
            dokument=dokument.findFile(teil) ?: return null
        }
        return dokument
    }
    @Synchronized fun vorhandeneGroesse(pfad:String,erwartet:Long=0):Long? {
        val dokument=dokumentSuchen(pfad) ?: return null
        if(!dokument.isFile || !dokument.canRead() || dokumentSuchen("$pfad.writing")!=null)return null
        val groesse=dokument.length()
        return groesse.takeIf {it>0 && (erwartet<=0 || it==erwartet)}
    }
    @Synchronized fun vorhandeneZuordnungen(ordner:String):Map<String,Archivdatei> {
        val pfad="$ordner/media/media.db.csv"
        val dokument=dokumentSuchen(pfad) ?: return emptyMap()
        val text=umgebung.contentResolver.openInputStream(dokument.uri)?.bufferedReader()?.use {it.readText()} ?: error("Medienzuordnung nicht lesbar")
        return Archivverzeichnis.lesen(text)
    }
    @Synchronized fun textLesen(pfad:String):String? {
        val dokument=verzeichnisSuchen(pfad.substringBeforeLast('/',"")).findFile(pfad.substringAfterLast('/')) ?: return null
        return umgebung.contentResolver.openInputStream(dokument.uri)?.bufferedReader()?.use {it.readText()}
    }
    @Synchronized fun textSchreiben(pfad:String,text:String,medientyp:String="text/html") = inhaltSchreiben(pfad,text.byteInputStream(),medientyp)
    /** Erst vollständig zwischenspeichern, dann die Zieldatei ersetzen. */
    @Synchronized fun inhaltSchreiben(pfad:String,eingabe:InputStream,medientyp:String) {
        eingabe.use {quelle ->
            val verzeichnis=verzeichnisSuchen(pfad.substringBeforeLast('/',""));val name=pfad.substringAfterLast('/')
            require(!name.contains('\\') && name!="..")
            val zwischenname="$name.writing"
            verzeichnis.findFile(zwischenname)?.delete()
            val zwischendatei=verzeichnis.createFile(medientyp.ifEmpty {"application/octet-stream"},zwischenname) ?: error("Datei nicht anlegbar")
            try {
                umgebung.contentResolver.openOutputStream(zwischendatei.uri,"wt")?.use {quelle.copyTo(it)} ?: error("Datei nicht schreibbar")
                val vorherig=verzeichnis.findFile(name)
                // Die vorherige Fassung wurde vor dem Ersetzen gesichert.
                if(vorherig!=null && !vorherig.delete()) error("Vorherige Datei nicht ersetzbar")
                if(!zwischendatei.renameTo(name)) {
                    val zieldatei=verzeichnis.createFile(medientyp.ifEmpty {"application/octet-stream"},name) ?: error("Zieldatei nicht anlegbar")
                    umgebung.contentResolver.openInputStream(zwischendatei.uri)!!.use {i -> umgebung.contentResolver.openOutputStream(zieldatei.uri,"wt")!!.use {o -> i.copyTo(o)} }
                    zwischendatei.delete()
                }
            } catch(e:Exception) { throw java.io.IOException("Archiv schreiben: ${e.message}",e) }
        }
    }
    fun dateiKopieren(pfad:String,datei:File,medientyp:String)=inhaltSchreiben(pfad,datei.inputStream(),medientyp)
    fun zugriffPruefen() { verzeichnisSuchen("") }
}

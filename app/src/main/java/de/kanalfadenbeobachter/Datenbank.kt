package de.kanalfadenbeobachter
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.kanalfadenbeobachter.kern.*


data class BeobachteterFaden(val schluessel: String, val faden: FadenAdresse, val titel: String, val aktiv: Boolean, val status: String, val pruefsumme: String, val abgerufen: Long, val anzahl: Int, val fassungen: Int, val kennungen: Set<String>, val versionsmarke: String, val veraendert: String)
data class Medienauftrag(val fadenschluessel: String, val adresse: String, val pfad: String, val medientyp: String, val versuche: Int, val erwarteteGroesse:Long=0)
class Datenbank(umgebung: Context) : SQLiteOpenHelper(umgebung, "archive.db", null, 1) {
    override fun onConfigure(datenbank: SQLiteDatabase) { datenbank.setForeignKeyConstraintsEnabled(true); datenbank.enableWriteAheadLogging() }
    override fun onCreate(datenbank: SQLiteDatabase) {
        datenbank.execSQL("CREATE TABLE threads (key TEXT PRIMARY KEY, board TEXT NOT NULL, id TEXT NOT NULL UNIQUE, title TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL DEFAULT 'Bereit', hash TEXT NOT NULL DEFAULT '', fetched INTEGER NOT NULL DEFAULT 0, count INTEGER NOT NULL DEFAULT 0, revisions INTEGER NOT NULL DEFAULT 0, ids TEXT NOT NULL DEFAULT '', etag TEXT NOT NULL DEFAULT '', modified TEXT NOT NULL DEFAULT '')")
        datenbank.execSQL("CREATE TABLE media (thread TEXT NOT NULL REFERENCES threads(key), url TEXT NOT NULL, path TEXT NOT NULL, mime TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, next INTEGER NOT NULL DEFAULT 0, bytes INTEGER NOT NULL DEFAULT 0, sha TEXT NOT NULL DEFAULT '', downloaded TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '', PRIMARY KEY(thread,url))")
        datenbank.execSQL("CREATE TABLE attachments (thread TEXT NOT NULL, post TEXT NOT NULL, slot INTEGER NOT NULL, url TEXT NOT NULL, original TEXT NOT NULL, PRIMARY KEY(thread,post,slot,url), FOREIGN KEY(thread,url) REFERENCES media(thread,url))")
        datenbank.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL)")
    }
    override fun onUpgrade(datenbank: SQLiteDatabase, vorherig: Int, neu: Int) = Unit
    @Synchronized fun fadenHinzufuegen(faden: FadenAdresse) {
        Fadenaufnahme.pruefen(faden,faedenLesen().map {it.faden})
        writableDatabase.insertOrThrow("threads", null, ContentValues().apply { put("key",faden.schluessel);put("board",faden.brett);put("id",faden.kennung) })
    }
    fun faedenLesen(): List<BeobachteterFaden> = readableDatabase.rawQuery("SELECT * FROM threads ORDER BY active DESC, key", null).use { c ->
        buildList { while(c.moveToNext()) { fun s(n:String)=c.getString(c.getColumnIndexOrThrow(n))
            fun l(n:String)=c.getLong(c.getColumnIndexOrThrow(n))
            add(BeobachteterFaden(s("key"),FadenAdresse(s("board"),s("id")),s("title"),l("active")==1L,s("status"),s("hash"),l("fetched"),l("count").toInt(),l("revisions").toInt(),s("ids").split(',').filter { it.isNotEmpty() }.toSet(),s("etag"),s("modified")))
        } }
    }
    fun fadenLesen(schluessel: String) = faedenLesen().firstOrNull { it.schluessel == schluessel }
    fun aktiv(schluessel:String, aktiv:Boolean) = fadenAendern(schluessel,ContentValues().apply { put("active",if(aktiv)1 else 0);put("status",if(aktiv)"Bereit" else "Archiviert") })
    fun status(schluessel:String, text:String) = fadenAendern(schluessel,ContentValues().apply { put("status",text) })
    private fun fadenAendern(schluessel:String, werte:ContentValues) { writableDatabase.update("threads",werte,"key=?",arrayOf(schluessel)) }
    fun standSichern(s:Fadenstand, zeitpunkt:Long, versionsmarke:String, veraendert:String) {
        val datenbank=writableDatabase; datenbank.beginTransaction()
        try {
            val vorherig=fadenLesen(s.faden.schluessel)!!
            fadenAendern(s.faden.schluessel,ContentValues().apply { put("title",s.titel);put("hash",s.inhaltspruefsumme);put("fetched",zeitpunkt);put("count",s.beitraege.size);put("revisions",vorherig.fassungen+1);put("ids",s.beitraege.joinToString(","){it.kennung});put("etag",versionsmarke);put("modified",veraendert);put("status","Gesichert") })
            s.medien.forEach { m ->
                datenbank.insertWithOnConflict("media",null,ContentValues().apply {put("thread",s.faden.schluessel);put("url",m.adresse);put("path",m.pfad);put("mime",m.medientyp)},SQLiteDatabase.CONFLICT_IGNORE)
                datenbank.insertWithOnConflict("attachments",null,ContentValues().apply {put("thread",s.faden.schluessel);put("post",m.beitragskennung);put("slot",m.laufnummer);put("url",m.adresse);put("original",m.ursprungsname)},SQLiteDatabase.CONFLICT_REPLACE)
            }
            datenbank.setTransactionSuccessful()
        } finally { datenbank.endTransaction() }
    }
    fun medienpfade(schluessel:String):Map<String,String> = readableDatabase.rawQuery("SELECT url,path FROM media WHERE thread=?",arrayOf(schluessel)).use {zeilen -> buildMap {while(zeilen.moveToNext())put(zeilen.getString(0),zeilen.getString(1))} }
    fun abgeschlosseneMedien(schluessel:String):Set<String> = readableDatabase.rawQuery("SELECT url FROM media WHERE thread=? AND status='done'",arrayOf(schluessel)).use {zeilen -> buildSet {while(zeilen.moveToNext())add(zeilen.getString(0))} }
    fun naechsterMedienauftrag(): Medienauftrag? = readableDatabase.rawQuery("SELECT m.* FROM media m JOIN threads t ON t.key=m.thread WHERE t.active=1 AND m.status='pending' AND m.next<=? ORDER BY m.next,m.attempts,m.rowid LIMIT 1",arrayOf(System.currentTimeMillis().toString())).use { c ->
        if(!c.moveToFirst()) null else Medienauftrag(c.getString(c.getColumnIndexOrThrow("thread")),c.getString(c.getColumnIndexOrThrow("url")),c.getString(c.getColumnIndexOrThrow("path")),c.getString(c.getColumnIndexOrThrow("mime")),c.getInt(c.getColumnIndexOrThrow("attempts")),c.getLong(c.getColumnIndexOrThrow("bytes")))
    }
    fun erwarteteGroesseMerken(schluessel:String,adresse:String,groesse:Long) {
        if(groesse>0)writableDatabase.update("media",ContentValues().apply {put("bytes",groesse)},"thread=? AND url=? AND status='pending'",arrayOf(schluessel,adresse))
    }
    fun abgeschlossen(d:Medienauftrag, groesse:Long, sha:String) { writableDatabase.update("media",ContentValues().apply {put("status","done");put("bytes",groesse);put("sha",sha);put("downloaded",java.time.Instant.now().toString());put("error","")},"thread=? AND url=?",arrayOf(d.fadenschluessel,d.adresse)) }
    fun fehlgeschlagen(d:Medienauftrag, fehler:String, dauerhaft:Boolean=false, wiederholungNachMillisekunden:Long=0) {
        val wartezeit=maxOf(wiederholungNachMillisekunden,minOf(3600000L,30000L * (1L shl minOf(d.versuche,7))))
        writableDatabase.update("media",ContentValues().apply {put("status",if(dauerhaft)"missing" else "pending");put("attempts",d.versuche+1);put("next",System.currentTimeMillis()+wartezeit);put("error",fehler)},"thread=? AND url=?",arrayOf(d.fadenschluessel,d.adresse))
    }
    fun erneutVersuchen() { writableDatabase.execSQL("UPDATE media SET status='pending', attempts=0, next=0 WHERE status!='done'") }
    fun urspruenglicherName(d:Medienauftrag):String = readableDatabase.rawQuery("SELECT original FROM attachments WHERE thread=? AND url=? ORDER BY post,slot LIMIT 1",arrayOf(d.fadenschluessel,d.adresse)).use {c -> if(c.moveToFirst()) c.getString(0) else d.adresse.substringAfterLast('/')}
    fun medienzusammenfassung(schluessel:String):String = readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(status='done'),0),COALESCE(SUM(status='missing'),0) FROM media WHERE thread=?",arrayOf(schluessel)).use {c ->
        c.moveToFirst()
        Fortschrittstext.anzahlmeldung(c.getInt(1),c.getInt(0),c.getInt(2))
    }
    fun ausstehend(schluessel:String):Int = readableDatabase.rawQuery("SELECT count(*) FROM media WHERE thread=? AND status!='done'",arrayOf(schluessel)).use {it.moveToFirst();it.getInt(0)}
    fun wertetabelle(schluessel:String):String {
        fun q(v:String)="\""+v.replace("\"","\"\"")+"\""
        val ausgabe=StringBuilder("post_id,slot,source_url,original_name,local_path,mime_type,status,bytes,sha256,downloaded_at,error\r\n")
        readableDatabase.rawQuery("SELECT a.post,a.slot,m.url,a.original,m.path,m.mime,m.status,m.bytes,m.sha,m.downloaded,m.error FROM attachments a JOIN media m ON a.thread=m.thread AND a.url=m.url WHERE a.thread=? ORDER BY CAST(a.post AS INTEGER),a.slot",arrayOf(schluessel)).use {c -> while(c.moveToNext()) ausgabe.append((0 until c.columnCount).joinToString(","){q(c.getString(it).orEmpty())}).append("\r\n") }
        return ausgabe.toString()
    }
    fun protokoll(stufe:String, nachricht:String) {
        writableDatabase.insert("logs",null,ContentValues().apply {put("time",System.currentTimeMillis());put("level",stufe);put("message",nachricht)})
        writableDatabase.execSQL("DELETE FROM logs WHERE id <= (SELECT COALESCE(MAX(id),0)-5000 FROM logs)")
    }
    fun protokolle(hoechstzahl:Int=250):String = readableDatabase.rawQuery("SELECT time,level,message FROM logs ORDER BY id DESC LIMIT ?",arrayOf(hoechstzahl.toString())).use {c -> buildString {while(c.moveToNext())append(Zeitangaben.uhrzeit(c.getLong(0))).append(" ").append(c.getString(1)).append("  ").append(c.getString(2)).append('\n')} }
}

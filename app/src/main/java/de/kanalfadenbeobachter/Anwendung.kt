package de.kanalfadenbeobachter
import android.app.Application
import android.content.Context

class Anwendung:Application() {
    lateinit var datenbank:Datenbank; private set
    lateinit var archivspeicher:Archivspeicher; private set
    lateinit var abrufwerk:Abrufwerk; private set
    val einstellungen get()=getSharedPreferences("settings",0)
    fun faedenGeaendert() {
        abrufwerk.faedenGeaendert()
        Benachrichtigungen.aktualisieren(this)
    }
    override fun onCreate() {
        super.onCreate()
        datenbank=Datenbank(this);archivspeicher=Archivspeicher(this);abrufwerk=Abrufwerk(this)
        Benachrichtigungen.einrichten(this)
        Benachrichtigungen.hintergrund(this)
    }
}
val Context.anwendung:Anwendung get()=applicationContext as Anwendung

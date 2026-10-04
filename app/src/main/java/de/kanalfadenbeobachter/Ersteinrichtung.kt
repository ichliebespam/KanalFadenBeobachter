package de.kanalfadenbeobachter

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.view.WindowInsets
import android.widget.*

/** Die Auswahl bleibt bei Ordnerwahl und Bildschirmdrehung erhalten. */
class Ersteinrichtung:Activity() {
    private lateinit var ordneranzeige:TextView
    private lateinit var dateinamen:Spinner
    private lateinit var ausblenden:Switch
    private lateinit var zufallspause:Spinner
    private lateinit var abschliessen:Button

    override fun onCreate(zustand:Bundle?) {
        super.onCreate(zustand)
        val inhalt=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(abstand(24),abstand(24),abstand(24),abstand(24))
            setBackgroundColor(Color.rgb(16,24,32))
        }
        val bildlauf=ScrollView(this).apply {addView(inhalt);setBackgroundColor(Color.rgb(16,24,32))}
        bildlauf.setOnApplyWindowInsetsListener {ansicht,raender ->
            if(Build.VERSION.SDK_INT>=30) {
                val rand=raender.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                ansicht.setPadding(rand.left,rand.top,rand.right,rand.bottom)
            } else ansicht.setPadding(raender.systemWindowInsetLeft,raender.systemWindowInsetTop,raender.systemWindowInsetRight,raender.systemWindowInsetBottom)
            raender
        }
        fun text(wert:String,groesse:Float=16f):TextView = TextView(this).apply {
            text=wert;textSize=groesse;setTextColor(Color.WHITE);setPadding(0,abstand(12),0,abstand(8));inhalt.addView(this)
        }
        text("Willkommen bei\nKanalFadenBeobachter",26f)
        text("Wähle zuerst, wo und wie deine Fäden und Medien gespeichert werden. Du kannst diese Einstellungen später wieder öffnen.")
        text("1 · Archivordner",20f)
        ordneranzeige=text("")
        inhalt.addView(Button(this).apply {
            text="Archivordner auswählen";isAllCaps=false
            setOnClickListener {
                auswahlMerken()
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),20)
            }
        })
        text("2 · Dateinamen",20f)
        dateinamen=Spinner(this).apply {
            adapter=ArrayAdapter(this@Ersteinrichtung,android.R.layout.simple_spinner_dropdown_item,listOf("Prüfsumme der Adresse","Beitragsnummer_Originaldateiname"))
            setSelection(zustand?.getInt("dateinamen") ?: if(anwendung.einstellungen.getBoolean("beitragsnamen",false))1 else 0)
        };inhalt.addView(dateinamen)
        text("3 · Bilderübersichten",20f)
        ausblenden=Switch(this).apply {
            text="Medien ausblenden (.nomedia)";setTextColor(Color.WHITE)
            isChecked=zustand?.getBoolean("ausblenden") ?: anwendung.einstellungen.getBoolean("medienAusblenden",true)
        };inhalt.addView(ausblenden)
        text("Eine .nomedia-Datei verhindert, dass Bilderübersichten die Archivmedien aufnehmen. Die Dateien bleiben im Archiv verfügbar.",14f)
        text("4 · Zufällige Wartezeit zwischen Medien",20f)
        zufallspause=Spinner(this).apply {
            adapter=ArrayAdapter(this@Ersteinrichtung,android.R.layout.simple_spinner_dropdown_item,(0..10).map {"$it Sekunden"})
            setSelection((zustand?.getInt("zufallspause") ?: anwendung.einstellungen.getInt("zufallspause",3)).coerceIn(0,10))
        };inhalt.addView(zufallspause)
        text("Höchstdauer der zufälligen Pause: Bei 3 Sekunden wartet die Anwendung jeweils zwischen 0 und 3 Sekunden. Bereits vorhandene Dateien werden übersprungen.",14f)
        text("Nach der Einrichtung kannst du Fadenadressen hinzufügen. Erlaube Benachrichtigungen, damit du den Zustand der Überwachung auch außerhalb der Anwendung siehst.",14f)
        abschliessen=Button(this).apply {
            text="Einrichtung abschließen";isAllCaps=false
            setOnClickListener {
                runCatching {anwendung.archivspeicher.zugriffPruefen()}.onSuccess {
                    auswahlMerken()
                    anwendung.einstellungen.edit().putBoolean("eingerichtet",true).apply()
                    val weiter=Intent(this@Ersteinrichtung,Hauptansicht::class.java)
                    getIntent().getStringExtra("anfangsadresse")?.let {weiter.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,it)}
                    startActivity(weiter);finish()
                }.onFailure {Toast.makeText(this@Ersteinrichtung,"Bitte Archivordner erneut auswählen: ${it.message}",Toast.LENGTH_LONG).show()}
            }
        };inhalt.addView(abschliessen)
        setContentView(bildlauf)
        ordnerAktualisieren()
    }
    private fun abstand(wert:Int)=(wert*resources.displayMetrics.density).toInt()
    private fun auswahlMerken() {
        anwendung.einstellungen.edit().putBoolean("beitragsnamen",dateinamen.selectedItemPosition==1).putBoolean("medienAusblenden",ausblenden.isChecked).putInt("zufallspause",zufallspause.selectedItemPosition).apply()
    }
    private fun ordnerAktualisieren() {
        val adresse=anwendung.einstellungen.getString("tree",null)
        val name=adresse?.let {runCatching {DocumentFile.fromTreeUri(this,Uri.parse(it))?.name}.getOrNull()}
        ordneranzeige.text=if(adresse==null)"Noch kein Ordner ausgewählt" else "Ausgewählter Ordner: ${name ?: "Archivordner"}"
        abschliessen.isEnabled=adresse!=null
    }
    override fun onSaveInstanceState(ausgabe:Bundle) {
        ausgabe.putInt("zufallspause",zufallspause.selectedItemPosition)
        ausgabe.putInt("dateinamen",dateinamen.selectedItemPosition);ausgabe.putBoolean("ausblenden",ausblenden.isChecked)
        super.onSaveInstanceState(ausgabe)
    }
    @Deprecated("Vorgegebene Android-Schnittstelle der nativen Ansicht")
    override fun onActivityResult(anfrage:Int,ergebnis:Int,rueckgabe:Intent?) {
        super.onActivityResult(anfrage,ergebnis,rueckgabe)
        if(anfrage!=20 || ergebnis!=RESULT_OK)return
        val adresse=rueckgabe?.data ?: return
        val vorherig=anwendung.einstellungen.getString("tree",null)
        runCatching {
            val rechte=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            require(rueckgabe.flags and rechte==rechte) {"Lese- und Schreibzugriff erforderlich"}
            contentResolver.takePersistableUriPermission(adresse,rechte)
            anwendung.einstellungen.edit().putString("tree",adresse.toString()).apply()
            anwendung.archivspeicher.zwischenspeicherLeeren();anwendung.archivspeicher.zugriffPruefen()
        }.onFailure {
            anwendung.einstellungen.edit().putString("tree",vorherig).apply()
            anwendung.archivspeicher.zwischenspeicherLeeren()
            Toast.makeText(this,"Ordner nicht verfügbar: ${it.message}",Toast.LENGTH_LONG).show()
        }
        ordnerAktualisieren()
    }
}

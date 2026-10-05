package de.kanalfadenbeobachter
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import de.kanalfadenbeobachter.kern.FadenAdresse


class Hauptansicht:Activity() {
    private val arbeitsbereich=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var liste:LinearLayout
    private lateinit var protokoll:TextView
    private lateinit var zustand:TextView
    private lateinit var protokollfenster:ScrollView
    private val ausgewaehlt=mutableSetOf<String>()
    private var erneuerungsauftrag:Job?=null
    private var letzteZeilen=""
    private var letztesProtokoll=""
    private val hervorhebung=Color.rgb(100,216,203)
    private val zurueckhaltend=Color.rgb(159,182,197)
    override fun onCreate(gespeicherterZustand:Bundle?) {
        super.onCreate(gespeicherterZustand)
        if(!anwendung.einstellungen.getBoolean("eingerichtet",false)) {
            startActivity(Intent(this,Ersteinrichtung::class.java).putExtra("anfangsadresse",getIntent().getStringExtra(Intent.EXTRA_TEXT)))
            finish();return
        }
        ausgewaehlt.addAll(gespeicherterZustand?.getStringArrayList("selected").orEmpty())
        val wurzel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(8));setBackgroundColor(Color.rgb(16,24,32))}
        wurzel.setOnApplyWindowInsetsListener {v,i ->
            if(Build.VERSION.SDK_INT>=30){val b=i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout());v.setPadding(dp(16)+b.left,dp(12)+b.top,dp(16)+b.right,dp(8)+b.bottom)}
            else v.setPadding(dp(16)+i.systemWindowInsetLeft,dp(12)+i.systemWindowInsetTop,dp(16)+i.systemWindowInsetRight,dp(8)+i.systemWindowInsetBottom)
            i
        }
        wurzel.addView(TextView(this).apply {text="KanalFadenBeobachter";textSize=24f;setTextColor(Color.WHITE);typeface=android.graphics.Typeface.DEFAULT_BOLD})
        zustand=TextView(this).apply {textSize=12f;setTextColor(hervorhebung);setPadding(0,dp(8),0,dp(4))}
        wurzel.addView(beschriftung("ÜBERWACHTE FÄDEN"))
        liste=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        wurzel.addView(ScrollView(this).apply {addView(liste)},LinearLayout.LayoutParams(-1,0,1.1f))
        wurzel.addView(zeile(schaltflaeche("+ Adresse"){hinzufuegedialog()},schaltflaeche("Einfügen"){einfuegen()},schaltflaeche("Entfernen"){ausgewaehlteEntfernen()}))
        wurzel.addView(beschriftung("PROTOKOLL"))
        protokoll=TextView(this).apply {textSize=11f;setTextColor(zurueckhaltend);typeface=android.graphics.Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(8),dp(8),dp(8),dp(8))}
        protokollfenster=ScrollView(this).apply {setBackgroundColor(Color.rgb(10,18,24));addView(protokoll)}
        wurzel.addView(protokollfenster,LinearLayout.LayoutParams(-1,0,1f))
        wurzel.addView(zustand)
        wurzel.addView(zeile(schaltflaeche("Einzelabruf"){ausfuehren(true)},schaltflaeche("Intervallabruf"){betriebsartWaehlen()}))
        wurzel.addView(zeile(schaltflaeche("Anhalten"){Ueberwachung.anhalten(this);anwendung.datenbank.protokoll("HINWEIS","Anhalten angefordert")},schaltflaeche("Einstellungen"){einstellungsdialog()}))
        setContentView(wurzel)
        if(gespeicherterZustand==null)geteilteAdresseUebernehmen(getIntent())
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),11)
    }
    override fun onSaveInstanceState(ausgabe:Bundle) {ausgabe.putStringArrayList("selected",ArrayList(ausgewaehlt));super.onSaveInstanceState(ausgabe)}
    override fun onNewIntent(absicht:Intent) {super.onNewIntent(absicht);setIntent(absicht);geteilteAdresseUebernehmen(getIntent())}
    private fun geteilteAdresseUebernehmen(i:Intent) {if(i.action==Intent.ACTION_SEND)i.getStringExtra(Intent.EXTRA_TEXT)?.let {hinzufuegedialog(it)}}
    override fun onResume() {super.onResume();if(!::zustand.isInitialized)return;erneuerungsauftrag=arbeitsbereich.launch {while(isActive){erneuern();delay(1000)}}}
    override fun onRequestPermissionsResult(anfrage:Int,berechtigungen:Array<out String>,ergebnisse:IntArray) {
        super.onRequestPermissionsResult(anfrage,berechtigungen,ergebnisse)
        if(anfrage==11)Benachrichtigungen.hintergrund(this)
    }
    override fun onPause() {erneuerungsauftrag?.cancel();super.onPause()}
    override fun onDestroy() {arbeitsbereich.cancel();super.onDestroy()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun beschriftung(wert:String)=TextView(this).apply {text=wert;textSize=11f;setTextColor(zurueckhaltend);setPadding(0,dp(10),0,dp(6))}
    private fun schaltflaeche(wert:String,aktion:()->Unit)=Button(this).apply {text=wert;textSize=12f;isAllCaps=false;minimumWidth=0;setPadding(dp(4),0,dp(4),0);setOnClickListener{aktion()}}
    private fun zeile(vararg ansichten:View)=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;ansichten.forEach {addView(it,LinearLayout.LayoutParams(0,dp(48),1f))}}
    private fun nachricht(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun erneuern() {
        val faeden=anwendung.datenbank.faedenLesen().filter {it.aktiv}
        zustand.text=when {Ueberwachungsdienst.laeuft -> anwendung.abrufwerk.fortschritt;anwendung.einstellungen.getBoolean("periodic",false)->anwendung.abrufwerk.hintergrundanzeige();else->"Gestoppt · ${faeden.size} Fäden"+anwendung.abrufwerk.uebertragungszustand.takeIf {it.isNotEmpty()}?.let {"\nZuletzt: $it"}.orEmpty()}
        val zeilen=faeden.joinToString {"${it.schluessel}|${it.titel}|${it.status}|${it.anzahl}|${it.fassungen}|${anwendung.datenbank.ausstehend(it.schluessel)}"}
        if(zeilen!=letzteZeilen || liste.childCount==0) {
            letzteZeilen=zeilen;liste.removeAllViews();ausgewaehlt.retainAll(faeden.map {it.schluessel}.toSet())
            if(faeden.isEmpty())liste.addView(TextView(this).apply {text="Noch keine aktiven Fäden.\nFüge eine Fadenadresse hinzu.";setTextColor(zurueckhaltend);setPadding(0,dp(16),0,dp(16))})
            faeden.forEach {t ->
                val auswahl=CheckBox(this).apply {
                    text="${t.faden.ordner} · /${t.faden.brett}/\n${t.titel.ifEmpty {"Noch nicht abgerufen"}}\n${t.anzahl} Beiträge · ${t.fassungen} Fassungen · ${anwendung.datenbank.ausstehend(t.schluessel)} Medien offen\n${t.status}"
                    textSize=13f;setTextColor(Color.WHITE);isChecked=t.schluessel in ausgewaehlt;setPadding(0,dp(7),0,dp(7))
                    setOnCheckedChangeListener {_,markiert -> if(markiert)ausgewaehlt.add(t.schluessel) else ausgewaehlt.remove(t.schluessel)}
                    setOnLongClickListener {AlertDialog.Builder(this@Hauptansicht).setTitle(t.faden.ordner).setMessage(t.faden.adresse+"\nLetzte Fassung: "+if(t.abgerufen>0)java.time.Instant.ofEpochMilli(t.abgerufen).toString() else "—").setPositiveButton("Im Netzbetrachter"){_,_->startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(t.faden.adresse)))}.setNegativeButton("Schließen",null).show();true}
                }
                liste.addView(auswahl)
            }
        }
        val protokolle=anwendung.datenbank.protokolle()
        if(protokolle!=letztesProtokoll) {
            val vorherigerVersatz=protokollfenster.scrollY
            val vorherigeHoehe=protokoll.height
            val neuestemFolgen=vorherigerVersatz<dp(24)
            letztesProtokoll=protokolle
            protokoll.text=protokolle
            protokollfenster.post {
                // Neueste Einträge oben halten; beim Lesen älterer Zeilen die Position bewahren.
                protokollfenster.scrollTo(0,if(neuestemFolgen) 0 else (vorherigerVersatz+protokoll.height-vorherigeHoehe).coerceAtLeast(0))
            }
        }
    }
    private fun hinzufuegedialog(anfang:String="") {
        val eingabe=EditText(this).apply {hint="https://kohlchan.net/b/res/########.html";setText(anfang);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;minLines=2}
        AlertDialog.Builder(this).setTitle("Fäden hinzufügen").setMessage("Eine oder mehrere Adressen, jeweils in einer Zeile.").setView(eingabe).setPositiveButton("Hinzufügen"){_,_->adressenHinzufuegen(eingabe.text.toString())}.setNegativeButton("Abbrechen",null).show()
    }
    private fun adressenHinzufuegen(text:String) {
        val adressen=Regex("https://kohlchan\\.net/[^\\s<>\"]+").findAll(text).map {it.value.trimEnd('.',',',')',';')}.toList()
        if(adressen.isEmpty()){nachricht("Keine Kohlchan-Fadenadresse gefunden");return}
        val fehler=mutableListOf<String>()
        var hinzugefuegt=0
        adressen.forEach {adresse ->
            runCatching {
                val faden=FadenAdresse.auswerten(adresse)
                anwendung.datenbank.fadenHinzufuegen(faden)
                anwendung.datenbank.protokoll("HINWEIS","${faden.ordner} hinzugefügt");hinzugefuegt++
            }.onFailure {
                val meldung=it.message ?: "Adresse konnte nicht hinzugefügt werden"
                fehler.add(meldung);anwendung.datenbank.protokoll("FEHLER",meldung)
            }
        }
        if(fehler.isNotEmpty())AlertDialog.Builder(this).setTitle("Adressen nicht übernommen")
            .setMessage(fehler.joinToString("\n\n")+if(hinzugefuegt>0)"\n\n$hinzugefuegt neue Fäden hinzugefügt." else "")
            .setPositiveButton("Schließen",null).show()
        if(hinzugefuegt>0)anwendung.faedenGeaendert()
        erneuern()
    }
    private fun einfuegen() {val zwischenablage=getSystemService(ClipboardManager::class.java).primaryClip;if(zwischenablage!=null && zwischenablage.itemCount>0)adressenHinzufuegen(zwischenablage.getItemAt(0).coerceToText(this).toString()) else nachricht("Zwischenablage ist leer")}
    private fun ausgewaehlteEntfernen() {
        if(ausgewaehlt.isEmpty()){nachricht("Zuerst Fäden markieren");return}
        AlertDialog.Builder(this).setTitle("${ausgewaehlt.size} Fäden entfernen?").setMessage("Beendet die Überwachung. Gespeicherte Fassungen und Medien bleiben erhalten.").setPositiveButton("Entfernen"){_,_->ausgewaehlt.forEach {anwendung.datenbank.aktiv(it,false);anwendung.datenbank.protokoll("HINWEIS","$it aus Überwachung entfernt")};ausgewaehlt.clear();anwendung.faedenGeaendert();erneuern()}.setNegativeButton("Abbrechen",null).show()
    }
    private fun bereit():Boolean {
        if(anwendung.einstellungen.getString("tree",null)==null){nachricht("Bitte zuerst einen Archivordner wählen");ordner();return false}
        if(anwendung.datenbank.faedenLesen().none {it.aktiv}){nachricht("Bitte zuerst einen Faden hinzufügen");return false}
        return true
    }
    private fun ausfuehren(einmalig:Boolean) {if(bereit())runCatching {Ueberwachung.beginn(this,einmalig)}.onFailure {nachricht("Start fehlgeschlagen: ${it.message}")}}
    private fun betriebsartWaehlen() {
        if(!bereit())return
        AlertDialog.Builder(this).setTitle("Intervallabruf").setItems(arrayOf("Aktive Sitzung · ${anwendung.einstellungen.getInt("seconds",60)} Sekunden","Hintergrundplan · ${anwendung.einstellungen.getInt("periodicMinutes",15)} Minuten")){_,auswahl ->if(auswahl==0)ausfuehren(false)else Ueberwachung.regelmaessig(this)}.setNegativeButton("Abbrechen",null).show()
    }
    private fun ordner() {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),20)}
    private fun einstellungsdialog() {
        val feld=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),0)}
        fun zahl(titel:String,wert:Int):EditText {feld.addView(beschriftung(titel));return EditText(this).apply {inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(wert.toString());feld.addView(this)}}
        val sekunden=zahl("AKTIVE SITZUNG: INTERVALL IN SEKUNDEN (MIN. 10)",anwendung.einstellungen.getInt("seconds",60))
        val minuten=zahl("HINTERGRUNDPLAN: MINUTEN (MIN. 15)",anwendung.einstellungen.getInt("periodicMinutes",15))
        val lesefrist=zahl("LESEWARTEZEIT IN SEKUNDEN (MIN. 30)",anwendung.einstellungen.getInt("readTimeout",180))
        feld.addView(beschriftung("ZUFÄLLIGE WARTEZEIT ZWISCHEN MEDIEN: HÖCHSTDAUER"))
        val zufallspause=Spinner(this).apply {
            adapter=ArrayAdapter(this@Hauptansicht,android.R.layout.simple_spinner_dropdown_item,(0..10).map {"$it Sekunden"})
            setSelection(anwendung.einstellungen.getInt("zufallspause",3).coerceIn(0,10))
        };feld.addView(zufallspause)
        val medienAusblenden=Switch(this).apply {text="Medien in Bilderübersichten ausblenden (.nomedia)";isChecked=anwendung.einstellungen.getBoolean("medienAusblenden",true)};feld.addView(medienAusblenden)
        feld.addView(beschriftung("DATEINAMEN NEU ERFASSTER MEDIEN"))
        val dateinamen=Spinner(this).apply {
            adapter=ArrayAdapter(this@Hauptansicht,android.R.layout.simple_spinner_dropdown_item,listOf("Prüfsumme (bisher)","Beitragsnummer_Originaldateiname"))
            setSelection(if(anwendung.einstellungen.getBoolean("beitragsnamen",false))1 else 0)
        };feld.addView(dateinamen)
        feld.addView(TextView(this).apply {text="Bereits zugeordnete Dateinamen bleiben erhalten. Medien werden immer einzeln nacheinander geladen.";textSize=12f;setTextColor(zurueckhaltend)})
        val ungetaktet=CheckBox(this).apply {text="Nur ungetaktete Netze (z. B. WLAN)";isChecked=anwendung.einstellungen.getBoolean("wifiOnly",false)};feld.addView(ungetaktet)
        feld.addView(schaltflaeche("Archivordner auswählen / erneut freigeben"){ordner()})
        feld.addView(schaltflaeche("Fehlgeschlagene Medien erneut versuchen"){anwendung.datenbank.erneutVersuchen();nachricht("Fehlgeschlagene Medien zurück in Warteschlange")})
        feld.addView(schaltflaeche("Entfernte Fäden wiederherstellen"){entfernteWiederherstellen()})
        feld.addView(schaltflaeche("Protokoll ausgeben"){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"KanalFadenBeobachter-protokoll.txt"),21)})
        feld.addView(TextView(this).apply {text="Aktive Sitzungen enden nach spätestens 5 Stunden und können durch Android früher beendet werden. Hintergrundintervalle sind ungenau. Anhalten beendet beide Modi.\n\nArchivordner: "+(anwendung.einstellungen.getString("tree",null) ?: "Noch nicht gewählt");textSize=12f;setTextColor(zurueckhaltend)})
        AlertDialog.Builder(this).setTitle("Einstellungen").setView(ScrollView(this).apply {addView(feld)}).setPositiveButton("Speichern"){_,_->
            anwendung.einstellungen.edit().putInt("seconds",sekunden.text.toString().toIntOrNull()?.coerceIn(10,86400) ?: 60).putInt("periodicMinutes",minuten.text.toString().toIntOrNull()?.coerceIn(15,1440) ?: 15).putInt("readTimeout",lesefrist.text.toString().toIntOrNull()?.coerceIn(30,1800) ?: 180).putBoolean("wifiOnly",ungetaktet.isChecked).putInt("zufallspause",zufallspause.selectedItemPosition).putBoolean("medienAusblenden",medienAusblenden.isChecked).putBoolean("beitragsnamen",dateinamen.selectedItemPosition==1).apply()
            arbeitsbereich.launch(Dispatchers.IO) {runCatching {anwendung.archivspeicher.medienschutzAnwenden(anwendung.datenbank.faedenLesen().map {it.faden.ordner})}.onFailure {anwendung.datenbank.protokoll("FEHLER","Medienausblendung: ${it.message}")}}
            if(anwendung.einstellungen.getBoolean("periodic",false))Ueberwachung.regelmaessig(this)
        }.setNegativeButton("Schließen",null).show()
    }
    private fun entfernteWiederherstellen() {val eintraege=anwendung.datenbank.faedenLesen().filter {!it.aktiv};if(eintraege.isEmpty()){nachricht("Keine archivierten Fäden");return};AlertDialog.Builder(this).setTitle("Faden reaktivieren").setItems(eintraege.map {"${it.faden.ordner} · ${it.titel}"}.toTypedArray()){_,i->anwendung.datenbank.aktiv(eintraege[i].schluessel,true);anwendung.faedenGeaendert();erneuern()}.show()}
    @Deprecated("Vorgegebene Android-Schnittstelle der nativen Ansicht")
    override fun onActivityResult(anfrage:Int,ergebnis:Int,rueckgabe:Intent?) {
        super.onActivityResult(anfrage,ergebnis,rueckgabe)
        if(ergebnis!=RESULT_OK)return
        val ressourcenadresse=rueckgabe?.data ?: return
        if(anfrage==20) {
            val vorherig=anwendung.einstellungen.getString("tree",null)
            if(vorherig!=null && vorherig!=ressourcenadresse.toString() && anwendung.datenbank.faedenLesen().any {it.fassungen>0}){nachricht("Archiv enthält Daten. Bitte bisherigen Ordner erneut wählen; Ordnerumzug wird noch nicht unterstützt.");return}
            if(Ueberwachungsdienst.laeuft || anwendung.einstellungen.getBoolean("periodic",false)){nachricht("Vor der Ordnerauswahl bitte Anhalten drücken");return}
            runCatching {
                require(rueckgabe.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && rueckgabe.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {"Lese- und Schreibzugriff erforderlich"}
                contentResolver.takePersistableUriPermission(ressourcenadresse,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                anwendung.einstellungen.edit().putString("tree",ressourcenadresse.toString()).apply();anwendung.archivspeicher.zwischenspeicherLeeren();anwendung.archivspeicher.zugriffPruefen();nachricht("Archivordner eingerichtet")
            }.onFailure {nachricht("Ordner nicht verfügbar: ${it.message}")}
        } else if(anfrage==21)arbeitsbereich.launch {
            runCatching {withContext(Dispatchers.IO){contentResolver.openOutputStream(ressourcenadresse,"wt")!!.bufferedWriter().use {it.write(anwendung.datenbank.protokolle(5000))}}}.onSuccess {nachricht("Protokoll ausgegeben")}.onFailure {nachricht(it.message.orEmpty())}
        }
    }
}

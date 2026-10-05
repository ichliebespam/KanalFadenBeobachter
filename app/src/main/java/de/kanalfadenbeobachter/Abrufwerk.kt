package de.kanalfadenbeobachter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import de.kanalfadenbeobachter.kern.*
import okhttp3.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


class HttpFehler(val antwortnummer:Int,val wiederholungMillisekunden:Long=0):IOException("HTTP $antwortnummer")
private class FadenEntfernt:IOException("Faden aus Überwachung entfernt")
class Abrufwerk(private val anwendung:Anwendung) {
    private val laufendeAnfragen=java.util.concurrent.ConcurrentHashMap<String,Call>()
    @Volatile private var aktuellerFaden:String?=null
    fun faedenGeaendert() {
        val aktive=anwendung.datenbank.faedenLesen().filter {it.aktiv}.map {it.schluessel}.toSet()
        laufendeAnfragen.forEach { (schluessel,aufruf) -> if(schluessel !in aktive)aufruf.cancel() }
        if(aktuellerFaden?.let {it !in aktive}==true) {
            pruefzustand="Faden entfernt · übrige Fäden werden weiterbearbeitet"
            uebertragungszustand=pruefzustand
        }
    }
    private fun aktivPruefen(schluessel:String) {
        if(anwendung.datenbank.fadenLesen(schluessel)?.aktiv!=true)throw FadenEntfernt()
    }
    private val pruefsperre=Mutex(); private val mediensperre=Mutex()
    private val tabellensperre=Any()
    private val abrufsperre=Mutex()
    val pruefungLaeuft=AtomicBoolean(false)
    @Volatile private var pruefzustand="Bereit"
    @Volatile var uebertragungszustand=""; private set
    @Volatile private var mediumBeschaeftigt=false
    val beschaeftigt:Boolean get()=pruefungLaeuft.get() || mediumBeschaeftigt
    @Volatile var naechsterAbruf:Long=0
    @Volatile private var naechstesMedium:Long=0
    val fortschritt:String get() {
        val zaehler=if(naechsterAbruf>0 && !pruefungLaeuft.get()) "Nächster Abruf in ${Zeitangaben.restsekunden(naechsterAbruf,android.os.SystemClock.elapsedRealtime())} Sekunden" else ""
        val meldung=when {
        aktuellerFaden?.let {anwendung.datenbank.fadenLesen(it)?.aktiv!=true}==true -> "Faden entfernt · übrige Fäden werden weiterbearbeitet"
        mediumBeschaeftigt -> uebertragungszustand
        pruefungLaeuft.get() -> pruefzustand
        else -> uebertragungszustand.ifEmpty {pruefzustand}
        }
        return if(zaehler.isEmpty())meldung else if(mediumBeschaeftigt)"$zaehler\n$meldung" else zaehler
    }
    fun hintergrundanzeige():String {
        if(beschaeftigt)return fortschritt
        val ziel=anwendung.einstellungen.getLong("naechsterHintergrundabruf",0)
        val rest=Zeitangaben.restsekunden(ziel,System.currentTimeMillis())
        return if(rest>0)"Nächster Abruf frühestens in $rest Sekunden (Android bestimmt den Zeitpunkt)" else "Abruf fällig · warte auf Android"
    }
    private val verbindungsgrundlage=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(30,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).build()
    private fun verbindung()=verbindungsgrundlage.newBuilder().readTimeout(anwendung.einstellungen.getInt("readTimeout",180).toLong(),TimeUnit.SECONDS).build()
    fun netzabrufAbbrechen() {verbindungsgrundlage.dispatcher.cancelAll()}
    private suspend fun <T> antwort(anfrage:Request, verarbeitung:suspend (Response)->T):T = coroutineScope {
        val aufruf=verbindung().newCall(anfrage)
        val schluessel=requireNotNull(anfrage.tag(String::class.java))
        laufendeAnfragen[schluessel]=aufruf
        val abbruch=launch(start=CoroutineStart.UNDISPATCHED) { try {awaitCancellation()} finally {aufruf.cancel()} }
        try {
            aktivPruefen(schluessel)
            withContext(Dispatchers.IO) { aufruf.execute().use {aktivPruefen(schluessel);verarbeitung(it)} }
        } catch(e:IOException) {
            currentCoroutineContext().ensureActive()
            if(aufruf.isCanceled())throw FadenEntfernt()
            throw e
        } finally {laufendeAnfragen.remove(schluessel,aufruf);abbruch.cancelAndJoin()}
    }
    private fun anfrage(adresse:String)=Request.Builder().url(adresse).header("User-Agent","KanalFadenBeobachter/0.3.5 (Android; privates Fadenarchiv)").header("Accept-Encoding","identity")
    private fun wiederholungMillisekunden(r:Response):Long {
        val h=r.header("Retry-After") ?: return 0
        return h.toLongOrNull()?.coerceIn(0,86400)?.times(1000) ?: runCatching { (java.time.ZonedDateTime.parse(h,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis()).coerceIn(0,86400000) }.getOrDefault(0)
    }
    private fun netzzugriffErlaubt():Boolean {
        val verbindungsverwaltung=anwendung.getSystemService(android.net.ConnectivityManager::class.java)
        val netz=verbindungsverwaltung.activeNetwork ?: return false
        val eigenschaften=verbindungsverwaltung.getNetworkCapabilities(netz) ?: return false
        return eigenschaften.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && (!anwendung.einstellungen.getBoolean("wifiOnly",false) || !verbindungsverwaltung.isActiveNetworkMetered)
    }
    suspend fun abrufAusfuehren(bezeichnung:String) {
        abrufsperre.lock()
        val bilanz=Abrufbilanz()
        var abgeschlossen=false
        try {
            uebertragungszustand=""
            val geprueft=mutableSetOf<String>()
            while(true) {
                currentCoroutineContext().ensureActive()
                alleFaedenPruefen(bilanz,geprueft)
                if(!einzelnesMediumLaden(bilanz)) {
                    if(anwendung.datenbank.faedenLesen().any {it.aktiv && it.schluessel !in geprueft})continue
                    break
                }
            }
            abgeschlossen=true
        } finally {
            try {
                bilanz.meldung(bezeichnung,abgeschlossen)?.let {anwendung.datenbank.protokoll("BILANZ",it);uebertragungszustand=it}
            } finally {aktuellerFaden=null;abrufsperre.unlock()}
        }
    }
    private suspend fun alleFaedenPruefen(bilanz:Abrufbilanz,geprueft:MutableSet<String>) {
        if(anwendung.datenbank.faedenLesen().none {it.aktiv && it.schluessel !in geprueft})return
        pruefsperre.lock()
        pruefungLaeuft.set(true)
        try {
            anwendung.archivspeicher.zugriffPruefen()
            if(!netzzugriffErlaubt()) {geprueft.addAll(anwendung.datenbank.faedenLesen().filter {it.aktiv}.map {it.schluessel});pruefzustand="Warte auf erlaubtes Netzwerk";return}
            while(true) {
                val t=anwendung.datenbank.faedenLesen().firstOrNull {it.aktiv && it.schluessel !in geprueft} ?: break
                geprueft.add(t.schluessel)
                aktuellerFaden=t.schluessel
                currentCoroutineContext().ensureActive()
                if(anwendung.einstellungen.getLong("retry:${t.schluessel}",0)>System.currentTimeMillis())continue
                pruefzustand="Prüfe ${t.faden.ordner}"
                anwendung.datenbank.status(t.schluessel,"Abruf …")
                try {
                    val anforderung=anfrage(t.faden.adresse).tag(String::class.java,t.schluessel).header("Accept","text/html")
                    if(t.pruefsumme.isNotEmpty()) {if(t.versionsmarke.isNotEmpty())anforderung.header("If-None-Match",t.versionsmarke);if(t.veraendert.isNotEmpty())anforderung.header("If-Modified-Since",t.veraendert)}
                    antwort(anforderung.build()) {r ->
                        when(r.code) {404 -> throw NichtGefunden();304 -> {anwendung.datenbank.status(t.schluessel,"Unverändert · ${uhrzeit()}");anwendung.datenbank.protokoll("HINWEIS","${t.faden.ordner}: unverändert · vom Anbieter bestätigt (HTTP 304)");return@antwort}}
                        if(!r.isSuccessful)throw HttpFehler(r.code,wiederholungMillisekunden(r))
                        val inhalt=r.body ?: error("Leere Antwort")
                        val groesse=inhalt.byteStream().use { eingabe ->
                            val ausgabe=java.io.ByteArrayOutputStream()
                            val puffer=ByteArray(32768)
                            while(ausgabe.size()<=16*1024*1024) {
                                aktivPruefen(t.schluessel)
                                currentCoroutineContext().ensureActive()
                                val n=eingabe.read(puffer)
                                if(n<0)break
                                bilanz.seiteEmpfangen(n.toLong())
                                ausgabe.write(puffer,0,n)
                            }
                            ausgabe.toByteArray()
                        }
                        if(groesse.size>16*1024*1024)throw UngueltigeSeite("HTML größer als 16 MiB")
                        val rohtext=groesse.toString(inhalt.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        if(!rohtext.trimEnd().endsWith("</html>",true))throw UngueltigeSeite("HTML unvollständig (Abschluss fehlt)")
                        val vorhandene=anwendung.archivspeicher.vorhandeneZuordnungen(t.faden.ordner)
                        val pfade=vorhandene.mapValues {it.value.pfad}+anwendung.datenbank.medienpfade(t.schluessel)
                        val stand=MedienDateinamen.zuordnen(FadenAuswertung.auswerten(t.faden,rohtext),pfade,anwendung.einstellungen.getBoolean("beitragsnamen",false))
                        if(anwendung.datenbank.fadenLesen(t.schluessel)?.aktiv!=true)return@antwort
                        if(stand.inhaltspruefsumme!=t.pruefsumme) {
                            val jetzt=System.currentTimeMillis()
                            val seitentext=ArchivDarstellung.darstellen(stand,Instant.ofEpochMilli(jetzt).toString())
                            if(t.pruefsumme.isNotEmpty()) {
                                val vorherigerStand=standdatei(t.schluessel,t.pruefsumme).takeIf {it.exists()}?.readText().orEmpty()
                                check(vorherigerStand.isNotEmpty()) {"Vorheriger Archivstand fehlt"}
                                anwendung.archivspeicher.textSchreiben("${t.faden.ordner}/html/old/${t.faden.ordner}_${zeitstempel(t.abgerufen)}.html",ArchivDarstellung.zurAltenFassung(vorherigerStand))
                            }
                            if(t.pruefsumme.isEmpty()) {
                                anwendung.archivspeicher.textLesen("${t.faden.ordner}/html/${t.faden.ordner}.html")?.let {alt ->
                                    anwendung.archivspeicher.textSchreiben("${t.faden.ordner}/html/old/${t.faden.ordner}_uebernommen_${zeitstempel(jetzt)}.html",ArchivDarstellung.zurAltenFassung(alt))
                                }
                                anwendung.archivspeicher.textLesen("${t.faden.ordner}/media/media.db.csv")?.let {alt ->
                                    anwendung.archivspeicher.textSchreiben("${t.faden.ordner}/media/media.db_vor_uebernahme_${zeitstempel(jetzt)}.csv",alt,"text/csv")
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            val zwischengespeichert=standdatei(t.schluessel,stand.inhaltspruefsumme)
                            val zwischenablageDatei=File(zwischengespeichert.parentFile,zwischengespeichert.name+".tmp")
                            zwischenablageDatei.writeText(seitentext)
                            check(zwischenablageDatei.renameTo(zwischengespeichert)) {"Lokaler Fassungsstand nicht sicherbar"}
                            anwendung.archivspeicher.textSchreiben("${t.faden.ordner}/html/${t.faden.ordner}.html",seitentext)
                            anwendung.datenbank.standSichern(stand,jetzt,r.header("ETag").orEmpty(),r.header("Last-Modified").orEmpty())
                            // Erfolgreich archivierte Medien bleiben erledigt; nur offene Medien am Archiv prüfen.
                            val abgeschlossene=anwendung.datenbank.abgeschlosseneMedien(t.schluessel)
                            for(medium in stand.medien.distinctBy {it.adresse}) {
                                currentCoroutineContext().ensureActive()
                                if(medium.adresse in abgeschlossene)continue
                                val alt=vorhandene[medium.adresse]
                                anwendung.datenbank.erwarteteGroesseMerken(t.schluessel,medium.adresse,alt?.groesse ?: 0)
                                val groesse=anwendung.archivspeicher.vorhandeneGroesse("${t.faden.ordner}/${medium.pfad}",alt?.groesse ?: 0) ?: continue
                                anwendung.datenbank.abgeschlossen(Medienauftrag(t.schluessel,medium.adresse,medium.pfad,medium.medientyp,0),groesse,alt?.pruefsumme.orEmpty())
                                anwendung.datenbank.protokoll("MEDIUM","${t.faden.ordner}: bereits vorhanden · ${medium.pfad} · ${Uebertragungsangaben.groesse(groesse)} · nicht erneut geladen")
                            }
                            if(t.pruefsumme.isNotEmpty())standdatei(t.schluessel,t.pruefsumme).delete()
                            val kennungen=stand.beitraege.map {it.kennung}.toSet()
                            anwendung.datenbank.protokoll("FASSUNG","${t.faden.ordner}: Fassung ${t.fassungen+1}, +${(kennungen-t.kennungen).size}/−${(t.kennungen-kennungen).size} Beiträge, ${stand.medien.size} Anhänge")
                        } else {anwendung.datenbank.status(t.schluessel,"Unverändert · ${uhrzeit()}");anwendung.datenbank.protokoll("HINWEIS","${t.faden.ordner}: unverändert · empfangenen Inhalt verglichen (HTTP ${r.code})")}
                    }
                    wertetabelleSchreiben(t.schluessel)
                    anwendung.einstellungen.edit().remove("retry:${t.schluessel}").remove("failures:${t.schluessel}").apply()
                } catch(e:CancellationException) {throw e}
                catch(e:FadenEntfernt) {anwendung.datenbank.protokoll("HINWEIS","${t.faden.ordner}: Abruf wegen Entfernung beendet")}
                catch(e:NichtGefunden) {anwendung.datenbank.aktiv(t.schluessel,false);anwendung.faedenGeaendert();anwendung.datenbank.status(t.schluessel,"404 · Überwachung beendet");anwendung.datenbank.protokoll("FEHLER","${t.faden.ordner}: 404, aus Überwachung entfernt. Archiv bleibt erhalten.")}
                catch(e:Exception) {
                    val fehlschlaege=anwendung.einstellungen.getInt("failures:${t.schluessel}",0)+1
                    val pause=maxOf((e as? HttpFehler)?.wiederholungMillisekunden ?: 0, minOf(3600000L,15000L*(1L shl minOf(fehlschlaege,8))))
                    anwendung.einstellungen.edit().putInt("failures:${t.schluessel}",fehlschlaege).putLong("retry:${t.schluessel}",System.currentTimeMillis()+pause).apply()
                    anwendung.datenbank.status(t.schluessel,"Fehler · neuer Versuch später")
                    anwendung.datenbank.protokoll("FEHLER","${t.faden.ordner}: ${e.message}; Wiederholung frühestens in ${pause/1000}s")
                }
            }
        } finally {pruefungLaeuft.set(false);pruefsperre.unlock();pruefzustand="Prüfung abgeschlossen"}
    }
    private fun standdatei(schluessel:String, pruefsumme:String):File {
        val verzeichnis=File(anwendung.filesDir,"snapshots").apply {mkdirs()}
        return File(verzeichnis,pruefsumme256(schluessel)+"_"+pruefsumme+".html")
    }
    fun wertetabelleSchreiben(schluessel:String) = synchronized(tabellensperre) {
        val t=anwendung.datenbank.fadenLesen(schluessel) ?: return@synchronized
        anwendung.archivspeicher.textSchreiben("${t.faden.ordner}/media/media.db.csv",anwendung.datenbank.wertetabelle(schluessel),"text/csv")
    }
    private suspend fun einzelnesMediumLaden(bilanz:Abrufbilanz):Boolean {
        mediensperre.lock()
        var begonnen=false
        try {
            val d=anwendung.datenbank.naechsterMedienauftrag() ?: return false
            val t=anwendung.datenbank.fadenLesen(d.fadenschluessel) ?: return true
            if(!t.aktiv)return true
            aktuellerFaden=t.schluessel
            val alt=anwendung.archivspeicher.vorhandeneZuordnungen(t.faden.ordner)[d.adresse]
            val vorhanden=anwendung.archivspeicher.vorhandeneGroesse("${t.faden.ordner}/${d.pfad}",d.erwarteteGroesse.takeIf {it>0} ?: alt?.groesse ?: 0)
            if(vorhanden!=null) {
                anwendung.datenbank.abgeschlossen(d,vorhanden,alt?.pruefsumme.orEmpty())
                uebertragungszustand="${t.faden.ordner}: bereits vorhanden · ${Uebertragungsangaben.groesse(vorhanden)}"
                anwendung.datenbank.protokoll("MEDIUM","${t.faden.ordner}: bereits vorhanden · ${d.pfad} · nicht erneut geladen")
                wertetabelleSchreiben(d.fadenschluessel)
                return true
            }
            if(!netzzugriffErlaubt())return false
            val verzeichnis=File(anwendung.filesDir,"partial").apply {mkdirs()}
            val datei=File(verzeichnis,pruefsumme256(d.adresse)+".part")
            val bestaetigung=File(verzeichnis,pruefsumme256(d.adresse)+".etag")
            val name=anwendung.datenbank.urspruenglicherName(d).replace('\n',' ').replace('\r',' ').let {if(it.length>48)it.take(45)+"…" else it}
            val titel="${t.faden.ordner} · $name"
            val zusammenfassung=anwendung.datenbank.medienzusammenfassung(d.fadenschluessel)
            mediumBeschaeftigt=true
            if(anwendung.einstellungen.getInt("zufallspause",3)==0)naechstesMedium=0
            while(android.os.SystemClock.elapsedRealtime()<naechstesMedium) {
                if(anwendung.datenbank.fadenLesen(d.fadenschluessel)?.aktiv!=true)return true
                val rest=Zeitangaben.restsekunden(naechstesMedium,android.os.SystemClock.elapsedRealtime())
                uebertragungszustand="Zufällige Wartezeit: noch $rest Sekunden\nDanach: $titel\n$zusammenfassung"
                delay(minOf(250L,(naechstesMedium-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1)))
            }
            currentCoroutineContext().ensureActive()
            if(anwendung.datenbank.fadenLesen(d.fadenschluessel)?.aktiv!=true)return true
            begonnen=true
            uebertragungszustand="$titel\nVerbindung wird aufgebaut …\n$zusammenfassung"
            anwendung.datenbank.protokoll("MEDIUM","${t.faden.ordner}: Verbindung für $name wird aufgebaut")
            val uebertragungsbeginn=android.os.SystemClock.elapsedRealtime()
            var neuEmpfangen=0L
            var uebertragungsdauer=0L
            try {
                val versionsmarke=if(bestaetigung.exists())bestaetigung.readText() else ""
                val versatz=if(datei.exists() && versionsmarke.startsWith('"'))datei.length() else 0L
                if(versatz==0L && datei.exists())datei.delete()
                val anforderung=anfrage(d.adresse).tag(String::class.java,d.fadenschluessel)
                if(versatz>0)anforderung.header("Range","bytes=$versatz-").header("If-Range",versionsmarke)
                antwort(anforderung.build()) {r ->
                    if(r.code==416) {datei.delete();bestaetigung.delete();throw IOException("Ungültiger Teilstand; nächster Versuch beginnt neu")}
                    if(!r.isSuccessful)throw HttpFehler(r.code,wiederholungMillisekunden(r))
                    val inhalt=r.body ?: error("Leere Medienantwort")
                    val art=inhalt.contentType()?.toString().orEmpty()
                    if(art.startsWith("text/html"))throw IOException("HTML-Fehlerseite statt Medium")
                    val bereich=r.header("Content-Range").orEmpty()
                    val bereichstreffer=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(bereich)
                    val anhaengen=r.code==206 && versatz>0 && bereichstreffer?.groupValues?.get(1)?.toLongOrNull()==versatz
                    if(r.code==206 && !anhaengen)throw IOException("Unpassende Teilantwort")
                    val beginn=if(anhaengen)versatz else 0
                    val erwartet=inhalt.contentLength()
                    val gesamt=if(anhaengen)bereichstreffer!!.groupValues[3].toLong() else erwartet
                    anwendung.datenbank.protokoll("MEDIUM","${t.faden.ordner}: ${Uebertragungsangaben.beginn(name,gesamt,beginn.toLong(),d.versuche+1)}")
                    val frei=android.os.StatFs(verzeichnis.absolutePath).availableBytes
                    if(erwartet>0 && erwartet+16*1024*1024>frei)throw IOException("Zu wenig freier Gerätespeicher zum Zwischenspeichern")
                    val neueVersionsmarke=r.header("ETag").orEmpty()
                    if(neueVersionsmarke.startsWith('"'))bestaetigung.writeText(neueVersionsmarke) else bestaetigung.delete()
                    uebertragungszustand="$titel\n${Fortschrittstext.uebertragung(beginn,gesamt)}\n$zusammenfassung"
                    var empfangen=0L;var letzteErneuerung=0L
                    inhalt.byteStream().use {eingabe -> java.io.FileOutputStream(datei,anhaengen).use {ausgabe ->
                        val puffer=ByteArray(64*1024)
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            aktivPruefen(d.fadenschluessel)
                            val n=eingabe.read(puffer);if(n<0)break
                            bilanz.mediumEmpfangen(n.toLong());neuEmpfangen+=n
                            ausgabe.write(puffer,0,n);empfangen+=n
                            val jetzt=System.currentTimeMillis()
                            if(jetzt-letzteErneuerung>250) {uebertragungszustand="$titel\n${Fortschrittstext.uebertragung(beginn+empfangen,gesamt)}\n$zusammenfassung";letzteErneuerung=jetzt}
                        }
                        uebertragungsdauer=android.os.SystemClock.elapsedRealtime()-uebertragungsbeginn
                        ausgabe.fd.sync()
                    }}
                    if(erwartet>=0 && empfangen!=erwartet)throw IOException("Herunterladen unvollständig")
                    if(gesamt>=0 && datei.length()!=gesamt)throw IOException("Gesamtlänge stimmt nicht")
                    if(datei.length()==0L)throw IOException("Leere Mediendatei")
                }
                currentCoroutineContext().ensureActive()
                uebertragungszustand="$titel\n${Fortschrittstext.groesse(datei.length())} geladen · wird geprüft und gespeichert …\n$zusammenfassung"
                val pruefsummenrechner=MessageDigest.getInstance("SHA-256")
                datei.inputStream().use {i ->val b=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();aktivPruefen(d.fadenschluessel);val n=i.read(b);if(n<0)break;pruefsummenrechner.update(b,0,n)}}
                val pruefsumme=pruefsummenrechner.digest().joinToString(""){"%02x".format(it)}
                aktivPruefen(d.fadenschluessel)
                anwendung.archivspeicher.dateiKopieren("${t.faden.ordner}/${d.pfad}",datei,d.medientyp)
                anwendung.datenbank.abgeschlossen(d,datei.length(),pruefsumme)
                bilanz.mediumGesichert()
                uebertragungszustand="$titel\nGespeichert · ${Fortschrittstext.groesse(datei.length())}\n${anwendung.datenbank.medienzusammenfassung(d.fadenschluessel)}"
                anwendung.datenbank.protokoll("MEDIUM","${t.faden.ordner}: ${Uebertragungsangaben.gespeichert(d.pfad,neuEmpfangen,uebertragungsdauer,datei.length())}")
                datei.delete();bestaetigung.delete()
            } catch(e:CancellationException) {uebertragungszustand="$titel\nAngehalten · ${Fortschrittstext.groesse(datei.length())} zwischengespeichert\n${anwendung.datenbank.medienzusammenfassung(d.fadenschluessel)}";anwendung.datenbank.protokoll("HINWEIS","Herunterladen angehalten; Teilstand bleibt erhalten");throw e}
            catch(e:FadenEntfernt) {
                uebertragungszustand="Faden entfernt · übrige Fäden werden weiterbearbeitet"
                anwendung.datenbank.protokoll("HINWEIS","${t.faden.ordner}: Download beendet; Teilstand bleibt erhalten")
            }
            catch(e:Exception) {anwendung.datenbank.fehlgeschlagen(d,e.message.orEmpty(),(e as? HttpFehler)?.antwortnummer in listOf(404,410),(e as? HttpFehler)?.wiederholungMillisekunden ?: 0);anwendung.datenbank.protokoll("FEHLER","Herunterladen ${d.adresse.substringAfterLast('/')}: ${e.message}");uebertragungszustand="$titel\nFehler: ${e.message.orEmpty().take(100)}\n${anwendung.datenbank.medienzusammenfassung(d.fadenschluessel)}"}
            try {wertetabelleSchreiben(d.fadenschluessel)}catch(e:Exception) {anwendung.datenbank.protokoll("FEHLER","CSV: ${e.message}")}
            return true
        } finally {
            if(begonnen)naechstesMedium=android.os.SystemClock.elapsedRealtime()+Zeitangaben.zufallspause(anwendung.einstellungen.getInt("zufallspause",3))
            mediumBeschaeftigt=false;mediensperre.unlock()
        }
    }
    companion object {
        fun zeitstempel(millisekunden:Long):String=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(millisekunden))
        fun uhrzeit():String=Zeitangaben.uhrzeit(System.currentTimeMillis())
    }
}

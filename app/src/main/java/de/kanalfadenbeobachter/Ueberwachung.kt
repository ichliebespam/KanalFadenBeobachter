package de.kanalfadenbeobachter

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import de.kanalfadenbeobachter.kern.Zeitangaben

object Benachrichtigungen {
    const val KANAL="ueberwachung"
    fun einrichten(umgebung:Context) {
        umgebung.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(KANAL,"Überwachung",NotificationManager.IMPORTANCE_LOW))
    }
    fun erzeugen(umgebung:Context,text:String):Notification {
        val oeffnen=PendingIntent.getActivity(umgebung,0,Intent(umgebung,Hauptansicht::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val anhalten=PendingIntent.getBroadcast(umgebung,1,Intent(umgebung,AnhalteEmpfaenger::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(umgebung,KANAL).setSmallIcon(R.drawable.archivzeichen)
            .setContentTitle("KanalFadenBeobachter").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(oeffnen)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null,"Anhalten",anhalten).build()).build()
    }
    fun anzeigen(umgebung:Context,kennung:Int,text:String) {
        runCatching {umgebung.getSystemService(NotificationManager::class.java).notify(kennung,erzeugen(umgebung,text))}
    }
    @Volatile var hintergrundLaeuft=false
    @Synchronized fun aktualisieren(umgebung:Context) {
        if(Ueberwachungsdienst.laeuft) {
            val anzahl=umgebung.anwendung.datenbank.faedenLesen().count {it.aktiv}
            val text=if(anzahl==0) "Keine aktiven Fäden · Sitzung wartet" else
                "Aktive Sitzung · $anzahl Fäden\n${umgebung.anwendung.abrufwerk.fortschritt}"
            anzeigen(umgebung,41,text)
        }
        hintergrund(umgebung)
    }
    @Synchronized fun hintergrund(umgebung:Context) {
        val anwendung=umgebung.anwendung
        if(!anwendung.einstellungen.getBoolean("periodic",false))return
        val anzahl=anwendung.datenbank.faedenLesen().count {it.aktiv}
        val text=if(anzahl==0) "Keine aktiven Fäden · Hintergrundplan vorgemerkt"
        else if(hintergrundLaeuft) "Hintergrundabruf läuft · $anzahl Fäden\n${anwendung.abrufwerk.fortschritt}" else {
            val ziel=anwendung.einstellungen.getLong("naechsterHintergrundabruf",0)
            val zuletzt=anwendung.einstellungen.getLong("letzterHintergrundabruf",0)
            val ergebnis=anwendung.einstellungen.getString("letzterHintergrundzustand","").orEmpty()
            val zeit=if(ziel>System.currentTimeMillis())"Nächster Abruf frühestens ab ${Zeitangaben.uhrzeit(ziel)}" else "Nächster Abruf: warte auf Android"
            "Hintergrundplan aktiv · $anzahl Fäden\n$zeit · Android bestimmt den Zeitpunkt" +
                if(zuletzt>0) "\nLetzte Ausführung: ${Zeitangaben.uhrzeit(zuletzt)} · $ergebnis" else ""
        }
        anzeigen(umgebung,42,text)
    }
}

object Ueberwachung {
    const val AUFTRAG="fadenueberwachung"
    fun anhalten(umgebung:Context) {
        umgebung.anwendung.einstellungen.edit().putBoolean("periodic",false).apply()
        WorkManager.getInstance(umgebung).cancelUniqueWork(AUFTRAG)
        umgebung.stopService(Intent(umgebung,Ueberwachungsdienst::class.java))
        umgebung.anwendung.abrufwerk.netzabrufAbbrechen()
        umgebung.getSystemService(NotificationManager::class.java).cancel(42)
    }
    fun regelmaessig(umgebung:Context) {
        umgebung.stopService(Intent(umgebung,Ueberwachungsdienst::class.java))
        val minuten=umgebung.anwendung.einstellungen.getInt("periodicMinutes",15).coerceAtLeast(15)
        val voraussetzungen=Constraints.Builder().setRequiredNetworkType(if(umgebung.anwendung.einstellungen.getBoolean("wifiOnly",false)) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()
        val anforderung=PeriodicWorkRequestBuilder<Hintergrundarbeit>(minuten.toLong(),TimeUnit.MINUTES).setConstraints(voraussetzungen).build()
        umgebung.anwendung.einstellungen.edit().putBoolean("periodic",true).putLong("naechsterHintergrundabruf",0).apply()
        WorkManager.getInstance(umgebung).enqueueUniquePeriodicWork(AUFTRAG,ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,anforderung)
        Benachrichtigungen.hintergrund(umgebung)
        umgebung.anwendung.datenbank.protokoll("HINWEIS","Hintergrundabruf aktiviert: $minuten Minuten (Android kann verzögern)")
    }
    fun beginn(umgebung:Context,einmalig:Boolean) {
        umgebung.anwendung.einstellungen.edit().putBoolean("periodic",false).apply()
        WorkManager.getInstance(umgebung).cancelUniqueWork(AUFTRAG)
        umgebung.getSystemService(NotificationManager::class.java).cancel(42)
        umgebung.startForegroundService(Intent(umgebung,Ueberwachungsdienst::class.java).putExtra("einmalig",einmalig))
    }
}

class AnhalteEmpfaenger:BroadcastReceiver() {
    override fun onReceive(umgebung:Context,absicht:Intent) {
        Ueberwachung.anhalten(umgebung)
        umgebung.anwendung.datenbank.protokoll("HINWEIS","Überwachung angehalten")
    }
}

class NeustartEmpfaenger:BroadcastReceiver() {
    override fun onReceive(umgebung:Context,absicht:Intent) {
        if(absicht.action==Intent.ACTION_BOOT_COMPLETED || absicht.action==Intent.ACTION_MY_PACKAGE_REPLACED)Benachrichtigungen.hintergrund(umgebung)
    }
}

class Ueberwachungsdienst:Service() {
    private val arbeitsbereich=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var auftrag:Job?=null
    private var wachhaltesperre:PowerManager.WakeLock?=null
    override fun onBind(absicht:Intent?)=null
    override fun onStartCommand(absicht:Intent?,kennzeichen:Int,startkennung:Int):Int {
        if(Build.VERSION.SDK_INT>=29)startForeground(41,Benachrichtigungen.erzeugen(this,"Überwachung beginnt"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(41,Benachrichtigungen.erzeugen(this,"Überwachung beginnt"))
        if(auftrag?.isActive==true) {anwendung.datenbank.protokoll("HINWEIS","Überwachung läuft bereits");return START_NOT_STICKY}
        laeuft=true
        wachhaltesperre=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"KanalFadenBeobachter:Sitzung").apply {setReferenceCounted(false);acquire(10*60*1000L)}
        val einmalig=absicht?.getBooleanExtra("einmalig",false) ?: true
        anwendung.datenbank.protokoll("HINWEIS",if(einmalig)"Einzelabruf begonnen" else "Aktive Intervallsitzung begonnen")
        auftrag=arbeitsbereich.launch {
            try {
                withTimeout(5*60*60*1000L) {
                    val zustandsauftrag=launch {
                        var takte=0
                        while(isActive) {
                            Benachrichtigungen.aktualisieren(this@Ueberwachungsdienst)
                            delay(2000)
                            if(++takte%150==0)wachhaltesperre?.acquire(10*60*1000L)
                        }
                    }
                    try {
                        do {
                            anwendung.abrufwerk.naechsterAbruf=0
                            anwendung.abrufwerk.abrufAusfuehren(if(einmalig)"Einzelabruf" else "Intervallabruf")
                            if(einmalig)break
                            anwendung.abrufwerk.naechsterAbruf=android.os.SystemClock.elapsedRealtime()+anwendung.einstellungen.getInt("seconds",60).coerceAtLeast(10)*1000L
                            while(android.os.SystemClock.elapsedRealtime()<anwendung.abrufwerk.naechsterAbruf) {
                                delay(minOf(1000L,(anwendung.abrufwerk.naechsterAbruf-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1)))
                            }
                        } while(isActive)
                    } finally {zustandsauftrag.cancelAndJoin()}
                }
            } catch(fehler:TimeoutCancellationException) {anwendung.datenbank.protokoll("HINWEIS","Sitzung nach 5 Stunden beendet. Für weitere Abrufe erneut beginnen.")}
            catch(fehler:CancellationException) {throw fehler}
            catch(fehler:Exception) {anwendung.datenbank.protokoll("FEHLER","Überwachung: ${fehler.message}")}
            finally {stopSelf()}
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startkennung:Int,dienstart:Int) {anwendung.datenbank.protokoll("HINWEIS","Android-Zeitbudget erreicht; Sitzung beendet");auftrag?.cancel();stopSelf()}
    override fun onDestroy() {
        anwendung.abrufwerk.naechsterAbruf=0;arbeitsbereich.cancel()
        if(wachhaltesperre?.isHeld==true)wachhaltesperre?.release()
        laeuft=false;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    companion object {@Volatile var laeuft=false}
}

class Hintergrundarbeit(umgebung:Context,parameter:WorkerParameters):CoroutineWorker(umgebung,parameter) {
    override suspend fun doWork():Result = coroutineScope {
        val anwendung=applicationContext.anwendung
        if(!anwendung.einstellungen.getBoolean("periodic",false))return@coroutineScope Result.success()
        if(Ueberwachungsdienst.laeuft)return@coroutineScope Result.retry()
        Benachrichtigungen.hintergrundLaeuft=true
        val zustandsauftrag=launch {
            while(isActive && anwendung.einstellungen.getBoolean("periodic",false)) {
                Benachrichtigungen.hintergrund(applicationContext)
                delay(2000)
            }
        }
        var ergebnistext="Unterbrochen"
        var erneutVersuchen=false
        try {
            // Unter der Zehn-Minuten-Grenze der Android-Aufträge bleiben.
            val beendet=withTimeoutOrNull(8*60*1000L) {anwendung.abrufwerk.abrufAusfuehren("Hintergrundabruf");true} ?: false
            ergebnistext=if(beendet)"Abgeschlossen" else "Zeitlimit erreicht"
            if(!beendet)anwendung.datenbank.protokoll("HINWEIS","Hintergrundabruf nach acht Minuten unterbrochen; offene Medien bleiben vorgemerkt")
            Result.success()
        } catch(fehler:CancellationException) {throw fehler}
        catch(fehler:Exception) {ergebnistext="Fehlgeschlagen";erneutVersuchen=true;anwendung.datenbank.protokoll("FEHLER","Hintergrundabruf: ${fehler.message}");Result.retry()}
        finally {
            withContext(NonCancellable) {zustandsauftrag.cancelAndJoin()}
            Benachrichtigungen.hintergrundLaeuft=false
            if(anwendung.einstellungen.getBoolean("periodic",false)) {
                val jetzt=System.currentTimeMillis()
                val abstand=if(erneutVersuchen)minOf(18000000L,30000L*(1L shl runAttemptCount.coerceIn(0,10))) else anwendung.einstellungen.getInt("periodicMinutes",15).coerceAtLeast(15)*60000L
                anwendung.einstellungen.edit().putLong("letzterHintergrundabruf",jetzt).putString("letzterHintergrundzustand",ergebnistext).putLong("naechsterHintergrundabruf",jetzt+abstand).apply()
                Benachrichtigungen.hintergrund(applicationContext)
            }
        }
    }
}

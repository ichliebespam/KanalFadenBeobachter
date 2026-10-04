package de.kanalfadenbeobachter.kern

/** Bereits zugeordnete Namen bleiben auch nach einem Wechsel der Einstellung bestehen. */
object MedienDateinamen {
    fun zuordnen(stand:Fadenstand,vorhandene:Map<String,String>,beitragsnamen:Boolean):Fadenstand {
        val zuordnung=vorhandene.toMutableMap()
        val belegte=vorhandene.values.toMutableSet()
        return stand.copy(beitraege=stand.beitraege.map {beitrag -> beitrag.copy(medien=beitrag.medien.map {medium ->
            val pfad=zuordnung.getOrPut(medium.adresse) {
                if(!beitragsnamen) medium.pfad else {
                    val ursprung=bereinigen(medium.ursprungsname).ifEmpty {"Datei.${medium.erweiterung}"}
                    val vorsilbe="media/${medium.erweiterung}/${medium.beitragskennung}_"
                    var kandidat=vorsilbe+ursprung
                    var nummer=2
                    while(kandidat in belegte) {
                        val punkt=ursprung.lastIndexOf('.').takeIf {it>0} ?: ursprung.length
                        kandidat=vorsilbe+ursprung.take(punkt)+"_${nummer++}"+ursprung.substring(punkt)
                    }
                    kandidat
                }.also {belegte.add(it)}
            }
            medium.copy(lokalerPfad=pfad)
        })})
    }
    fun bereinigen(name:String):String {
        var ergebnis=name.replace(Regex("[\\x00-\\x1f\\x7f/\\\\<>:\"|?*#%]"),"_").trim().trim('.')
        while(ergebnis.toByteArray(Charsets.UTF_8).size>180)ergebnis=ergebnis.substring(0,ergebnis.offsetByCodePoints(ergebnis.length,-1))
        return ergebnis
    }
}
object Zeitangaben {
    fun uhrzeit(zeitpunkt:Long,zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone).format(java.time.Instant.ofEpochMilli(zeitpunkt))
    fun restsekunden(ziel:Long,jetzt:Long):Long=((ziel-jetzt).coerceAtLeast(0)+999)/1000
    fun zufallspause(maximum:Int,zufall:kotlin.random.Random=kotlin.random.Random.Default):Long =
        if(maximum<=0)0 else zufall.nextLong(maximum.coerceAtMost(10)*1000L+1)
}

package de.kanalfadenbeobachter.kern

data class Archivdatei(val adresse:String,val pfad:String,val groesse:Long,val pruefsumme:String)

object Archivverzeichnis {
    /** Liest die von der Anwendung geschriebenen, vollständig zitierten CSV-Felder. */
    fun lesen(text:String):Map<String,Archivdatei> {
        val zeilen=mutableListOf<List<String>>()
        var zeile=mutableListOf<String>();val feld=StringBuilder();var zitiert=false;var stelle=0
        while(stelle<text.length) {
            val zeichen=text[stelle++]
            when {
                zeichen=='"' -> if(zitiert && stelle<text.length && text[stelle]=='"'){feld.append('"');stelle++}else zitiert=!zitiert
                zeichen==',' && !zitiert -> {zeile.add(feld.toString());feld.setLength(0)}
                (zeichen=='\n' || zeichen=='\r') && !zitiert -> {
                    if(zeichen=='\r' && stelle<text.length && text[stelle]=='\n')stelle++
                    zeile.add(feld.toString());feld.setLength(0);zeilen.add(zeile);zeile=mutableListOf()
                }
                else -> feld.append(zeichen)
            }
        }
        require(!zitiert){"Unvollständige Medienzuordnung"}
        if(feld.isNotEmpty() || zeile.isNotEmpty()){zeile.add(feld.toString());zeilen.add(zeile)}
        val kopf=zeilen.firstOrNull() ?: return emptyMap()
        fun wert(zeile:List<String>,spalte:String)=zeile.getOrNull(kopf.indexOf(spalte)).orEmpty()
        return buildMap {
            for(eintrag in zeilen.drop(1)) {
                val adresse=wert(eintrag,"source_url");val pfad=wert(eintrag,"local_path")
                val teile=pfad.split('/')
                if(adresse.isBlank() || teile.size!=3 || teile[0]!="media" || teile.any {it.isEmpty() || it=="." || it==".." || it.contains('\\')} || pfad.endsWith(".writing"))continue
                val groesse=if(wert(eintrag,"status")=="done")wert(eintrag,"bytes").toLongOrNull() ?: 0 else 0
                put(adresse,Archivdatei(adresse,pfad,groesse,wert(eintrag,"sha256")))
            }
        }
    }
}

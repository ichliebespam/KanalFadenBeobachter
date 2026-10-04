package de.kanalfadenbeobachter.kern

object ArchivDarstellung {
    private fun schuetzen(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun darstellen(s: Fadenstand, abgerufen: String, vorherig: Boolean = false): String {
        val vorsilbe = if (vorherig) "../../" else "../"
        return buildString {
            append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'\">")
            append("<title>${schuetzen(s.titel)}</title><style>body{font:16px system-ui;background:#101820;color:#e0ecef;margin:24px auto;padding:0 16px;max-width:960px}a{color:#64d8cb}article{background:#1c2a35;padding:18px;margin:16px 0;border-radius:12px;overflow-wrap:anywhere}header,small{color:#a7bac8}img,video{max-width:100%;max-height:520px}audio{max-width:100%}figure{margin:16px 0}blockquote{border-left:3px solid #64d8cb;padding-left:12px}h1{font-size:26px}</style></head><body>")
            append("<h1>${schuetzen(s.titel)}</h1><p>Archivstand: ${schuetzen(abgerufen)} · ${s.beitraege.size} Beiträge · <a href=\"${schuetzen(s.faden.adresse)}\">Original</a></p>")
            s.beitraege.forEach { p ->
                append("<article id=\"${p.kennung}\"><header>#${p.kennung} · ${schuetzen(p.name)} · ${schuetzen(p.datum)}</header>")
                if (p.betreff.isNotEmpty()) append("<h2>${schuetzen(p.betreff)}</h2>")
                val nachricht = org.jsoup.Jsoup.parseBodyFragment(p.nachricht)
                nachricht.select("a[href]").forEach { a ->
                    val elfenadresse = a.attr("href")
                    if (elfenadresse.substringBefore('#') == s.faden.adresse && elfenadresse.contains('#')) a.attr("href", "#" + elfenadresse.substringAfter('#'))
                }
                append(nachricht.body().html())
                p.medien.forEach { m ->
                    val pfad = schuetzen(vorsilbe + m.pfad)
                    append("<figure>")
                    when {
                        m.medientyp.startsWith("image/") -> append("<a href=\"$pfad\"><img loading=\"lazy\" src=\"$pfad\" alt=\"${schuetzen(m.ursprungsname)}\"></a>")
                        m.medientyp.startsWith("video/") -> append("<video controls preload=\"none\" src=\"$pfad\"></video>")
                        m.medientyp.startsWith("audio/") -> append("<audio controls preload=\"none\" src=\"$pfad\"></audio>")
                    }
                    append("<figcaption><a href=\"$pfad\">${schuetzen(m.ursprungsname)}</a></figcaption></figure>")
                }
                append("</article>")
            }
            append("</body></html>")
        }
    }
    fun zurAltenFassung(seitentext: String): String = seitentext.replace("\"../media/", "\"../../media/")
}

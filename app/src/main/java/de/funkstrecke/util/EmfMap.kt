package de.funkstrecke.util

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Link auf die EMF-Karte der Bundesnetzagentur (amtliche Funkanlagen-Standorte mit
 * Standortbescheinigung, Lage bis zu 80 m ungenau).
 *
 * Die Karte liest ihre Ansicht aus dem Parameter `p`: ein MessagePack-Objekt
 * {c: [lat, lon], z: Zoom, m: "EMFKarte", d: {l: null, i: null, s: false}},
 * URL-sicher Base64-kodiert (Bibliothek json-url, Codec "pack"). So erzeugt die Karte
 * auch ihre eigenen Teilen-Links. Ab Zoom 14 zeigt sie die Standorte an.
 */
object EmfMap {
    private const val BASE = "https://www.bundesnetzagentur.de/DE/Vportal/TK/Funktechnik/EMF/start.html"

    fun url(lat: Double, lon: Double, zoom: Int = 17): String {
        val b = ByteArrayOutputStream()
        fun str(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            b.write(0xa0 or bytes.size) // fixstr (< 32 Byte)
            b.write(bytes)
        }
        fun f64(v: Double) {
            b.write(0xcb)
            val bits = v.toRawBits()
            for (i in 7 downTo 0) b.write((bits ushr (8 * i)).toInt() and 0xff)
        }
        b.write(0x84) // Map mit 4 Einträgen
        str("c")
        b.write(0x92) // Array mit 2 Einträgen
        f64(lat)
        f64(lon)
        str("z")
        b.write(zoom.coerceIn(0, 19)) // positive fixint
        str("m")
        str("EMFKarte")
        str("d")
        b.write(0x83)
        str("l")
        b.write(0xc0) // nil
        str("i")
        b.write(0xc0)
        str("s")
        b.write(0xc2) // false
        val p = Base64.getUrlEncoder().withoutPadding().encodeToString(b.toByteArray())
        return "$BASE?p=$p"
    }
}

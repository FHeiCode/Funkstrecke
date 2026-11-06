package de.funkstrecke.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import de.funkstrecke.util.LocationHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos

/** Ein Punkt auf der Karte. */
data class MapDot(
    val latitude: Double,
    val longitude: Double,
    val color: Color,
    val sizeDp: Int,
    val title: String? = null,
    val snippet: String? = null,
    val onClick: (() -> Unit)? = null,
    /** Nur Ring statt gefülltem Punkt (z. B. mögliche Artefakte) */
    val hollow: Boolean = false,
)

/** Eine Linie auf der Karte (z. B. Grenze einer Tracking Area), Farbe als ARGB, Breite in dp. */
data class CoverageLine(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double, val argb: Int, val widthDp: Float)

/** Ein farbiges Rechteck der Abdeckungskarte (Farbe als ARGB). */
data class CoverageRect(val south: Double, val west: Double, val north: Double, val east: Double, val argb: Int)

/**
 * Zeichnet viele Rechtecke direkt auf die Karte. Eigene Polygone je Feld wären bei
 * tausenden Feldern zu langsam.
 */
private class CoverageOverlay : Overlay() {
    @Volatile
    var rects: List<CoverageRect> = emptyList()

    @Volatile
    var lines: List<CoverageLine> = emptyList()
    var density = 1f
    private val paint = Paint().apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val a = android.graphics.Point()
    private val b = android.graphics.Point()
    private val p1 = GeoPoint(0.0, 0.0)
    private val p2 = GeoPoint(0.0, 0.0)
    private val topLeft = android.graphics.Point()
    private val bottomRight = android.graphics.Point()
    private val nw = GeoPoint(0.0, 0.0)
    private val se = GeoPoint(0.0, 0.0)

    override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
        if (shadow || canvas == null || mapView == null) return
        val projection = mapView.projection
        val view = projection.boundingBox
        for (r in rects) {
            if (r.north < view.latSouth || r.south > view.latNorth || r.east < view.lonWest || r.west > view.lonEast) continue
            nw.latitude = r.north
            nw.longitude = r.west
            se.latitude = r.south
            se.longitude = r.east
            projection.toPixels(nw, topLeft)
            projection.toPixels(se, bottomRight)
            paint.color = r.argb
            // Mindestens 2 px, damit Felder auch weit herausgezoomt sichtbar bleiben.
            val right = maxOf(bottomRight.x, topLeft.x + 2)
            val bottom = maxOf(bottomRight.y, topLeft.y + 2)
            canvas.drawRect(topLeft.x.toFloat(), topLeft.y.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        }
        for (l in lines) {
            if (maxOf(l.lat1, l.lat2) < view.latSouth || minOf(l.lat1, l.lat2) > view.latNorth ||
                maxOf(l.lon1, l.lon2) < view.lonWest || minOf(l.lon1, l.lon2) > view.lonEast
            ) {
                continue
            }
            p1.latitude = l.lat1
            p1.longitude = l.lon1
            p2.latitude = l.lat2
            p2.longitude = l.lon2
            projection.toPixels(p1, a)
            projection.toPixels(p2, b)
            linePaint.color = l.argb
            linePaint.strokeWidth = l.widthDp * density
            canvas.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), linePaint)
        }
    }
}

/** Ein Unsicherheitskreis (Standortschätzung); mit [sector] nur der Kreissektor in diese Richtung. */
data class MapCircle(
    val latitude: Double,
    val longitude: Double,
    val radiusM: Double,
    val color: Color,
    val sector: de.funkstrecke.data.Sector? = null,
)

/** Umriss eines Kreises oder Kreissektors (Spitze in der Mitte, Bogen in 5°-Schritten). */
private fun circlePoints(c: MapCircle): List<GeoPoint> {
    val center = GeoPoint(c.latitude, c.longitude)
    val s = c.sector ?: return Polygon.pointsAsCircle(center, c.radiusM)
    val steps = maxOf(2, (2 * s.halfWidthDeg / 5).toInt())
    val arc = (0..steps).map { i ->
        center.destinationPoint(c.radiusM, s.bearingDeg - s.halfWidthDeg + 2 * s.halfWidthDeg * i / steps)
    }
    return listOf(center) + arc + center
}

/** Roter Punkt mit weißem Rand für die eigene Position. */
private fun positionBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (20 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OWN_POSITION_RED }
    canvas.drawCircle(center, center + density * 0.8f, center - density * 0.5f, shadow)
    canvas.drawCircle(center, center, center - density, ring)
    canvas.drawCircle(center, center, center - density * 3.5f, fill)
    return bitmap
}

private const val OWN_POSITION_RED = 0xFFE0201B.toInt()

private fun dotDrawable(context: Context, color: Color, sizeDp: Int, hollow: Boolean = false): GradientDrawable {
    if (hollow) {
        val density = context.resources.displayMetrics.density
        val px = (sizeDp * density).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.argb(150, 255, 255, 255))
            setStroke((2.5f * density).toInt().coerceAtLeast(2), color.toArgb())
            setSize(px, px)
        }
    }
    val density = context.resources.displayMetrics.density
    val px = (sizeDp * density).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color.toArgb())
        setStroke((1.5f * density).toInt().coerceAtLeast(1), android.graphics.Color.WHITE)
        setSize(px, px)
    }
}

/** Worauf die Karte beim Öffnen zoomt. */
sealed interface MapFocus {
    /** Alle Punkte und Kreise einpassen */
    data object FitAll : MapFocus

    /** ±[radiusM] um die eigene Position (ohne Standortfreigabe: alle Punkte) */
    data class OwnPosition(val radiusM: Double = 1_000.0) : MapFocus

    /** ±[radiusM] um einen festen Punkt */
    data class Around(val latitude: Double, val longitude: Double, val radiusM: Double = 1_000.0) : MapFocus
}

/** Rechteck ±[radiusM] in Nord-Süd- und Ost-West-Richtung um einen Punkt. */
private fun boxAround(latitude: Double, longitude: Double, radiusM: Double): BoundingBox {
    val dLat = radiusM / 110_540.0
    val dLon = radiusM / (111_320.0 * cos(Math.toRadians(latitude)))
    return BoundingBox(latitude + dLat, longitude + dLon, latitude - dLat, longitude - dLon)
}

/** Zoomt, sobald die Karte ihre Größe kennt. */
private fun MapView.zoomWhenReady(box: BoundingBox) {
    if (isLayoutOccurred) zoomToBoundingBox(box, false) else addOnFirstLayoutListener { _, _, _, _, _ -> zoomToBoundingBox(box, false) }
}

@SuppressLint("MissingPermission")
private fun lastKnownPosition(context: Context): Location? {
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
}

/**
 * OpenStreetMap-Karte (osmdroid). Beim ersten Anzeigen wird nach [focus] gezoomt, danach bleibt
 * der Ausschnitt, wie ihn der Nutzer einstellt. Jede Änderung von [recenterKey] zoomt erneut.
 */
@Composable
fun OsmMap(
    dots: List<MapDot>,
    circles: List<MapCircle>,
    modifier: Modifier = Modifier,
    focus: MapFocus = MapFocus.FitAll,
    recenterKey: Int = 0,
    interactive: Boolean = true,
    /** Langes Drücken auf die Karte liefert die Koordinaten (z. B. zum Setzen der Heimatbasis) */
    onLongPress: ((latitude: Double, longitude: Double) -> Unit)? = null,
    /** Farbige Felder der Abdeckungskarte, unter allen anderen Ebenen */
    coverage: List<CoverageRect> = emptyList(),
    /** Linien über den Feldern, z. B. Grenzen von Tracking Areas */
    lines: List<CoverageLine> = emptyList(),
) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.filesDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(51.16, 10.45))
            overlays.add(CopyrightOverlay(context))
        }
    }
    val fitted = remember { AtomicBoolean(false) }

    // Eigene Position als roter Punkt (nur mit Standortfreigabe).
    val positionOverlay = remember {
        if (!LocationHelper.hasPermission(context)) {
            null
        } else {
            val provider = GpsMyLocationProvider(context).apply {
                // Sparsam: Position für den roten Punkt höchstens alle 3 s bzw. nach 5 m
                locationUpdateMinTime = 3_000L
                locationUpdateMinDistance = 5f
            }
            MyLocationNewOverlay(provider, mapView).apply {
                val overlay = this
                val dot = positionBitmap(context)
                setPersonIcon(dot)
                setPersonHotspot(dot.width / 2f, dot.height / 2f)
                setDirectionArrow(dot, dot)
                isDrawAccuracyEnabled = true
                if (focus is MapFocus.OwnPosition) {
                    // Sofort auf die letzte bekannte Position, beim ersten echten GPS-Fix nachjustieren.
                    lastKnownPosition(context)?.let { mapView.zoomWhenReady(boxAround(it.latitude, it.longitude, focus.radiusM)) }
                    runOnFirstFix {
                        mapView.post {
                            val here = overlay.myLocation
                            if (here != null && fitted.compareAndSet(false, true)) {
                                mapView.zoomWhenReady(boxAround(here.latitude, here.longitude, focus.radiusM))
                            }
                        }
                    }
                }
                mapView.overlays.add(this)
            }
        }
    }

    val coverageOverlay = remember {
        CoverageOverlay().also {
            it.density = context.resources.displayMetrics.density
            mapView.overlays.add(0, it)
        }
    }
    val longPress = rememberUpdatedState(onLongPress)
    remember {
        if (onLongPress != null) {
            mapView.overlays.add(
                0,
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        val point = p ?: return false
                        longPress.value?.invoke(point.latitude, point.longitude)
                        return true
                    }
                }),
            )
        }
        true
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    positionOverlay?.enableMyLocation()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    positionOverlay?.disableMyLocation()
                    mapView.onPause()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) positionOverlay?.enableMyLocation()
        onDispose {
            lifecycle.removeObserver(observer)
            positionOverlay?.disableMyLocation()
            mapView.onDetach()
        }
    }

    LaunchedEffect(recenterKey) {
        if (recenterKey == 0) return@LaunchedEffect
        when (focus) {
            is MapFocus.OwnPosition -> {
                val here = positionOverlay?.myLocation
                    ?: lastKnownPosition(context)?.let { GeoPoint(it.latitude, it.longitude) }
                if (here != null) mapView.zoomWhenReady(boxAround(here.latitude, here.longitude, focus.radiusM))
            }
            is MapFocus.Around -> mapView.zoomWhenReady(boxAround(focus.latitude, focus.longitude, focus.radiusM))
            MapFocus.FitAll -> Unit
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.removeAll { it is Marker || it is Polygon }
            val added = mutableListOf<Overlay>()
            circles.forEach { c ->
                if (c.sector != null) {
                    // Voller 95-%-Kreis bleibt als blasse, gestrichelte Linie sichtbar.
                    added += Polygon(map).apply {
                        points = Polygon.pointsAsCircle(GeoPoint(c.latitude, c.longitude), c.radiusM)
                        fillPaint.color = android.graphics.Color.TRANSPARENT
                        outlinePaint.color = c.color.copy(alpha = 0.3f).toArgb()
                        outlinePaint.strokeWidth = 2f
                        outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 10f), 0f)
                        setOnClickListener { _, _, _ -> false }
                    }
                }
                added += Polygon(map).apply {
                    points = circlePoints(c)
                    fillPaint.color = c.color.copy(alpha = 0.12f).toArgb()
                    outlinePaint.color = c.color.copy(alpha = 0.7f).toArgb()
                    outlinePaint.strokeWidth = 3f
                    setOnClickListener { _, _, _ -> false }
                }
            }
            dots.forEach { d ->
                added += Marker(map).apply {
                    position = GeoPoint(d.latitude, d.longitude)
                    icon = dotDrawable(context, d.color, d.sizeDp, d.hollow)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = d.title
                    snippet = d.snippet
                    val click = d.onClick
                    if (click != null) {
                        setOnMarkerClickListener { _, _ ->
                            click()
                            true
                        }
                    } else if (d.title == null) {
                        setOnMarkerClickListener { _, _ -> true }
                    }
                }
            }
            map.overlays.addAll(added)
            coverageOverlay.rects = coverage
            coverageOverlay.lines = lines
            // Eigene Position immer obenauf zeichnen.
            positionOverlay?.let {
                map.overlays.remove(it)
                map.overlays.add(it)
            }
            map.invalidate()

            when {
                focus is MapFocus.Around -> {
                    if (fitted.compareAndSet(false, true)) {
                        map.zoomWhenReady(boxAround(focus.latitude, focus.longitude, focus.radiusM))
                    }
                }
                // Eigene Position wird über den GPS-Fix angesteuert; ohne Freigabe auf alle Punkte zoomen.
                focus is MapFocus.OwnPosition && positionOverlay != null -> Unit
                else -> {
                    val points = dots.map { GeoPoint(it.latitude, it.longitude) } +
                        circles.map { GeoPoint(it.latitude, it.longitude) }
                    if (points.isNotEmpty() && fitted.compareAndSet(false, true)) {
                        if (points.size == 1) {
                            val p = points.first()
                            map.zoomWhenReady(boxAround(p.latitude, p.longitude, 1_000.0))
                        } else {
                            map.zoomWhenReady(BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.4f))
                        }
                    }
                }
            }
        },
    )
}

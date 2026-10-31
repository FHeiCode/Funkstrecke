package de.funkstrecke.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Ein Punkt auf der Karte. */
data class MapDot(
    val latitude: Double,
    val longitude: Double,
    val color: Color,
    val sizeDp: Int,
    val title: String? = null,
    val snippet: String? = null,
    val onClick: (() -> Unit)? = null,
)

/** Ein Unsicherheitskreis (Standortschätzung). */
data class MapCircle(val latitude: Double, val longitude: Double, val radiusM: Double, val color: Color)

private fun dotDrawable(context: Context, color: Color, sizeDp: Int): GradientDrawable {
    val density = context.resources.displayMetrics.density
    val px = (sizeDp * density).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color.toArgb())
        setStroke((1.5f * density).toInt().coerceAtLeast(1), android.graphics.Color.WHITE)
        setSize(px, px)
    }
}

/**
 * OpenStreetMap-Karte (osmdroid). Beim ersten Anzeigen wird auf alle Punkte gezoomt,
 * danach bleibt der Ausschnitt, wie ihn der Nutzer einstellt.
 */
@Composable
fun OsmMap(
    dots: List<MapDot>,
    circles: List<MapCircle>,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
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

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.removeAll { it is Marker || it is Polygon }
            val added = mutableListOf<Overlay>()
            circles.forEach { c ->
                added += Polygon(map).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(c.latitude, c.longitude), c.radiusM)
                    fillPaint.color = c.color.copy(alpha = 0.12f).toArgb()
                    outlinePaint.color = c.color.copy(alpha = 0.7f).toArgb()
                    outlinePaint.strokeWidth = 3f
                    setOnClickListener { _, _, _ -> false }
                }
            }
            dots.forEach { d ->
                added += Marker(map).apply {
                    position = GeoPoint(d.latitude, d.longitude)
                    icon = dotDrawable(context, d.color, d.sizeDp)
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
            map.invalidate()

            val points = dots.map { GeoPoint(it.latitude, it.longitude) } +
                circles.map { GeoPoint(it.latitude, it.longitude) }
            if (points.isNotEmpty() && fitted.compareAndSet(false, true)) {
                val fit = {
                    if (points.size == 1) {
                        map.controller.setZoom(16.0)
                        map.controller.setCenter(points.first())
                    } else {
                        map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.4f), false)
                    }
                }
                if (map.isLayoutOccurred) fit() else map.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
            }
        },
    )
}

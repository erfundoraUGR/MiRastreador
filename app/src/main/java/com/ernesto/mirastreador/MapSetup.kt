package com.ernesto.mirastreador

import android.content.Context
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.views.MapView
import java.io.File

/** Elige entre el mapa de internet (con caché) y un mapa sin conexión cargado por el usuario. */
object MapSetup {

    fun offlineFile(ctx: Context): File? =
        TrackerStore.offlineFile(ctx)?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }

    /** Configura el mapa. Devuelve true si se está usando el mapa sin conexión. */
    fun apply(ctx: Context, map: MapView): Boolean {
        val f = offlineFile(ctx)
        if (TrackerStore.offlineOn(ctx) && f != null) {
            try {
                val provider = OfflineTileProvider(SimpleRegisterReceiver(ctx), arrayOf(f))
                map.setTileProvider(provider)
                var source: String? = null
                val archives = provider.archives
                if (archives.isNotEmpty()) {
                    val names = archives[0].tileSources
                    if (names.isNotEmpty()) source = names.first()
                }
                map.setTileSource(if (source != null) FileBasedTileSource.getSource(source) else TileSourceFactory.MAPNIK)
                map.setUseDataConnection(false)
                return true
            } catch (_: Exception) {
                // Si el archivo no se puede abrir, se usa el mapa normal
            }
        }
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setUseDataConnection(true)
        return false
    }

    /** Lo que debería estar usando el mapa ahora mismo según los ajustes. */
    fun wantsOffline(ctx: Context): Boolean = TrackerStore.offlineOn(ctx) && offlineFile(ctx) != null
}

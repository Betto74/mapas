package com.example.mapas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color.Companion.Red
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.mapas.ui.theme.MapasTheme
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val apiService = RouteApiService.create()
    private var homeAddress: String? = null
    private val LOCATION_PERMISSION_CODE = 1001
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Configuración inicial de osmdroid
        Configuration.getInstance().load(this, getPreferences(Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            MapasTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MapScreen()
                }
            }
        }
    }

    @Composable
    fun MapScreen() {
        val context = LocalContext.current
        var routePoints by remember { mutableStateOf<List<GeoPoint>?>(null) }
        var currentLocation by remember { mutableStateOf<GeoPoint?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var showDialog by remember { mutableStateOf(false) }
        var tempAddress by remember { mutableStateOf("") }
        var mapView by remember { mutableStateOf<MapView?>(null) }

        var hasLocationPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        // Lanzador para solicitar permisos
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasLocationPermission = isGranted
            if (!isGranted) {
                error = "Se necesitan permisos de ubicación para usar esta función"
            }
        }

        // Función para obtener solo la ubicación actual
        fun getCurrentLocation() {
            if (!hasLocationPermission) {
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                return
            }

            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location == null) {
                        error = "No se pudo obtener la ubicación"
                        return@addOnSuccessListener
                    }
                    currentLocation = GeoPoint(location.latitude, location.longitude)
                    mapView?.controller?.setCenter(currentLocation)
                    mapView?.controller?.setZoom(15.0)
                }
                .addOnFailureListener {
                    error = "Error al obtener ubicación: ${it.message}"
                }
        }

        // Función para calcular la ruta
        fun calculateRoute() {
            if (currentLocation == null || homeAddress.isNullOrEmpty()) {
                error = "Primero obtén tu ubicación y configura una dirección"
                return
            }

            isLoading = true
            error = null

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val coords = homeAddress!!.split(",")
                    if (coords.size != 2) {
                        withContext(Dispatchers.Main) {
                            error = "Formato de dirección incorrecto. Usa latitud,longitud"
                            isLoading = false
                        }
                        return@launch
                    }

                    // Validar que las coordenadas son números válidos
                    val startLon = currentLocation!!.longitude
                    val startLat = currentLocation!!.latitude
                    val endLat = coords[0].trim().toDoubleOrNull()
                    val endLon = coords[1].trim().toDoubleOrNull()

                    if (endLat == null || endLon == null) {
                        withContext(Dispatchers.Main) {
                            error = "Coordenadas inválidas. Ejemplo: 19.432608,-99.133209"
                            isLoading = false
                        }
                        return@launch
                    }

                    // Construir parámetros de la ruta
                    val start = "$startLon,$startLat"
                    val end = "$endLon,$endLat"  // Nota: ORS espera lon,lat

                    Log.d("RouteAPI", "Solicitando ruta de $start a $end")

                    val route = apiService.getRoute(
                        apiKey = RouteApiService.API_KEY,
                        start = start,
                        end = end
                    )

                    val points = route.features.firstOrNull()?.geometry?.coordinates?.map {
                        GeoPoint(it[1], it[0]) // La API devuelve [lon, lat]
                    } ?: emptyList()

                    withContext(Dispatchers.Main) {
                        routePoints = points
                        isLoading = false
                        Log.d("RouteAPI", "Ruta obtenida con ${points.size} puntos")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        error = when {
                            e.message?.contains("400") == true -> "Error en los datos enviados. Verifica las coordenadas"
                            e.message?.contains("401") == true -> "API Key inválida"
                            e.message?.contains("404") == true -> "No se encontró ruta entre los puntos"
                            else -> "Error al obtener la ruta: ${e.message}"
                        }
                        isLoading = false
                        Log.e("RouteAPI", "Error al calcular ruta", e)
                    }
                }
            }
        }
        // UI del mapa
        Scaffold(
            floatingActionButton = {
                Column {
                    // Botón para obtener ubicación actual
                    FloatingActionButton(
                        onClick = { getCurrentLocation() },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Obtener ubicación actual")
                    }

                    // Botón para calcular ruta
                    FloatingActionButton(
                        onClick = { calculateRoute() },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Calcular ruta")
                    }

                    // Botón para configurar dirección
                    FloatingActionButton(
                        onClick = {
                            tempAddress = homeAddress ?: ""
                            showDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "Configurar dirección")
                    }

                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {

                MapViewComponent(
                    currentLocation = currentLocation,
                    homeAddress = homeAddress,
                    routePoints = routePoints,
                    onMapLoaded = { view -> mapView = view }
                )

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                error?.let {
                    Text(
                        text = it,
                        color = Red,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                    )
                }
            }
        }

        // Diálogo para configurar dirección con Geocoder
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Configurar dirección de casa") },
                text = {
                    Column {
                        Text("Ingresa la dirección de tu casa:")
                        TextField(
                            value = tempAddress,
                            onValueChange = { tempAddress = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Ejemplo: Av. Reforma 123, Ciudad de México",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        geocodeAddress(context, tempAddress) { geoPoint ->
                            if (geoPoint != null) {
                                // Guardar latitud y longitud como string
                                homeAddress = "${geoPoint.latitude},${geoPoint.longitude}"
                            } else {
                                // No se encontró la dirección
                                Toast.makeText(context, "No se encontró la dirección", Toast.LENGTH_SHORT).show()
                            }
                            showDialog = false
                        }
                    }) {
                        Text("Guardar")
                    }
                },
                dismissButton = {
                    Button(onClick = { showDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }

    }

    @Composable
    fun MapViewComponent(
        currentLocation: GeoPoint?,
        homeAddress: String?,
        routePoints: List<GeoPoint>?,
        onMapLoaded: (MapView) -> Unit = {}
    ) {
        val context = LocalContext.current

        AndroidView(
            factory = { context ->
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    minZoomLevel = 3.0
                    maxZoomLevel = 19.0
                    setMultiTouchControls(true)
                }
            },
            update = { mapView ->
                currentLocation?.let { location ->
                    mapView.controller.setCenter(location)
                    mapView.controller.setZoom(15.0)
                }

                // Limpiar marcadores anteriores
                mapView.overlays.clear()

                // Agregar marcador de ubicación actual
                currentLocation?.let { location ->
                    val marker = Marker(mapView)
                    marker.position = location
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.title = "Tu ubicación"
                    mapView.overlays.add(marker)
                }

                // Agregar marcador de casa
                homeAddress?.let { address ->
                    val coords = address.split(",")
                    if (coords.size == 2) {
                        val homePoint = GeoPoint(coords[0].toDouble(), coords[1].toDouble())
                        val marker = Marker(mapView)
                        marker.position = homePoint
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        marker.title = "Tu casa"
                        mapView.overlays.add(marker)
                    }
                }

                // Dibujar ruta
                routePoints?.let { points ->
                    val line = Polyline()
                    line.setPoints(points)
                    line.color = Color.BLUE
                    mapView.overlayManager.add(line)
                }

                mapView.invalidate()
                onMapLoaded(mapView)
            }
        )
    }
}

fun geocodeAddress(context: Context, address: String, onResult: (GeoPoint?) -> Unit) {
    val geocoder = Geocoder(context, Locale.getDefault())
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val addresses = geocoder.getFromLocationName(address, 1)
            addresses?.firstOrNull()?.let {
                withContext(Dispatchers.Main) {
                    onResult(GeoPoint(it.latitude, it.longitude))
                }
            } ?: onResult(null)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {

                onResult(null)
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MapasTheme {
        // Preview básico - no funcionará completamente por la dependencia del mapa
        Text("Vista previa del mapa (no funcional en preview)")
    }
}
package se.hallqvist.hqgolf

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.maps.android.ui.IconGenerator;
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.iid.FirebaseInstanceId
import kotlinx.android.synthetic.main.activity_maps.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class MapsActivity : AppCompatActivity(), OnMapReadyCallback,
        ActivityCompat.OnRequestPermissionsResultCallback,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        com.google.android.gms.location.LocationListener {
    private val TAG = "Striker"
    private val LOCATIONS = "locations.txt"
    private val STROKES = "strokes.txt"
    private lateinit var mMap: GoogleMap
    private lateinit var mGoogleApiClient: GoogleApiClient
    private var mLocationManager: LocationManager? = null
    private lateinit var mLocation: Location
    private var mLocationRequest: LocationRequest? = null
    private val listener: com.google.android.gms.location.LocationListener? = null
    private val UPDATE_INTERVAL = (2 * 1000).toLong()  /* 10 secs */
    private val FASTEST_INTERVAL: Long = 2000 /* 2 sec */
    private var mCurrentLoc: Marker? = null
    private lateinit var locationManager: LocationManager
    private lateinit var myLocationIcon: Bitmap
    private var shotNo = 1
    private var holeNo = 1
    private lateinit var mIconFactory: IconGenerator;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseInstanceId.getInstance().instanceId
                .addOnCompleteListener(OnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w(TAG, "getInstanceId failed", task.exception)
                        return@OnCompleteListener
                    }

                    // Log new Instance ID token
                    Log.d(TAG, task.result?.token)
                })
        setContentView(R.layout.activity_maps)
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
                .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        myLocationIcon = getBitmapFromVectorDrawable(this, R.drawable.ic_my_location_yellow_24dp)
        mIconFactory = IconGenerator(applicationContext)

        mGoogleApiClient = GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build()

        mLocationManager = this.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        checkLocation()
    }

    override fun onStart() {
        super.onStart()
        mGoogleApiClient.connect();
    }

    override fun onStop() {
        super.onStop();
        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

    override fun onConnectionSuspended(p0: Int) {
        Log.i(TAG, "Connection Suspended");
        mGoogleApiClient.connect();
    }

    override fun onConnectionFailed(connectionResult: ConnectionResult) {
        Log.i(TAG, "Connection failed. Error: " + connectionResult.getErrorCode());
    }

    override fun onBackPressed() {
        moveTaskToBack(false);
    }

    override fun onLocationChanged(location: Location) {
//        if (!isValid(location))
//            return
        logLocation(location)
        mLocation = location
        val loc = LatLng(location.latitude, location.longitude)
        if (mCurrentLoc != null)
            mCurrentLoc?.remove()
        mCurrentLoc = mMap.addMarker(
                MarkerOptions().position(loc)
                        .icon(BitmapDescriptorFactory.fromBitmap(myLocationIcon))
                        .anchor(0.5F, 0.5F))
        mMap.animateCamera(CameraUpdateFactory.newLatLng(loc))
    }

    /* Checks if external storage is available for read and write */
    private fun isExternalStorageWritable(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }

    private fun logLocation(location: Location) {
        if (!isExternalStorageWritable()) {
            Log.w(TAG, "external storage not writable");
            return;
        }
        val path = File(getExternalFilesDir(null), LOCATIONS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.write(path.toPath(), (location.toString() + "\n").toByteArray(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        } catch (e: IOException) {
            Log.e(TAG, "log failed: ", e)
        }
    }

    private fun logStroke(shot: String, location: LatLng) {
        if (!isExternalStorageWritable()) {
            Log.w(TAG, "external storage not writable");
            return;
        }
        val path = File(getExternalFilesDir(null), STROKES)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.write(path.toPath(),
                        (System.currentTimeMillis().toString() + ": " + shot + " " +
                                location + "\n")
                                .toByteArray(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        } catch (e: IOException) {
            Log.e(TAG, "log failed: ", e)
        }
    }

    private val locationHistory = mutableListOf<Location>()
    private val MOVEMENT_THRESHOLD = 10

    private fun isValid(location: Location): Boolean {
        try {
            if (locationHistory.isEmpty())
                return true
            val last = locationHistory.last()
            val distance = location.distanceTo(last)
            if (distance < MOVEMENT_THRESHOLD)
                return true
            val duration = location.time - last.time
            val speed = distance * 1000 / duration
            Log.d(TAG, "speed " + location.speed + " computed " + speed)
            return speed < 10
        } finally { locationHistory.add(location) }
    }

    private fun addIcon(iconFactory: IconGenerator, text: CharSequence,
                        position: LatLng) {
        val markerOptions = MarkerOptions()
                .icon(BitmapDescriptorFactory.fromBitmap(
                        iconFactory.makeIcon(text)))
                .position(position)
                .anchor(iconFactory.getAnchorU(), iconFactory.getAnchorV())
        mMap.addMarker(markerOptions).tag = text
    }

    private fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap {
        var drawable: Drawable? = AppCompatResources.getDrawable(context, drawableId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            drawable = (DrawableCompat.wrap(drawable!!)).mutate();
        }
        val bitmap = Bitmap.createBitmap(
                drawable!!.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        val canvas = Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    override fun onConnected(p0: Bundle?) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                            Manifest.permission.ACCESS_FINE_LOCATION)) {
                // XXX Show an explanation to the user *asynchronously* -- don't block
                // XXX this thread waiting for the user's response! After the user
                // XXX sees the explanation, try again to request the permission.
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0)
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0)
            }
            return
        }
        startLocationProvider()
    }

    @SuppressLint("MissingPermission")
    fun startLocationProvider() {
        startLocationUpdates();

        var fusedLocationProviderClient:
                FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        fusedLocationProviderClient.getLastLocation()
                .addOnSuccessListener(this, OnSuccessListener<Location> { location ->
                    // Got last known location. In some rare situations this can be null.
                    if (location != null) {
                        // Logic to handle location object
                        mLocation = location;
                    }
                })
        fab.setOnClickListener { view -> strike(view) }
        fab.setOnLongClickListener { _ -> nextHole(); true }
    }

    fun strike(view: View) {
        Snackbar.make(view, "Nice shot", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
        val loc = LatLng(mLocation.latitude, mLocation.longitude)
        val shot = holeNo.toString() + "." + shotNo++
        logStroke(shot, loc)
        addIcon(mIconFactory, shot, loc)
    }

    fun nextHole() {
        shotNo = 1
        holeNo++
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>,
                                            grantResults: IntArray) {
        if (grantResults[0] == -1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                finishAffinity()
            } else {
                finish()
            }
            return;
        }
        startLocationProvider()
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.animateCamera(CameraUpdateFactory.zoomTo(15F))
        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE)
        mMap.setOnMarkerClickListener(GoogleMap.OnMarkerClickListener { marker ->
            val tag = marker.tag
            if (null != tag) {}
            true
        })
    }

    private fun checkLocation(): Boolean {
        if (!isLocationEnabled())
            showAlert();
        return isLocationEnabled();
    }

    private fun isLocationEnabled(): Boolean {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showAlert() {
        val dialog = AlertDialog.Builder(this)
        dialog.setTitle("Enable Location")
                .setMessage("Your Locations Settings is set to 'Off'.\nPlease Enable Location to " + "use this app")
                .setPositiveButton("Location Settings", DialogInterface.OnClickListener { _, _ ->
                    val myIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivity(myIntent)
                })
                .setNegativeButton("Cancel", DialogInterface.OnClickListener { _, _ -> })
        dialog.show()
    }

    protected fun startLocationUpdates() {
        // Create the location request
        mLocationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(UPDATE_INTERVAL)
                .setFastestInterval(FASTEST_INTERVAL);
        // Request location updates
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // XXX Check out the deprecation of this.
        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient,
                mLocationRequest, this);
    }
}
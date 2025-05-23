package com.chateo.chatcorner.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.chateo.chatcorner.R
import com.chateo.chatcorner.Utils.DeviceUtils
import com.chateo.chatcorner.Utils.FirebaseExtension
import com.chateo.chatcorner.Utils.FirebaseUtil
import com.chateo.chatcorner.Utils.SharedPrefHelper
import com.chateo.chatcorner.databinding.ActivityHomeBinding
import com.chateo.chatcorner.fragment.ChatFragment
import com.chateo.chatcorner.fragment.ContactFragment
import com.chateo.chatcorner.fragment.SettingFragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.CountDownLatch

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 100
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (SharedPrefHelper.getPhoneNumber().isNotEmpty()){
            FirebaseExtension.userOnlineStatus("true")
        }
        binding.defaultContacts.isVisible = false
        binding.clickedContacts.isVisible = true
        loadFragment(ContactFragment())

        binding.contactsLayout.setOnClickListener {
            binding.defaultContacts.isVisible = false
            binding.clickedContacts.isVisible = true
            binding.defaultMore.isVisible = true
            binding.clickedMore.isVisible = false
            binding.defaultChats.isVisible = true
            binding.clickedChats.isVisible = false
            loadFragment(ContactFragment())
        }

        binding.chatsLayout.setOnClickListener {
            binding.defaultChats.isVisible = false
            binding.clickedChats.isVisible = true
            binding.defaultContacts.isVisible = true
            binding.clickedContacts.isVisible = false
            binding.defaultMore.isVisible = true
            binding.clickedMore.isVisible = false
            loadFragment(ChatFragment())
        }

        binding.moreLayout.setOnClickListener {
            binding.defaultMore.isVisible = false
            binding.clickedMore.isVisible = true
            binding.defaultContacts.isVisible = true
            binding.clickedContacts.isVisible = false
            binding.defaultChats.isVisible = true
            binding.clickedChats.isVisible = false
            loadFragment(SettingFragment())
        }
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize location updates
        initLocationUpdates()
        val authToken = generateAuthToken(this)
        SharedPrefHelper.setFirebaseAuthToken(authToken)
    }

    private fun initLocationUpdates() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, // High accuracy
            10_000 // Update interval in milliseconds 60_000 (1 minute)
        ).build()

        // Location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                for (location in locationResult.locations) {
                    latitude = location.latitude
                    longitude = location.longitude
                    val deviceUtils = DeviceUtils(this@HomeActivity)
                    val uniqueDeviceId = deviceUtils.getUniqueDeviceId()
                    SharedPrefHelper.setLatitude(location.latitude)
                    SharedPrefHelper.setLongitude(location.longitude)
                    val updateLatLong = mapOf(
                        "isLogin" to "true", // Ensure latitude and longitude are valid
                        "deviceToken" to uniqueDeviceId, // Ensure latitude and longitude are valid
                        "latitude" to latitude, // Ensure latitude and longitude are valid
                        "longitude" to longitude,
                        "fcmToken" to SharedPrefHelper.getFCMToken(),
                    )
                    if (SharedPrefHelper.getPhoneNumber().isNotEmpty()){
                        FirebaseUtil.database.collection("User").document(SharedPrefHelper.getPhoneNumber()).update(updateLatLong)
                            .addOnSuccessListener {
                                Log.e("HomeActivity","latitude and longitude updated successfully")
                            }
                            .addOnFailureListener { e ->
                                Log.e("HomeActivity","Failed to update device token: ${e.message}")
                            }
                    }
                }
            }
        }

        // Start location updates
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationProviderClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
        } else {
            // Request permission
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun loadFragment(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.container, fragment)
        transaction.commit()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initLocationUpdates()
            } else {
                Toast.makeText(this, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (SharedPrefHelper.getPhoneNumber().isNotEmpty()){
            FirebaseExtension.userOnlineStatus("true")
        }
    }

    override fun onPause() {
        super.onPause()
        if (SharedPrefHelper.getPhoneNumber().isNotEmpty()){
            FirebaseExtension.userOnlineStatus("false")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop location updates to save battery
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        if (SharedPrefHelper.getPhoneNumber().isNotEmpty()){
            FirebaseExtension.userOnlineStatus("false")
        }
    }

    private fun generateAuthToken(context: Context): String {
        return try {
            val inputStream = context.resources.openRawResource(R.raw.chatcorner_1fb20_firebase_adminsdk_jjgbv_e79741aa1c)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            val privateKeyString = json.getString("private_key") ?: throw Exception("Missing private_key in JSON.")
            val clientEmail = json.getString("client_email") ?: throw Exception("Missing client_email in JSON.")

            // Extract and decode private key
            val keyLines = privateKeyString.split("\n").filterNot { it.contains("-----") }
            val keyBase64 = keyLines.joinToString("")

            // Decode base64 depending on SDK version
            val privateKeyBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Base64.getDecoder().decode(keyBase64) // Android 8.0 (API 26) and above
            } else {
                // For lower Android versions, use Android's Base64 class
                android.util.Base64.decode(keyBase64, android.util.Base64.DEFAULT)
            }

            // Convert to RSAPrivateKey
            val keySpec = PKCS8EncodedKeySpec(privateKeyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val privateKey = keyFactory.generatePrivate(keySpec)

            // Create JWT claims
            val now = (System.currentTimeMillis() / 1000).toInt()
            val claims = mapOf(
                "iss" to clientEmail,
                "scope" to "https://www.googleapis.com/auth/firebase.messaging",
                "aud" to "https://oauth2.googleapis.com/token",
                "exp" to (now + 3600),
                "iat" to now
            )

            // Create JWT token
            val algorithm = Algorithm.RSA256(null, privateKey as java.security.interfaces.RSAPrivateKey)
            val signedJWT = JWT.create()
                .withIssuer(clientEmail)
                .withClaim("scope", "https://www.googleapis.com/auth/firebase.messaging")
                .withAudience("https://oauth2.googleapis.com/token")
                .withIssuedAt(Date(now * 1000L))
                .withExpiresAt(Date((now + 3600) * 1000L))
                .sign(algorithm)

            // Make HTTP POST request to fetch the access token
            val client = OkHttpClient()
            val requestBody = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("assertion", signedJWT)
                .build()

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(requestBody)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val latch = CountDownLatch(1)
            var result: String? = null
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    latch.countDown()
                    // Log the error and rethrow
                    Log.e("AuthToken", "Failed to fetch access token: ${e.message}")
                    throw Exception("Failed to fetch access token: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            val jsonResponse = JSONObject(responseBody)
                            result = jsonResponse.getString("access_token")
                        }
                    } else {
                        Log.e("AuthToken", "Failed to fetch access token: ${response.message}")
                        throw Exception("Failed to fetch access token: ${response.message}")
                    }
                    latch.countDown()
                }
            })

            latch.await()
            result ?: throw Exception("Failed to fetch access token.")
        } catch (e: Exception) {
            // Handle and log any other exceptions
            Log.e("AuthToken", "Error: ${e.message}")
            throw e  // Rethrow the exception after logging
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun generateAuthToken1(context: Context): String {
        val inputStream = context.resources.openRawResource(R.raw.chatcorner_1fb20_firebase_adminsdk_jjgbv_e79741aa1c)
        val jsonString = inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)

        val privateKeyString = json.getString("private_key") ?: throw Exception("Missing private_key in JSON.")
        val clientEmail = json.getString("client_email") ?: throw Exception("Missing client_email in JSON.")

        // Extract and decode private key
        val keyLines = privateKeyString.split("\n").filterNot { it.contains("-----") }
        val keyBase64 = keyLines.joinToString("")

        // Decode base64 depending on SDK version
        val privateKeyBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Base64.getDecoder().decode(keyBase64) // Android 8.0 (API 26) and above
        } else {
            // For lower Android versions, use Android's Base64 class
            android.util.Base64.decode(keyBase64, android.util.Base64.DEFAULT)
        }

        // Convert to RSAPrivateKey
        val keySpec = PKCS8EncodedKeySpec(privateKeyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(keySpec)

        // Create JWT claims
        val now = (System.currentTimeMillis() / 1000).toInt()
        val claims = mapOf(
            "iss" to clientEmail,
            "scope" to "https://www.googleapis.com/auth/firebase.messaging",
            "aud" to "https://oauth2.googleapis.com/token",
            "exp" to (now + 3600),
            "iat" to now
        )

        // Create JWT token
        val algorithm = Algorithm.RSA256(null, privateKey as java.security.interfaces.RSAPrivateKey)
        val signedJWT = JWT.create()
            .withIssuer(clientEmail)
            .withClaim("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .withAudience("https://oauth2.googleapis.com/token")
            .withIssuedAt(Date(now * 1000L))
            .withExpiresAt(Date((now + 3600) * 1000L))
            .sign(algorithm)

        // Make HTTP POST request to fetch the access token
        val client = OkHttpClient()
        val requestBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", signedJWT)
            .build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(requestBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val latch = CountDownLatch(1)
        var result: String? = null
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                latch.countDown()
                throw Exception("Failed to fetch access token: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val jsonResponse = JSONObject(responseBody)
                        result = jsonResponse.getString("access_token")
                    }
                }
                latch.countDown()
            }
        })

        latch.await()
        return result ?: throw Exception("Failed to fetch access token.")
    }
}
package com.chateo.chatcorner.ui

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.chateo.chatcorner.R
import com.chateo.chatcorner.Utils.DeviceUtils
import com.chateo.chatcorner.Utils.SharedPrefHelper
import com.chateo.chatcorner.databinding.ActivitySplashBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var existingDeviceToken: String = ""
    private lateinit var uniqueDeviceId: String
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        animateImageViewToCenterWithDrawableChange()
        // Initialize location client
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        // Request location permission and get location
        requestLocationPermission {
            getCurrentLocation()
        }

        // Fetch and store Firebase Messaging token
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d("FCM", "FCM Token: $token")
                SharedPrefHelper.setFCMToken(token)
            } else {
                Log.w("FCM", "Fetching FCM registration token failed", task.exception)
            }
        }

        // Check and request notification permission
        checkNotificationPermission()

        // Initialize device ID and user details
        initializeAppFlow()
    }

    private fun requestLocationPermission(onGranted: () -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            requestPermissionsLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                getCurrentLocation()
            } else {
                Toast.makeText(this, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationProviderClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        latitude = location.latitude
                        longitude = location.longitude
                        SharedPrefHelper.setLatitude(latitude)
                        SharedPrefHelper.setLongitude(longitude)
                    } else {
                        Toast.makeText(this, "Unable to fetch location", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to get location: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun initializeAppFlow() {
        binding.phoneProgressBar.isVisible = false

        val deviceUtils = DeviceUtils(this)
        uniqueDeviceId = deviceUtils.getUniqueDeviceId()
        val phoneNumber = SharedPrefHelper.getPhoneNumber()

        if (phoneNumber.isEmpty()) {
            navigateToLoginScreen()
        } else {
            getUserDetails()
        }
    }

    private fun navigateToLoginScreen() {
        binding.phoneProgressBar.isVisible = false
        startActivity(Intent(this, StartMessagingActivity::class.java))
        finish()
    }

    private fun getUserDetails() {
        val db = FirebaseFirestore.getInstance()
        db.collection("User").document(SharedPrefHelper.getPhoneNumber())
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    existingDeviceToken = document.getString("deviceToken").orEmpty()
                    val profileImageURL = document.getString("profileImageURL") ?: ""
                    val firstName = document.getString("firstName") ?: ""
                    val lastName = document.getString("lastName") ?: ""
                    val userName = "$firstName $lastName"
                    SharedPrefHelper.setUserName(userName)
                    SharedPrefHelper.setUserProfileIMage(profileImageURL)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (SharedPrefHelper.getIsLogin() && uniqueDeviceId == existingDeviceToken) {
                            navigateToHomeScreen()
                        } else {
                            navigateToLoginScreen()
                        }
                    }, 500)
                } else {
                    navigateToLoginScreen()
                    binding.phoneProgressBar.isVisible = false
                }
            }
            .addOnFailureListener {
//                showToast("Failed to retrieve user data: ${it.message}")
                binding.phoneProgressBar.isVisible = false
            }
    }

    private fun navigateToHomeScreen() {
        binding.phoneProgressBar.isVisible = false
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun animateImageViewToCenterWithDrawableChange() {
        val constraintLayout = findViewById<ConstraintLayout>(R.id.constraintLayout)
        val targetVerticalBias = 0.5f // Center of the parent

        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)

        val valueAnimator = ValueAnimator.ofFloat(1f, targetVerticalBias)
        valueAnimator.duration = 1500 // 1 second
        valueAnimator.addUpdateListener { animation ->
            val verticalBias = animation.animatedValue as Float
            constraintSet.setVerticalBias(R.id.imageView, verticalBias)
            constraintSet.applyTo(constraintLayout)
        }

        valueAnimator.start()

        binding.imageView.postDelayed({
            binding.imageView.setImageResource(R.drawable.app_logo_new_alternate)
        }, valueAnimator.duration / 2)
    }
}
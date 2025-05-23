@file:Suppress("DEPRECATION")

package com.chateo.chatcorner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import com.chateo.chatcorner.R
import com.chateo.chatcorner.Utils.SharedPrefHelper
import com.chateo.chatcorner.appinterface.BottomSheetCallback
import com.chateo.chatcorner.bottomsheet.CountryBottomSheet
import com.chateo.chatcorner.databinding.ActivityLoginBinding
import com.chateo.chatcorner.models.Country
import com.chateo.chatcorner.models.countries
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity(), BottomSheetCallback {
    private lateinit var binding: ActivityLoginBinding
    private var countryFlag = ""
    private var countryCode = ""
    private lateinit var auth: FirebaseAuth
    lateinit var storedVerificationId: String
    lateinit var resendToken: PhoneAuthProvider.ForceResendingToken
    private var isEditNumber = false
    private lateinit var callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        auth = FirebaseAuth.getInstance()
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        requestLocationPermission {
            getCurrentLocation()
        }
        isEditNumber = intent.getBooleanExtra("isEditNumber", false)
        if (isEditNumber){
            binding.phoneLabel.text = getString(R.string.update_your_phone_number)
        } else {
            binding.phoneLabel.text = getString(R.string.enter_your_phone_number)
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }

            // Get the FCM token
            val token = task.result
            Log.d("FCM", "FCM Token: $token")
            SharedPrefHelper.setFCMToken(token)
            // You can store or send the token to your server here
        }
        val locale: String = this.resources.configuration.locale.country
        val defaultCountry = countries.find { it.code == locale }

        defaultCountry?.let {
            countryCodeLengthFilter(it)
        } ?: run {
            binding.countryCode.text = ""
        }


        binding.continueMessaging.setOnClickListener {
            getOTPFirebase()
        }

        binding.countryCode.setOnClickListener {
            val countryBottomSheet = CountryBottomSheet(this)
            countryBottomSheet.show(supportFragmentManager, "CountryBottomSheet")
        }


        callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(p0: PhoneAuthCredential) {
                startActivity(Intent(applicationContext, HomeActivity::class.java))
                finish()
            }

            override fun onVerificationFailed(e: FirebaseException) {
                println("onVerificationFailed ${e.message}")
                binding.phoneProgressBar.isVisible = false
            }

            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                storedVerificationId = verificationId
                resendToken = token
                // Start a new activity using intent
                // also send the storedVerificationId using intent
                // we will use this id to send the otp back to firebase
                val intent = Intent(applicationContext, VerifyOTPActivity::class.java)
                intent.putExtra("flag", countryFlag)
                intent.putExtra("dial_code", countryCode)
                intent.putExtra("number", binding.etPhoneNumber.text.toString())
                intent.putExtra("storedVerificationId",storedVerificationId)
                intent.putExtra("verification",true)
                startActivity(intent)
                finish()
                binding.phoneProgressBar.isVisible = false

            }

        }
    }
    private fun countryCodeLengthFilter(item: Country) {
        binding.etPhoneNumber.setText("")
        val dialText = item.flag + "  " + item.dialCode
        countryFlag = item.flag
        countryCode = item.dialCode
        binding.countryCode.text = dialText
        val maxLength = phoneNumberLengths[item.code] ?: 10
        val lengthFilter = InputFilter.LengthFilter(maxLength)
        binding.etPhoneNumber.filters = arrayOf(lengthFilter)
    }

    private fun getOTPFirebase(){
        var number = binding.etPhoneNumber.text!!.trim().toString()
        if (number.isNotEmpty()){
            number = "$countryCode$number"
            sendVerificationCode(number)
        }else{
            Toast.makeText(this,"Enter mobile number", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendVerificationCode(number: String) {
        binding.phoneProgressBar.isVisible = true
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(number)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)

    }

    override fun onItemSelected(item: Country) {
        countryCodeLengthFilter(item)
    }

    private val phoneNumberLengths: Map<String, Int> = mapOf(
        "AF" to 9,   // Afghanistan
        "AX" to 7,   // Aland Islands
        "AL" to 9,   // Albania
        "DZ" to 9,   // Algeria
        "AS" to 7,   // American Samoa
        "AD" to 6,   // Andorra
        "AO" to 9,   // Angola
        "AI" to 7,   // Anguilla
        "AQ" to 6,   // Antarctica
        "AG" to 7,   // Antigua and Barbuda
        "AR" to 10,  // Argentina
        "AM" to 8,   // Armenia
        "AU" to 9,   // Australia
        "AT" to 10,  // Austria
        "AZ" to 9,   // Azerbaijan
        "BH" to 8,   // Bahrain
        "BD" to 10,  // Bangladesh
        "BY" to 9,   // Belarus
        "BE" to 9,   // Belgium
        "BJ" to 8,   // Benin
        "BT" to 8,   // Bhutan
        "BO" to 8,   // Bolivia
        "BA" to 8,   // Bosnia and Herzegovina
        "BR" to 10,  // Brazil
        "BG" to 8,   // Bulgaria
        "CM" to 9,   // Cameroon
        "CA" to 10,  // Canada
        "CL" to 9,   // Chile
        "CN" to 11,  // China
        "CO" to 10,  // Colombia
        "CR" to 8,   // Costa Rica
        "HR" to 9,   // Croatia
        "CU" to 8,   // Cuba
        "CY" to 8,   // Cyprus
        "CZ" to 9,   // Czech Republic
        "DK" to 8,   // Denmark
        "DO" to 10,  // Dominican Republic
        "EC" to 9,   // Ecuador
        "EG" to 10,  // Egypt
        "SV" to 8,   // El Salvador
        "EE" to 8,   // Estonia
        "FI" to 9,   // Finland
        "FR" to 9,   // France
        "GE" to 9,   // Georgia
        "DE" to 10,  // Germany
        "GH" to 9,   // Ghana
        "GR" to 10,  // Greece
        "GT" to 8,   // Guatemala
        "HN" to 8,   // Honduras
        "HK" to 8,   // Hong Kong
        "HU" to 9,   // Hungary
        "IS" to 7,   // Iceland
        "IN" to 10,  // India
        "ID" to 9,   // Indonesia
        "IR" to 10,  // Iran
        "IQ" to 10,  // Iraq
        "IE" to 9,   // Ireland
        "IL" to 9,   // Israel
        "IT" to 10,  // Italy
        "JM" to 10,  // Jamaica
        "JP" to 10,  // Japan
        "JO" to 9,   // Jordan
        "KZ" to 10,  // Kazakhstan
        "KE" to 9,   // Kenya
        "KR" to 9,   // Korea, South
        "KW" to 8,   // Kuwait
        "LA" to 9,   // Laos
        "LV" to 8,   // Latvia
        "LB" to 8,   // Lebanon
        "LT" to 8,   // Lithuania
        "LU" to 9,   // Luxembourg
        "MY" to 9,   // Malaysia
        "MV" to 7,   // Maldives
        "ML" to 8,   // Mali
        "MT" to 8,   // Malta
        "MX" to 10,  // Mexico
        "MC" to 8,   // Monaco
        "MA" to 9,   // Morocco
        "NP" to 10,  // Nepal
        "NL" to 9,   // Netherlands
        "NZ" to 9,   // New Zealand
        "NG" to 10,  // Nigeria
        "NO" to 8,   // Norway
        "OM" to 8,   // Oman
        "PK" to 10,  // Pakistan
        "PA" to 8,   // Panama
        "PY" to 9,   // Paraguay
        "PE" to 9,   // Peru
        "PH" to 10,  // Philippines
        "PL" to 9,   // Poland
        "PT" to 9,   // Portugal
        "PR" to 10,  // Puerto Rico
        "QA" to 8,   // Qatar
        "RO" to 10,  // Romania
        "RU" to 10,  // Russia
        "SA" to 9,   // Saudi Arabia
        "RS" to 8,   // Serbia
        "SG" to 8,   // Singapore
        "SK" to 9,   // Slovakia
        "SI" to 8,   // Slovenia
        "ZA" to 9,   // South Africa
        "ES" to 9,   // Spain
        "LK" to 10,  // Sri Lanka
        "SE" to 9,   // Sweden
        "CH" to 9,   // Switzerland
        "TW" to 9,   // Taiwan
        "TZ" to 9,   // Tanzania
        "TH" to 9,   // Thailand
        "TR" to 10,  // Turkey
        "UG" to 9,   // Uganda
        "UA" to 9,   // Ukraine
        "AE" to 9,   // United Arab Emirates
        "GB" to 10,  // United Kingdom
        "US" to 10,  // United States
        "UY" to 9,   // Uruguay
        "UZ" to 9,   // Uzbekistan
        "VE" to 10,  // Venezuela
        "VN" to 9,   // Vietnam
        "YE" to 9,   // Yemen
        "ZM" to 9,   // Zambia
        "ZW" to 9    // Zimbabwe
    )

    private fun requestLocationPermission(onGranted: () -> Unit) {
        val permission = Manifest.permission.ACCESS_FINE_LOCATION
        if (ActivityCompat.checkSelfPermission(this@LoginActivity, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            requestPermissionsLauncher.launch(permission)
        }
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                getCurrentLocation()
            } else {
                Toast.makeText(this@LoginActivity, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val task: Task<Location> = fusedLocationProviderClient.lastLocation
            task.addOnSuccessListener { location ->
                if (location != null) {
                    latitude = location.latitude
                    longitude = location.longitude
                    SharedPrefHelper.setLatitude(location.latitude)
                    SharedPrefHelper.setLongitude(location.longitude)
                    println("getCurrentLocation $latitude $longitude")
                } else {
                    Toast.makeText(this@LoginActivity, "Unable to fetch location", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this@LoginActivity, "Failed to get location: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

}
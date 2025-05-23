package com.chateo.chatcorner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.chateo.chatcorner.R
import com.chateo.chatcorner.Utils.DeviceUtils
import com.chateo.chatcorner.Utils.SharedPrefHelper
import com.chateo.chatcorner.databinding.ActivityProfileBinding
import com.chateo.chatcorner.databinding.ImagePickerDialogBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.storage
import java.io.File
import kotlin.random.Random

class ProfileActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProfileBinding
    private var cameraImageUri: Uri? = null
    private lateinit var auth: FirebaseAuth
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var profileImageURL: String = ""
    private lateinit var storageRef: StorageReference
    private var shuffledName: String = ""
    private var isEdit = false
    private val userMobileNumber: String by lazy {
        SharedPrefHelper.getPhoneNumber().ifEmpty { intent.getStringExtra("number").orEmpty() }
    }

    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
    }
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                cropImage(it)
            } ?: run {
                showToast("No image selected from gallery")
            }
        }


    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
            if (isSuccess) {
                cameraImageUri?.let {
                    cropImage(it)
                }
            } else {
                showToast("Failed to capture image")
            }
        }

    private val cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            profileImageURL = ""
            binding.phoneProgressBar.isVisible = true
            val croppedImageUri = result.uriContent
            croppedImageUri?.let {
//                binding.userProfileImage.setImageURI(it) // Set the cropped and rotated image to the ImageView
                val sd = getFileName(applicationContext, it)
                val uploadTask = storageRef.child("profileImages/$sd").putFile(it)
                uploadTask.addOnSuccessListener {
                    storageRef.child("profileImages/$sd").downloadUrl.addOnCompleteListener{ task ->
                        if (task.isSuccessful){
                            profileImageURL = task.result.toString()
                            Glide.with(this@ProfileActivity).load(profileImageURL).error(R.drawable.profile_icon).into(binding.userProfileImage)
                            println("cropImageLauncher task.result.toString() ${task.result}")
                            binding.phoneProgressBar.isVisible = false
                            Log.e("cropImageLauncher Firebase", "download passed")
                        }
                    }.addOnFailureListener {
                        Log.e("cropImageLauncher Firebase", "Failed in downloading")
                    }
                }.addOnFailureListener{
                    Log.e("cropImageLauncher Firebase", "Image Upload fail")
                }
            }
        } else {
            result.error?.let {
                showToast("Error cropping image: ${it.message}")
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        auth = FirebaseAuth.getInstance()
        storageRef =  Firebase.storage.reference
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        isEdit = intent.getBooleanExtra("isEdit", false)

        if (isEdit){
            binding.genderLayout.isVisible = true
            binding.header.isVisible = true
            binding.divider.isVisible = true
            binding.accountTxt.text = getString(R.string.profile_over_view)
            binding.btnSave.text = getString(R.string.update)
        } else {
            binding.genderLayout.isVisible = false
            binding.header.isVisible = false
            binding.divider.isVisible = false
            binding.accountTxt.text = getString(R.string.personalize_your_account)
            binding.btnSave.text = getString(R.string.continue_message)
        }

        requestLocationPermission {
            getCurrentLocation()
        }
        binding.addProfileImage.setOnClickListener {
            openCameraGallery()
        }
        binding.save.setOnClickListener {
            if (SharedPrefHelper.getIsLogin() && userMobileNumber.isNotEmpty()) {
                updateUserInfo(userMobileNumber)
            } else {
                saveProfileDetailsToFirebase(userMobileNumber)
            }
        }
        binding.backImageView.setOnClickListener { finish() }


        if (SharedPrefHelper.getIsLogin() && userMobileNumber.isNotEmpty()){
            binding.phoneProgressBar.isVisible = true
            getUserProfileUI(userMobileNumber)
        }

        binding.selectUserGender.setOnClickListener {
            val intent = Intent(this, GenderActivity::class.java)
            intent.putExtra("number", userMobileNumber)
            intent.putExtra("isEdit", isEdit)
            startActivity(intent)
        }

        binding.selectUserPreference.setOnClickListener {
            val intent = Intent(this, GenderPreferenceActivity::class.java)
            intent.putExtra("number", userMobileNumber)
            intent.putExtra("isEdit", isEdit)
            startActivity(intent)
        }


    }

    private fun updateUserInfo(phoneNumber: String) {
        val shuffleUserName = "${binding.firstName.text} ${binding.lastName.text}"
        val randomUsername = generateShuffledSplitUsername(shuffleUserName)
        shuffledName = randomUsername
        println("updateUserInfo $profileImageURL")
        println("updateUserInfo $shuffledName")
        println("updateUserInfo ${binding.firstName.text?.trim()?.toString()}")
        println("updateUserInfo ${binding.lastName.text?.trim()?.toString()}")
        val database = FirebaseFirestore.getInstance() // Or FirebaseDatabase for Realtime DB
        // Validate and create a profile details map
        val updateProfile = mapOf(
            "fcmToken" to SharedPrefHelper.getFCMToken(), // Provide a default empty string if null
            "firstName" to (binding.firstName.text?.trim()?.toString() ?: ""), // Default to empty string
            "isLogin" to true,
            "lastName" to (binding.lastName.text?.trim()?.toString() ?: ""), // Default to empty string
            "latitude" to latitude, // Ensure latitude and longitude are valid
            "longitude" to longitude,
            "phoneNumber" to phoneNumber,
            "profileImageURL" to profileImageURL,
            "shuffledName" to shuffledName,
        )
        binding.phoneProgressBar.isVisible = true
        database.collection("User").document(phoneNumber)
            .update(updateProfile)
            .addOnSuccessListener {
                println("Device token updated successfully for $phoneNumber")
                val intent = Intent(this, HomeActivity::class.java)
                startActivity(intent)
            }
            .addOnFailureListener { e ->
                println("Failed to update device token: ${e.message}")
            }
    }

    private fun getUserProfileUI(phoneNumber: String) {
        val db = FirebaseFirestore.getInstance()
        db.collection("User").document(phoneNumber)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                        val firstName = document.getString("firstName") ?: ""
                        val lastName = document.getString("lastName") ?: ""
                        val profileImage = document.getString("profileImageURL") ?: ""

                    SharedPrefHelper.setIsLogin(true)
                    profileImageURL = profileImage
                    binding.firstName.setText(firstName)
                    binding.lastName.setText(lastName)
                    Glide.with(this@ProfileActivity).load(profileImage).error(R.drawable.profile_icon).into(binding.userProfileImage)
                    binding.phoneProgressBar.isVisible = false
                } else {
                    Log.e("Profile Activity", "No Document Found")
                    SharedPrefHelper.setIsLogin(false)
                    binding.phoneProgressBar.isVisible = false
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this@ProfileActivity, "Failed to retrieve profile: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.phoneProgressBar.isVisible = false
            }
    }


    private fun requestLocationPermission(onGranted: () -> Unit) {
        val permission = Manifest.permission.ACCESS_FINE_LOCATION
        if (ActivityCompat.checkSelfPermission(this@ProfileActivity, permission) == PackageManager.PERMISSION_GRANTED) {
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
                Toast.makeText(this@ProfileActivity, "Location permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val task: Task<Location> = fusedLocationProviderClient.lastLocation
            task.addOnSuccessListener { location ->
                if (location != null) {
                    latitude = location.latitude
                    longitude = location.longitude
                } else {
                    Toast.makeText(this@ProfileActivity, "Unable to fetch location", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this@ProfileActivity, "Failed to get location: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveProfileDetailsToFirebase(phoneNumber: String) {
        val shuffleUserName = "${binding.firstName.text} ${binding.lastName.text}"
        val randomUsername = generateShuffledSplitUsername(shuffleUserName)
        shuffledName = randomUsername
        val firstName = binding.firstName.text?.trim()?.toString()
        val lastName = binding.lastName.text?.trim()?.toString()
        if (firstName.isNullOrEmpty()) {
            Toast.makeText(this@ProfileActivity, "First name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        // Validate phone number (ensure it's not empty)
        if (phoneNumber.isEmpty()) {
            Toast.makeText(this@ProfileActivity, "Phone number is required", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, GenderActivity::class.java)
        intent.putExtra("shuffledName", shuffledName)
        intent.putExtra("firstName", firstName)
        intent.putExtra("lastName", lastName)
        intent.putExtra("profileImageURL", profileImageURL)
        intent.putExtra("latitude", latitude)
        intent.putExtra("longitude", longitude)
        intent.putExtra("number", userMobileNumber)
        intent.putExtra("isEdit", isEdit)
        startActivity(intent)
    }

    private fun openCameraGallery() {
        val builder = AlertDialog.Builder(this@ProfileActivity, R.style.CustomAlertDialog).create()
        val view = ImagePickerDialogBinding.inflate(layoutInflater)
        builder.setView(view.root)
        view.camera.setOnClickListener {
            openCamera()
            builder.dismiss()
        }
        view.gallery.setOnClickListener {
            galleryLauncher.launch("image/*")
            builder.dismiss()
        }
        builder.setCanceledOnTouchOutside(true)
        builder.show()
    }

    private fun requestCameraPermission(onGranted: () -> Unit) {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            onGranted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                openCamera()
            } else {
                showToast("Camera permission is required to capture images")
            }
        }
    }

    private fun createImageUri(): Uri? {
        val imageFile = File.createTempFile(
            "profile_image_${System.currentTimeMillis()}", // Prefix for the file name
            ".jpg", // Suffix for the file name
            cacheDir // Directory to store the temporary file
        )
        return FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.provider",
            imageFile
        )
    }

    // Function to launch cropper
    private fun cropImage(imageUri: Uri) {
        cropImageLauncher.launch(
            CropImageContractOptions(
                uri = imageUri,
                cropImageOptions = CropImageOptions().apply {
                    aspectRatioX = 1 // Lock aspect ratio (optional)
                    aspectRatioY = 1
                    fixAspectRatio = true
                    allowRotation = true
                    allowFlipping = true
                    cropShape = CropImageView.CropShape.OVAL
                    cropMenuCropButtonTitle = "Done" // Custom button title
                    rotationDegrees = 90
                }
            )
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun openCamera() {
        requestCameraPermission {
            cameraImageUri = createImageUri()
            cameraImageUri?.let { uri ->
                cameraLauncher.launch(uri)
            } ?: showToast("Failed to create image file")
        }
    }

    @SuppressLint("Range")
    private fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor.use {
                if (cursor != null) {
                    if(cursor.moveToFirst()) {
                        return cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                    }
                }
            }
        }
        return uri.path?.lastIndexOf('/')?.let { uri.path?.substring(it) }
    }

    private fun generateRandomUsername(baseUsername: String, length: Int): String {
        val source = baseUsername.replace(" ", "")

        return (1..length)
            .map { Random.nextInt(0, source.length) }
            .map(source::get)
            .joinToString("")
    }

    private fun generateShuffledSplitUsername(baseUsername: String): String {
        // Split the name into words
        val words = baseUsername.split(" ")
            .filter { it.isNotBlank() } // Filter out any empty strings
            .map { it.trim() }

        // Shuffle each word's characters and join back into a word
        val shuffledWords = words.map { word ->
            word.toList().shuffled().joinToString("")
        }

        // Join the shuffled words back into a single string
        return shuffledWords.joinToString(" ")
    }


}
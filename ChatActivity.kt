package com.chateo.chatcorner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.chateo.chatcorner.R
import com.chateo.chatcorner.Utils.FirebaseExtension
import com.chateo.chatcorner.Utils.MessageSwipeController
import com.chateo.chatcorner.Utils.SharedPrefHelper
import com.chateo.chatcorner.Utils.ThemeUtils
import com.chateo.chatcorner.adapter.ChatAdapter
import com.chateo.chatcorner.adapter.OnItemData
import com.chateo.chatcorner.bottomsheet.AttachmentBottomSheet
import com.chateo.chatcorner.bottomsheet.LongPressReplyDialog
import com.chateo.chatcorner.bottomsheet.ProfileBottomSheet
import com.chateo.chatcorner.constants.CHAT_OPTION
import com.chateo.chatcorner.constants.MEDIA_TYPE
import com.chateo.chatcorner.databinding.ActivityChatBinding
import com.chateo.chatcorner.databinding.MediaAlertBinding
import com.chateo.chatcorner.databinding.PopupMenuBinding
import com.chateo.chatcorner.interfaces.SwipeControllerActions
import com.chateo.chatcorner.models.ChatItem
import com.chateo.chatcorner.models.MessageModel
import com.chateo.chatcorner.models.UserModel
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.storage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

@Suppress("DEPRECATION")
class ChatActivity : AppCompatActivity(), OnItemData {
    private lateinit var binding: ActivityChatBinding
    private var cameraImageUri: Uri? = null
    private var videoUri: Uri? = null
    private lateinit var videoFile: File
    private var senderId: String = ""
    private var receiverId: String = ""
    private var isVideo: Boolean = false
    private var mediaType = ""
    private var repliedMsg = ""
    private var repliedMsgIndexPathRow = 0
    private var repliedMsgIndexPathSection = 0
    private var repliedMsgTimestamp: Timestamp = Timestamp(Date())
    private var repliedUserName = ""
    private var mediaUrl = ""
    private var fcmToken = ""
    private var pdfName = ""
    private var fullName = ""
    private var isActive = ""
    private var isUserActive = false
    private var notificationType = "message"
    private lateinit var blockedByUsers : List<String>
    private lateinit var blockedUsers : MutableList<String>
    private lateinit var storageRef: StorageReference
    private lateinit var chatList: MutableList<MessageModel>
    private var chatMessageList: MutableList<MessageModel> = mutableListOf()
    private var isFirstCall = true
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    companion object {
        const val REQUEST_CODE_PICK_DOCUMENT = 100
        const val CAMERA_PERMISSION_REQUEST_CODE = 101
        const val REQUEST_PERMISSION_CODE = 1001
    }


    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                if (isVideo) {
                    uploadVideo(it)
                } else {
                    cropImage(it)
                }
            } ?: run {
                showToast("No media selected from gallery")
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

    private val videoCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.d("VideoCapture", "Video saved to: ${videoUri.toString()}")
                uploadVideo(videoUri!!)
            } else {
                Log.d("VideoCapture", "Video capture cancelled")
            }
        }

    private val cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val croppedImageUri = result.uriContent
            croppedImageUri?.let {
                val sd = getFileName(applicationContext, it)
                val uploadTask = storageRef.child("chatImages/$sd").putFile(it)
                binding.progressBar.isVisible = true
                uploadTask.addOnSuccessListener {
                    storageRef.child("chatImages/$sd").downloadUrl.addOnCompleteListener { task ->
                        val profileImageURL = task.result.toString()
                        mediaUrl = profileImageURL
                        notificationType = "image"
                        sendMessage(senderId, receiverId, "", fcmToken)
                        binding.progressBar.isVisible = false
                        Log.e("Firebase", "download passed")
                    }.addOnFailureListener {
                        Log.e("Firebase", "Failed in downloading")
                    }
                }.addOnFailureListener {
                    Log.e("Firebase", "Image Upload fail")
                }
            }
        } else {
            result.error?.let {
                showToast("Error cropping image: ${it.message}")
            }
        }
    }


    private val mChatAdapter: ChatAdapter by lazy {
        ChatAdapter(this, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
//        if (!hasPermissions()) {
//            requestPermissions()
//        }
        storageRef = Firebase.storage.reference
        val userModel = intent.getParcelableExtra<UserModel>("userModel")
        getBlockedUsersList()
        if (userModel != null) {
            receiverId = userModel.phoneNumber.toString()
            senderId = SharedPrefHelper.getPhoneNumber()
            getCurrentUserDetail(userModel)
            Log.d("ChatActivity", "Chatting with $fullName (receiverId: $receiverId, senderId: $senderId) isActive ${userModel.isActive} ${userModel.fcmToken}")
        } else {
            Log.e("ChatActivity", "No user data received")
        }
        updateOnlineUserStatus()
        fetchFCMToken(userModel)
        getAllowedUsersList(userModel)
        updateIsActive(receiverId)
        FirebaseExtension.userOnlineStatus("true")
        setupRecyclerView()

        if (senderId.isNotEmpty() && receiverId.isNotEmpty()) {
            fetchChatMessages(senderId, receiverId, userModel)
        }


        binding.backImageView.setOnClickListener { finish() }
        binding.sendChat.setOnClickListener {
            binding.replyLayout.isVisible = false
            val messageContent = binding.messageContent.text.toString()
            if (messageContent.isNotEmpty()) {
                if (mediaType == MEDIA_TYPE.REPLY_MESSAGE){
                    notificationType = "message"
                    sendMessage(senderId, receiverId, messageContent, fcmToken)
                    binding.messageContent.text?.clear()
                } else{
                    mediaType = MEDIA_TYPE.TEXT
                    notificationType = "message"
                    sendMessage(senderId, receiverId, messageContent, fcmToken)
                    binding.messageContent.text?.clear()
                }

            }
        }
        binding.attachMedia.setOnClickListener {
            AttachmentBottomSheet.newInstance {
                when (it) {
                    CHAT_OPTION.PHOTO -> {
                        customDialog(isPhoto = true)
                        mediaType = MEDIA_TYPE.MEDIA
                    }

                    CHAT_OPTION.VIDEO -> {
                        customDialog(isPhoto = false)
                        mediaType = MEDIA_TYPE.MEDIA
                    }

                    CHAT_OPTION.DOCUMENT -> {
                        mediaType = MEDIA_TYPE.DOCUMENT
                        openDocumentPicker()
                    }
                }

            }.show(supportFragmentManager, AttachmentBottomSheet.TAG)
        }
        binding.profileDetail.setOnClickListener {
            val profileBottomSheet = ProfileBottomSheet.newInstance(userModel!!)
            profileBottomSheet.show(supportFragmentManager, ProfileBottomSheet.Tag)
        }

        binding.menuImageview.setOnClickListener {
            showPopupMenu(it, blockedUsers)
        }

        binding.allowUser.setOnClickListener {
            allowUsers(userModel)
        }

        binding.close.setOnClickListener {
            binding.replyLayout.isVisible = false
            mediaType = MEDIA_TYPE.TEXT
        }

        val messageSwipeController = MessageSwipeController(this, object : SwipeControllerActions{
            override fun showReplyUI(position: Int) {
                mediaType = MEDIA_TYPE.REPLY_MESSAGE
                val item = chatList[position]
                swipeToReply(item, position)
            }
        })

        val itemTouchHelper = ItemTouchHelper(messageSwipeController)
        itemTouchHelper.attachToRecyclerView(binding.recChatList)
    }

    private fun getCurrentUserDetail(userModel: UserModel?) {
        val userDetailRef = db.collection("User").document(userModel?.phoneNumber.toString())
        userDetailRef.addSnapshotListener { snapshot, exception ->
            if (exception != null) {
                Log.w("FirestoreError", "Listen failed.", exception)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val userData = snapshot.data
                if (userData != null) {
                    val firstName = userData["firstName"] as? String
                    val lastName = userData["lastName"] as? String
                    val gender = userData["gender"] as? String
                    val profileImageURL = userData["profileImageURL"] as? String
                    val blockedUser = userData["blockedUser"] as? List<String>
                    val shuffledName = userData["shuffledName"] as? String
                    val allowedUsersList = userData["allowedUsersList"] as? List<String>
                    val isActive = userData["isActive"] as? String
                    val accountPrivacy = userData["accountPrivacy"] as? String
                    blockedByUsers = blockedUser!!.toMutableList()
                    fullName = if (accountPrivacy == "public" || accountPrivacy == "private" && allowedUsersList!!.contains(SharedPrefHelper.getPhoneNumber())) {
                        if (lastName.isNullOrEmpty()) "$firstName" else "$firstName $lastName"
                    } else {
                        if (shuffledName.isNullOrEmpty()) {
                            if (lastName.isNullOrEmpty()) "$firstName" else "$firstName $lastName"
                        } else {
                            shuffledName.toString()
                        }
                    }
                    binding.chatUserName.text = fullName
                    binding.allowUser.isVisible = SharedPrefHelper.getAccountPrivacy() != "public"

                    if (!this@ChatActivity.isFinishing && !this@ChatActivity.isDestroyed) {
                        if (accountPrivacy == "public" || accountPrivacy == "private" && allowedUsersList!!.contains(SharedPrefHelper.getPhoneNumber())) {
                            val imageUrl = when (gender) {
                                "Male" -> R.drawable.male
                                "Female" -> R.drawable.female
                                else -> R.drawable.other_gender
                            }
                            Glide.with(this@ChatActivity).load(profileImageURL).error(imageUrl).into(binding.userImage)
                        } else {
                            binding.userImage.setImageResource(when (gender) {
                                "Male" -> R.drawable.male
                                "Female" -> R.drawable.female
                                else -> R.drawable.other_gender
                            })
                        }
                    }

                    isUserActive = isActive == senderId
                    if (isUserActive) {
                        markMessagesAsRead(receiverId, senderId)
                    }
                }
            } else {
                Log.d("UserDetail", "Current data: null")
            }
        }
    }

    private fun swipeToReply(item: MessageModel, position: Int) {
        repliedMsgIndexPathRow = position
        repliedMsg = item.messageContent
        repliedMsgTimestamp = item.timestamp
        if (item.senderID == SharedPrefHelper.getPhoneNumber()){
            repliedUserName = SharedPrefHelper.getUserName()
            binding.replyUserName.text = SharedPrefHelper.getUserName()
        } else {
            repliedUserName = fullName
            binding.replyUserName.text = fullName
        }
        binding.replyLayout.isVisible = true
        binding.replyMessage.text = item.messageContent
    }

    private fun markMessagesAsRead(senderID: String, receiverID: String) {
        val chatCollection = db.collection("Chats")
            .document(senderID)
            .collection(receiverID)

        // Listen for changes where readStatus is false
        chatCollection.whereEqualTo("readStatus", false)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("Firestore Listener", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null && !snapshots.isEmpty) {
                    for (document in snapshots.documents) {
                        // Update readStatus to true
                        if(isUserActive){
                        document.reference.update("readStatus", true)
                            .addOnSuccessListener {
                                Log.e("Firestore Update", "Updated document: ${document.id}")
                            }
                            .addOnFailureListener { updateError ->
                                Log.e("Firestore Update", "Error updating document", updateError)
                            }
                        }
                    }
                }
            }
    }


    private fun getBlockedUsersList() {
        val userRef = db.collection("User").document(SharedPrefHelper.getPhoneNumber())

        // Listen for real-time updates on the user's block status
        userRef.addSnapshotListener { documentSnapshot, e ->
            if (e != null) {
                println("Listen failed: $e")
                return@addSnapshotListener
            }

            if (documentSnapshot != null && documentSnapshot.exists()) {
                val blockUsers = documentSnapshot.get("blockedUser") as? List<String> ?: emptyList()
                val blockedBy = documentSnapshot.get("blockedBy") as? List<String> ?: emptyList()

                blockedUsers = blockUsers.toMutableList()
                val isBlockedUser = blockUsers.contains(receiverId)
                val isBlockedBy = blockedBy.contains(receiverId)

                binding.apply {
                    when {
                        isBlockedUser -> {
                            blockText.text = getString(R.string.you_blocked_this_user)
                            blockText.isVisible = true
                            sendChat.isVisible = false
                            messageContent.isVisible = false
                        }
                        isBlockedBy -> {
                            blockText.text = getString(R.string.blocked_by)
                            blockText.isVisible = true
                            sendChat.isVisible = false
                            messageContent.isVisible = false
                        }
                        else -> {
                            blockText.isVisible = false
                            sendChat.isVisible = true
                            messageContent.isVisible = true
                        }
                    }
                }
            }
        }
    }


    private fun getAllowedUsersList(userModel: UserModel?) {
        db.collection("User").document(SharedPrefHelper.getPhoneNumber())
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val allowedUsersList = document.get("allowedUsersList") as? List<String> ?: emptyList()
                    SharedPrefHelper.saveAllowedUsersList(allowedUsersList)
                    val isUserAllowed = userModel?.phoneNumber?.let { allowedUsersList.contains(it) } == true
                    binding.allowUser.setImageResource(
                        if (isUserAllowed) R.drawable.allow_user_unselect else R.drawable.allow_user
                    )
                } else {
                    Log.e("Document does not exist", document.toString())
                }
            }
            .addOnFailureListener { exception ->
                Log.e("Error retrieving document: ${exception.message}", exception.message.toString())
            }
    }


    private fun allowUsers(userModel: UserModel?) {
        binding.progressBar.isVisible = true
        val chatListRef = db.collection("User").document(SharedPrefHelper.getPhoneNumber())

        chatListRef.get().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val document = task.result
                if (document != null && document.exists()) {
                    val allowedUsersList = document.get("allowedUsersList") as? List<String> ?: emptyList()
                    val phoneNumber = userModel?.phoneNumber

                    if (phoneNumber != null) {
                        if (allowedUsersList.contains(phoneNumber)) {
                            // Remove phone number if it exists
                            chatListRef.update("allowedUsersList", FieldValue.arrayRemove(phoneNumber))
                            getAllowedUsersList(userModel)
                        } else {
                            // Add phone number if it does not exist
                            chatListRef.update("allowedUsersList", FieldValue.arrayUnion(phoneNumber))
                            getAllowedUsersList(userModel)
                        }
                    }
                } else {
                    // If the document doesn't exist, create it and add the phone number
                    chatListRef.set(mapOf("allowedUsersList" to listOf(userModel?.phoneNumber)))
                }
            } else {
                Log.e("Error updating chat list: ", task.exception?.message.toString())
            }
            binding.progressBar.isVisible = false
        }
    }

    private fun fetchFCMToken(userModel: UserModel?) {
        db.collection("User").document(userModel?.phoneNumber.toString())
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    fcmToken = document.getString("fcmToken").toString()
                }
            }
            .addOnFailureListener {
                Log.e("addOnFailureListener ${it.message}", it.message.toString())
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                openCamera()
            } else {
                showToast("Camera permission is required to capture images")
            }
        }
    }

    private fun setupRecyclerView() {
        binding.recChatList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true // Start the RecyclerView at the bottom
        }
        binding.recChatList.adapter = mChatAdapter
    }

    private fun sendMessage(senderID: String, receiverID: String, messageContent: String, fcmToken: String) {
        val currentDate = Date()
        val currentTimestamp = Timestamp(currentDate)
        val chatDocRef = db.collection("Chats").document(senderID)

        chatDocRef.get().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val documentSnapshot = task.result
                if (documentSnapshot != null && documentSnapshot.exists()) {
                    // If sender's document exists, add message to the sender's collection
                    db.collection("Chats")
                        .document(senderID)
                        .collection(receiverID)
                        .add(
                            mapOf(
                                "docName" to pdfName,
                                "mediaType" to mediaType,
                                "mediaURL" to mediaUrl,
                                "messageContent" to messageContent,
                                "receiverID" to receiverID,
                                "repliedMsg" to repliedMsg,
                                "repliedUserName" to repliedUserName,
                                "repliedMsgTimestamp" to repliedMsgTimestamp,
                                "senderID" to senderID,
                                "timestamp" to currentTimestamp,
                                "readStatus" to false,
                            )
                        ).addOnCompleteListener { messageTask ->
                            if (messageTask.isSuccessful) {
                                Log.e("Message sent successfully!","")
                                mediaType = ""
                                repliedMsg = ""
                                repliedUserName = ""
                                repliedMsgIndexPathRow = 0
                                repliedMsgIndexPathSection = 0
                                repliedMsgTimestamp = currentTimestamp
                            } else {
                                Log.e("Error sending message: ", messageTask.exception?.message.toString())
                            }
                        }
                } else {
                    // If sender's document does not exist, add message to the receiver's collection
                    db.collection("Chats")
                        .document(receiverID)
                        .collection(senderID)
                        .add(
                            mapOf(
                                "docName" to pdfName,
                                "mediaType" to mediaType,
                                "mediaURL" to mediaUrl,
                                "messageContent" to messageContent,
                                "receiverID" to receiverID,
                                "repliedMsg" to repliedMsg,
                                "repliedMsgTimestamp" to repliedMsgTimestamp,
                                "repliedUserName" to repliedUserName,
                                "senderID" to senderID,
                                "timestamp" to currentTimestamp,
                                "readStatus" to false,
                            )
                        ).addOnCompleteListener { messageTask ->
                            if (messageTask.isSuccessful) {
                                Log.e("Message sent successfully!","")
                                mediaType = ""
                                repliedMsg = ""
                                repliedUserName = ""
                                repliedMsgIndexPathRow = 0
                                repliedMsgIndexPathSection = 0
                                repliedMsgTimestamp = currentTimestamp
                            } else {
                                Log.e("Error sending message: ", messageTask.exception?.message.toString())
                            }
                        }
                }
            } else {
                Log.e("Error sending message: ", task.exception?.message.toString())
            }
        }
//        // Update chat lists
        updateChatList(senderID, receiverID)
        updateChatList(receiverID, senderID)

        // Send push notification
        val notificationUserName = when {
            SharedPrefHelper.getAccountPrivacy() == "public" -> SharedPrefHelper.getUserName()
            SharedPrefHelper.getAllowedUsersList().contains(receiverId) -> SharedPrefHelper.getUserName()
            else -> SharedPrefHelper.getShuffleName()
        }

        val notificationData = mapOf(
            "nType" to "1",
            "senderId" to receiverID,
            "fcmToken" to fcmToken,
            "receiverId" to senderID,
            "receiverUserName" to notificationUserName
        )

        sendPushNotification(
            deviceToken = fcmToken,
            message = "$notificationUserName sent you a $notificationType",
            data = notificationData,
            SharedPrefHelper.getFirebaseAuthToken()
        )
    }

    // Function to update the chat list (you can define your own implementation)
    private fun updateChatList(userID: String, chatPartnerID: String) {
        val chatListRef = db.collection("User").document(userID)

        chatListRef.get().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val chatListDocument = task.result
                if (chatListDocument != null && chatListDocument.exists()) {
                    chatListRef.update(
                        "chatWith", FieldValue.arrayUnion(chatPartnerID)
                    )
                } else {
                    chatListRef.set(
                        mapOf("chatWith" to listOf(chatPartnerID))
                    )
                }
            } else {
                Log.e("Error updating chat list: ", task.exception?.message.toString())
            }
        }
    }

    private fun sendPushNotification(deviceToken: String, message: String, data: Map<String, Any>, firebaseAuthToken: String) {
        try {
            val url = "https://fcm.googleapis.com/v1/projects/chatcorner-1fb20/messages:send"
            val client = OkHttpClient()
            val jsonBody = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("token", deviceToken)
                    put("notification", JSONObject().apply {
                        put("title", "ChatCorner")
                        put("body", message)
                    })
                    put("data", JSONObject(data))
                    put("android", JSONObject().apply {
                        put("priority", "high") // Ensures the data is delivered quickly
                    })
                })
            }

            val requestBody = RequestBody.create(
                "application/json; charset=utf-8".toMediaTypeOrNull(),
                jsonBody.toString()
            )

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $firebaseAuthToken")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("sendPushNotification Error sending push notification:", e.message.toString())
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.e("sendPushNotification Push notification sent successfully", "")
                    } else {
                        val errorBody = response.body?.string()
                        Log.e("sendPushNotification Failed to send push notification: ${response.code}, Error: $errorBody", errorBody.toString())
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("Error: ",e.message.toString())
        }
    }

    private fun fetchChatMessages(senderID: String, receiverID: String, userModel: UserModel?) {
        val chatDeleteAt = SharedPrefHelper.getChatDeletedAt()
        val deleteChatByOtherUser = userModel?.chatDeletedAt
        val deleteChatByOtherUserTimestamp = deleteChatByOtherUser?.get(senderID)
        val deletedTimestamp = chatDeleteAt[receiverID]

        val senderCollection = db.collection("Chats")
            .document(senderID)
            .collection(receiverID)
            .orderBy("timestamp")

        val receiverCollection = db.collection("Chats")
            .document(receiverID)
            .collection(senderID)
            .orderBy("timestamp")

        senderCollection.addSnapshotListener { senderSnapshot, senderError ->
            if (senderError != null) {
                Log.e("ChatFetch", "Error fetching sender chat messages: ${senderError.message}")
                return@addSnapshotListener
            }

            receiverCollection.addSnapshotListener { receiverSnapshot, receiverError ->
                if (receiverError != null) {
                    Log.e("ChatFetch", "Error fetching receiver chat messages: ${receiverError.message}")
                    return@addSnapshotListener
                }

                val combinedMessages = mutableListOf<MessageModel>()

                senderSnapshot?.let { snapshot ->
                    for (document in snapshot.documents) {
                        val message = documentToMessageModel(document)
                        if (shouldIncludeMessage(message, deletedTimestamp, deleteChatByOtherUserTimestamp)) {
                            combinedMessages.add(message)
                        }
                    }
                }

                receiverSnapshot?.let { snapshot ->
                    for (document in snapshot.documents) {
                        val message = documentToMessageModel(document)
                        if (shouldIncludeMessage(message, deletedTimestamp, deleteChatByOtherUserTimestamp)) {
                            combinedMessages.add(message)
                        }
                    }
                }

                combinedMessages.sortBy { it.timestamp.seconds }

                val dateList: MutableList<ChatItem.DateItem> = mutableListOf()
                mChatAdapter.setData(combinedMessages, dateList)
                chatList = combinedMessages

                binding.recChatList.post {
                    val lastPosition = mChatAdapter.itemCount - 1
                    if (lastPosition >= 0) {
                        binding.recChatList.scrollToPosition(lastPosition)
                    }
                }
            }
        }
    }

    private fun shouldIncludeMessage(message: MessageModel, deletedTimestamp: Timestamp?, deleteChatByOtherUserTimestamp: Timestamp?): Boolean {
        val greaterTimestamp = getGreaterTimestamp(deletedTimestamp, deleteChatByOtherUserTimestamp)
        return greaterTimestamp == null || message.timestamp > greaterTimestamp
    }

    private fun getGreaterTimestamp(ts1: Timestamp?, ts2: Timestamp?): Timestamp? {
        if (ts1 == null && ts2 == null) return null
        if (ts1 == null) return ts2
        if (ts2 == null) return ts1
        return if (ts1.seconds > ts2.seconds || (ts1.seconds == ts2.seconds && ts1.nanoseconds > ts2.nanoseconds)) ts1 else ts2
    }


    private fun documentToMessageModel(document: DocumentSnapshot): MessageModel {
        val messageContent = document.getString("messageContent") ?: ""
        val receiverId = document.getString("receiverID") ?: ""
        val senderId = document.getString("senderID") ?: ""
        val mediaType = document.getString("mediaType") ?: ""
        val mediaURL = document.getString("mediaURL") ?: ""
        val docName = document.getString("docName") ?: ""
        val repliedMsg = document.getString("repliedMsg") ?: ""
        val repliedUserName = document.getString("repliedUserName") ?: ""
        val readStatus = document.getBoolean("readStatus") ?: false
        val timestamp = document.get("timestamp")

        val repliedMsgTimestampValue = when (val repliedMsgTimestamp = document.get("repliedMsgTimestamp")) {
            is Timestamp -> repliedMsgTimestamp // Field is already a Timestamp
            is String -> {
                try {
                    // Parse the String to Date and then to Timestamp
                    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
                    formatter.timeZone = TimeZone.getTimeZone("UTC")
                    val date = formatter.parse(repliedMsgTimestamp)
                    Timestamp(date!!)
                } catch (e: Exception) {
                    Log.e("Error parsing timestamp string: ",e.message.toString())
                    Timestamp.now() // Fallback
                }
            }

            is Long -> Timestamp(Date(repliedMsgTimestamp)) // If stored as a Unix timestamp
            else -> Timestamp.now() // Fallback to the current time
        }
        val timestampValue = when (timestamp) {
            is Timestamp -> timestamp // Field is already a Timestamp
            is String -> {
                try {
                    // Parse the String to Date and then to Timestamp
                    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
                    formatter.timeZone = TimeZone.getTimeZone("UTC")
                    val date = formatter.parse(timestamp)
                    Timestamp(date!!)
                } catch (e: Exception) {
                    Log.e("Error parsing timestamp string: ",e.message.toString())
                    Timestamp.now() // Fallback
                }
            }

            is Long -> Timestamp(Date(timestamp)) // If stored as a Unix timestamp
            else -> Timestamp.now() // Fallback to the current time
        }

        return MessageModel(
            messageContent = messageContent,
            receiverID = receiverId,
            senderID = senderId,
            timestamp = timestampValue,
            mediaType = mediaType,
            mediaURL = mediaURL,
            docName = docName,
            repliedMsg = repliedMsg,
            repliedUserName = repliedUserName,
            repliedMsgTimestampValue = repliedMsgTimestampValue,
            readStatus = readStatus,
        )
    }

    private fun customDialog(isPhoto: Boolean) {
        val builder = AlertDialog.Builder(this, R.style.TransparentDialogTheme)
        val view = MediaAlertBinding.inflate(LayoutInflater.from(this))
        builder.setView(view.root)
        val dialog = builder.create()
        view.camera.setOnClickListener {
            if (isPhoto) {
                openCamera()
            } else {
                if (hasPermissions()) {
                    captureVideo()
                } else {
                    requestPermissions()
                }
            }
            dialog.dismiss()
        }
        view.gallery.setOnClickListener {
            if (isPhoto) {
                galleryLauncher.launch("image/*")
                isVideo = false
            } else {
                isVideo = true
                galleryLauncher.launch("video/*")
            }
            dialog.dismiss()
        }
        dialog.show()
        val params = dialog.window?.attributes
        val margin = (24 * this.resources.displayMetrics.density).toInt() // Convert 24dp to pixels
        params?.width = this.resources.displayMetrics.widthPixels - 2 * margin
        dialog.window?.attributes = params
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun openDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            // Filter for document MIME types
            type = "application/pdf" // Change to desired type, e.g., for PDFs
            addCategory(Intent.CATEGORY_OPENABLE)

            // Accept multiple document types
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/pdf",
                    "application/msword", // .doc
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // .docx
                    "text/plain", // .txt
                    "application/vnd.ms-excel", // .xls
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" // .xlsx
                )
            )
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_DOCUMENT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_DOCUMENT && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                uploadDocumentToFirebase(uri)
            }
        }
    }

    private fun uploadDocumentToFirebase(uri: Uri) {
        binding.progressBar.isVisible = true
        val randomText = UUID.randomUUID().toString()
        val fileName = getFileNameFromUri(uri) ?: "UnknownFile_${System.currentTimeMillis()}.pdf"
        pdfName = fileName
        val fullFileName = randomText + "_" + fileName
        val storageReference: StorageReference =
            FirebaseStorage.getInstance().reference.child("chatDocuments/$fullFileName")
        storageReference.putFile(uri)
            .addOnSuccessListener { taskSnapshot ->
                // Retrieve the download URL
                taskSnapshot.storage.downloadUrl.addOnSuccessListener { downloadUrl ->
                    // Optionally send the download URL to chat
                    mediaUrl = downloadUrl.toString()
                    notificationType = "pdf"
                    sendMessage(senderId, receiverId, "", fcmToken)
                }
                binding.progressBar.isVisible = false
            }
            .addOnFailureListener { exception ->
                Toast.makeText(this, "Upload failed: ${exception.message}", Toast.LENGTH_SHORT)
                    .show()
            }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName
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
                    if (cursor.moveToFirst()) {
                        return cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                    }
                }
            }
        }
        return uri.path?.lastIndexOf('/')?.let { uri.path?.substring(it) }
    }


    private fun hasPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val storagePermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return cameraPermission == PackageManager.PERMISSION_GRANTED && storagePermission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_PERMISSION_CODE
        )
    }

    private fun captureVideo() {
        val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        videoFile = createVideoFile()
        videoUri = FileProvider.getUriForFile(this, "${packageName}.provider", videoFile)
        videoIntent.putExtra(MediaStore.EXTRA_OUTPUT, videoUri)
        videoCaptureLauncher.launch(videoIntent)
    }

    private fun createVideoFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return File.createTempFile("VIDEO_${timestamp}_", ".mp4", storageDir)
    }

    private fun uploadVideo(uri: Uri) {
        binding.progressBar.isVisible = true
        val randomText = UUID.randomUUID().toString()
        val fileName = getFileNameFromUri(uri) ?: "UnknownFile_${System.currentTimeMillis()}.mp4"
        val fullFileName = randomText + "_" + fileName
        val storageReference: StorageReference =
            FirebaseStorage.getInstance().reference.child("chatVideos/$fullFileName")
        storageReference.putFile(uri)
            .addOnSuccessListener { taskSnapshot ->
                // Retrieve the download URL
                taskSnapshot.storage.downloadUrl.addOnSuccessListener { downloadUrl ->
                    mediaUrl = downloadUrl.toString()
                    notificationType = "video"
                    sendMessage(senderId, receiverId, "", fcmToken)
                    binding.progressBar.isVisible = false
                }
            }
            .addOnFailureListener { exception ->
                Toast.makeText(this, "Upload failed: ${exception.message}", Toast.LENGTH_SHORT)
                    .show()
            }
    }

    private fun showPopupMenu(anchorView: View, blockedUsersList: List<String>) {
        val binding: PopupMenuBinding = DataBindingUtil.inflate(LayoutInflater.from(anchorView.context), R.layout.popup_menu, null, false)
        val popupWindow = PopupWindow(
            binding.root, // Pass the root view from binding
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            true // Focusable
        )
        popupWindow.elevation = 15f // Adjust as needed
        binding.root.setOnClickListener {
            blockUser(popupWindow, receiverId)
        }
        if (blockedUsersList.contains(receiverId)){
            binding.block.text = getString(R.string.unblocked)
        } else {
            binding.block.text = getString(R.string.block)
        }
        binding.root.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED
        )
        val popupWidth = binding.root.measuredWidth // Get the measured width of the popup

        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        val anchorLocation = IntArray(2)
        anchorView.getLocationOnScreen(anchorLocation)
        val anchorRight = anchorLocation[0] + anchorView.width
        val xOffset = screenWidth - (anchorRight + popupWidth) + 80
        val yOffset = 50 // Adjust for top margin
        popupWindow.showAsDropDown(anchorView, xOffset, yOffset)
    }
    private fun blockUser(popupWindow: PopupWindow, receiverId: String) {
        val userPhoneNumber = SharedPrefHelper.getPhoneNumber()
        popupWindow.dismiss()

        fun updateBlockedList(documentId: String, field: String, value: String, onSuccess: () -> Unit) {
            val userRef = db.collection("User").document(documentId)
            userRef.get().addOnSuccessListener { document ->
                val list = (document?.get(field) as? List<*>) ?: emptyList<Any>()
                val update = if (list.contains(value)) FieldValue.arrayRemove(value) else FieldValue.arrayUnion(value)
                userRef.update(field, update)
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { e ->
                        Log.e("Error updating $field for, $documentId: ${e.message}", documentId)
                    }
            }.addOnFailureListener { e ->
                Log.e("Error retrieving document for $documentId: ${e.message}", e.message.toString())
            }
        }

        updateBlockedList(userPhoneNumber, "blockedUser", receiverId) {
            getBlockedUsersList()
        }

        updateBlockedList(receiverId, "blockedBy", userPhoneNumber) {
            Log.e("User $userPhoneNumber successfully updated in blockedBy list.", userPhoneNumber)
        }
    }
    private fun updateIsActive(isActive: String){
        db.collection("User").document(senderId).update(
            "isActive", isActive
        ).addOnSuccessListener { Log.e("Chat Activity", "isActive user update") }
            .addOnFailureListener { Log.e("Chat Activity", "isActive failed to update")  }
    }
    private fun updateOnlineUserStatus() {
        val userRef = db.collection("User").document(receiverId)
        // Listen for real-time updates on the user's online status
        userRef.addSnapshotListener { documentSnapshot, e ->
            if (e != null) {
                println("Listen failed: $e")
                return@addSnapshotListener
            }

            if (documentSnapshot != null && documentSnapshot.exists()) {
                val isOnline = documentSnapshot.getString("isOnline")
                val timestampValue = when (val lastSeen = documentSnapshot.get("lastSeen")) {
                    is Timestamp -> lastSeen
                    is String -> {
                        try {
                            val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
                            formatter.timeZone = TimeZone.getTimeZone("UTC")
                            val date = formatter.parse(lastSeen)
                            Timestamp(date!!)
                        } catch (e: Exception) {
                            Timestamp.now()
                        }
                    }
                    is Long -> Timestamp(Date(lastSeen))
                    else -> Timestamp.now()
                }

                binding.onlineStatus.text = if (isOnline == "true") {
                    getString(R.string.online)
                } else {
                    val lastSeenAt = getString(R.string.lastSeenAt) + " " + ThemeUtils.convertMessageTimestampToTime(timestampValue)
                    lastSeenAt
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        FirebaseExtension.userOnlineStatus("true")
        updateIsActive(receiverId)
    }
    override fun onPause() {
        super.onPause()
        updateIsActive("")
    }
    override fun onDestroy() {
        super.onDestroy()
        updateIsActive("")
    }
    override fun onLongPress(messageModel: MessageModel, isIncomingChat: Boolean, position: Int) {
        val customDialog = LongPressReplyDialog(this, isIncomingChat)
        customDialog.setOnReplyClickListener {
            mediaType = MEDIA_TYPE.REPLY_MESSAGE
            repliedMsgIndexPathRow = position
            repliedMsg = messageModel.messageContent
            if (messageModel.senderID == SharedPrefHelper.getPhoneNumber()){
                repliedUserName = SharedPrefHelper.getUserName()
                binding.replyUserName.text = SharedPrefHelper.getUserName()
            } else {
                repliedUserName = fullName
                binding.replyUserName.text = fullName
            }
            binding.replyLayout.isVisible = true
            binding.replyMessage.text = messageModel.messageContent

        }
        customDialog.setOnDeleteClickListener {
            deleteChatItem(messageModel, position)
        }
        customDialog.setCancelable(true)
        customDialog.show()
    }
    private fun deleteChatItem(messageModel: MessageModel, position: Int) {
        val db = FirebaseFirestore.getInstance()
        val senderCollection = db.collection("Chats")
            .document(senderId)
            .collection(receiverId)
            .whereEqualTo("timestamp", messageModel.timestamp)

        val receiverCollection = db.collection("Chats")
            .document(receiverId)
            .collection(senderId)
            .whereEqualTo("timestamp", messageModel.timestamp)

        val layoutManager = binding.recChatList.layoutManager as LinearLayoutManager
        val currentPosition = layoutManager.findFirstVisibleItemPosition()
        val offset = layoutManager.findViewByPosition(currentPosition)?.top ?: 0

        // Delete from sender's collection
        senderCollection.get().addOnSuccessListener { documents ->
            for (document in documents) {
                document.reference.delete()
                    .addOnSuccessListener {
                        Log.e("Chat Activity", "Deleted from sender's collection")
//                        binding.recChatList.post {
//                            layoutManager.scrollToPositionWithOffset(currentPosition, offset)
//                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.e("Chat Activity", "Error deleting from sender's collection: ${exception.message}")
                    }
            }
        }

        // Delete from receiver's collection
        receiverCollection.get().addOnSuccessListener { documents ->
            for (document in documents) {
                document.reference.delete()
                    .addOnSuccessListener {
                        Log.e("Chat Activity", "Deleted from receiver's collection.")
                        // Restore the position after deleting the item
                        binding.recChatList.post {
                            layoutManager.scrollToPositionWithOffset(currentPosition, offset)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.e("Chat Activity", "Error deleting from receiver's collection: ${exception.message}")
                    }
            }
        }
    }
    override fun onItemClick(messageModel: MessageModel, isIncomingChat: Boolean, position: Int, itemBinding: ViewBinding) {
        val targetPosition = chatList.indexOfFirst { it.timestamp == messageModel.repliedMsgTimestampValue }
        if (targetPosition != -1) {
            binding.recChatList.smoothScrollToPosition(targetPosition)
            // Post the background color change on the RecyclerView's thread
            binding.recChatList.post {
                // Check if the targetViewHolder is visible or not
                val targetViewHolder = binding.recChatList.findViewHolderForAdapterPosition(targetPosition)

                // If the ViewHolder is found (visible), change its background
                targetViewHolder?.itemView?.setBackgroundColor(ContextCompat.getColor(itemBinding.root.context, R.color.tapMessageColor))

                // If ViewHolder is not visible, update the adapter item
                if (targetViewHolder == null) {
                    val adapterItemView = binding.recChatList.findViewHolderForAdapterPosition(targetPosition)?.itemView
                    adapterItemView?.setBackgroundColor(ContextCompat.getColor(itemBinding.root.context, R.color.tapMessageColor))
                }

                // Reset background color after 5 seconds
                Handler(Looper.getMainLooper()).postDelayed({
                    targetViewHolder?.itemView?.setBackgroundColor(ContextCompat.getColor(itemBinding.root.context, android.R.color.transparent))

                    // If not visible, reset the background color manually
                    if (targetViewHolder == null) {
                        binding.recChatList.findViewHolderForAdapterPosition(targetPosition)?.itemView?.setBackgroundColor(
                            ContextCompat.getColor(itemBinding.root.context, android.R.color.transparent)
                        )
                    }
                }, 3000)
            }
        }

    }
}

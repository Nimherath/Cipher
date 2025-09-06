package com.hush;

import static com.hush.utils.ImageUtil.convertBitmapToByteBuffer;
import static com.hush.utils.ImageUtil.toBitmap;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.firebase.ui.firestore.FirestoreRecyclerOptions;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.Query;
import com.hush.Models.ChatMessageModel;
import com.hush.Models.ChatroomModel;
import com.hush.Models.UserModel;
import com.hush.adapters.ChatRecyclerAdapter;
import com.hush.utils.AndroidUtil;
import com.hush.utils.DetectionModel;
import com.hush.utils.FirebaseUtil;
import com.hush.utils.ImageUtil;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import android.Manifest;

public class ChatActivity extends AppCompatActivity implements ChatRecyclerAdapter.OnImageOpenListener{

    UserModel otherUser;
    String chatroomId;
    ChatroomModel chatroomModel;
    ChatRecyclerAdapter adapter;

    EditText messageInput;
    ImageButton sendMessageBtn;
    ImageButton backBtn;
    TextView otherUsername;
    RecyclerView recyclerView;
    ImageView imageView;

    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int STORAGE_PERMISSION_REQUEST = 2;
    private Uri imageUri;
    private Dialog imageDialog;
    private ImageView selectedImage;
    private EditText imageMessageInput;
    Button viewOnceButton;
    CheckBox viewOnceCheckbox;

    private boolean imagePopupOpen = false;
    private boolean imageViewOnce = false;
    private int openImagePosition;
    private Dialog imagePopupDialog;
    private DetectionModel mDetectionModel;
    private ProcessCameraProvider cameraProvider;

    private boolean deviceDetectionStarted = false;
    private static final int DETECTION_DISPLAY_DURATION = 3000; // 3 seconds


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        mDetectionModel = new DetectionModel(this);

        //get UserModel
        otherUser = AndroidUtil.getUserModelFromIntent(getIntent());
        chatroomId = FirebaseUtil.getChatroomId(FirebaseUtil.currentUserId(),otherUser.getUserId());

        messageInput = findViewById(R.id.chat_message_input);
        sendMessageBtn = findViewById(R.id.message_send_btn);
        backBtn = findViewById(R.id.back_btn);
        otherUsername = findViewById(R.id.other_username);
        recyclerView = findViewById(R.id.chat_recycler_view);
        imageView = findViewById(R.id.profile_pic_image_view);

        if(otherUser.getProfileImage() != null){
            imageView.setImageBitmap(ImageUtil.getBitmapImage(otherUser.getProfileImage()));
        }

        backBtn.setOnClickListener((v)->{
            onBackPressed();
        });
        otherUsername.setText(otherUser.getUsername());

        sendMessageBtn.setOnClickListener((v -> {
            String message = messageInput.getText().toString().trim();
            if(message.isEmpty())
                return;
            sendMessageToUser(message, null, false);
        }));

        // When delete button is clicked
        findViewById(R.id.delete_btn).setOnClickListener(v -> {
            Set<Integer> selectedPositions = adapter.getSelectedPositions();
            String currentUserId = FirebaseUtil.currentUserId();
            AtomicBoolean allMessagesFromCurrentUser = new AtomicBoolean(true);
            for (Integer pos : selectedPositions) {
                String documentId = adapter.getSnapshots().getSnapshot(pos).getId();
                DocumentReference messageRef = FirebaseUtil.getChatroomMessageReference(chatroomId).document(documentId);

                messageRef.get().addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        ChatMessageModel messageModel = documentSnapshot.toObject(ChatMessageModel.class);
                        if (!messageModel.getSenderId().equals(currentUserId)) {
                            allMessagesFromCurrentUser.set(false);
                        }
                    }
                });
            }

            showDeletionOptionsDialog(allMessagesFromCurrentUser, selectedPositions, currentUserId);
        });

        getOrCreateChatroomModel();
        setupChatRecyclerView();

        ImageButton attachBtn = findViewById(R.id.attach_btn);
        attachBtn.setOnClickListener(v -> checkStoragePermission());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.d("ChatActivity", "Back pressed");
                if (!adapter.getSelectedPositions().isEmpty()) {
                    adapter.clearSelections();
                }else {
                    // Default back behavior (finishes activity or navigates back)
                    finish();
                }
            }
        });
    }

    void getOrCreateChatroomModel(){
        FirebaseUtil.getChatroomReference(chatroomId).get().addOnCompleteListener(task -> {
            if(task.isSuccessful()){
                chatroomModel = task.getResult().toObject(ChatroomModel.class);
                if(chatroomModel==null){
                    //first time chat
                    chatroomModel = new ChatroomModel(
                            chatroomId,
                            Arrays.asList(FirebaseUtil.currentUserId(),otherUser.getUserId()),
                            Timestamp.now(),
                            ""
                    );
                    FirebaseUtil.getChatroomReference(chatroomId).set(chatroomModel);
                }
            }
        });
    }

    // Get OAuth 2.0 Token from Service Account JSON
    private static String getAccessToken(Context context) throws IOException {
        InputStream inputStream = context.getAssets().open("service-account.json");
        GoogleCredentials googleCredentials = GoogleCredentials
                .fromStream(inputStream)
                .createScoped(Collections.singletonList("https://www.googleapis.com/auth/firebase.messaging"));
        googleCredentials.refreshIfExpired();
        return googleCredentials.getAccessToken().getTokenValue();
    }

    void setupChatRecyclerView(){
        Query query = FirebaseUtil.getChatroomMessageReference(chatroomId)
                .orderBy("timestamp", Query.Direction.DESCENDING);

        FirestoreRecyclerOptions<ChatMessageModel> options = new FirestoreRecyclerOptions.Builder<ChatMessageModel>()
                .setQuery(query,ChatMessageModel.class).build();

        adapter = new ChatRecyclerAdapter(options,ChatActivity.this);
        adapter.setViewOnceImageOpenListener(this);
        LinearLayoutManager manager = new LinearLayoutManager(this);
        manager.setReverseLayout(true);
        recyclerView.setLayoutManager(manager);
        recyclerView.setAdapter(adapter);
        adapter.startListening();
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                recyclerView.smoothScrollToPosition(0);
            }
        });

        // Inside ChatActivity after initializing adapter
        adapter.setOnSelectionChangedListener(count -> {
            // Show delete button if count > 0, hide if count == 0
            if(count > 0) {
                findViewById(R.id.delete_btn).setVisibility(View.VISIBLE);
            } else {
                findViewById(R.id.delete_btn).setVisibility(View.GONE);
            }
        });
    }

    void checkStoragePermission() {
        // Check if Android version is 13+ (API level 33)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                // Permission is not granted; request it
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                        STORAGE_PERMISSION_REQUEST);
            }else{
                openGallery();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Storage permission granted.");
                openGallery(); // Permission granted
            } else {
                Log.d("MainActivity", "Storage permission denied.");
                Toast.makeText(this, "Permission denied! Cannot access gallery.", Toast.LENGTH_SHORT).show();
            }
        }
    }


    private void openGallery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+ (API 33+)
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.READ_MEDIA_IMAGES},
                        STORAGE_PERMISSION_REQUEST);
                return;
            }
        } else { // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_REQUEST);
                return;
            }
        }

        // Permission granted, open gallery
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            imageUri = data.getData();
            showImagePopup();
        }
    }

    private void showImagePopup() {
        imageDialog = new Dialog(this);
        imageDialog.setContentView(R.layout.popup_send_image);

        selectedImage = imageDialog.findViewById(R.id.selected_image);
        imageMessageInput = imageDialog.findViewById(R.id.image_message_input);
        Button sendImageBtn = imageDialog.findViewById(R.id.send_image_btn);
        Button cancelImageBtn = imageDialog.findViewById(R.id.cancel_image_btn);
        viewOnceButton = imageDialog.findViewById(R.id.view_once_btn);
        viewOnceCheckbox = imageDialog.findViewById(R.id.view_once_checkbox);

        selectedImage.setImageURI(imageUri);

        sendImageBtn.setOnClickListener(v -> sendImageMessage());
        cancelImageBtn.setOnClickListener(v -> imageDialog.dismiss());

        viewOnceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Toggle the checkbox
                viewOnceCheckbox.setChecked(!viewOnceCheckbox.isChecked());

                // Toggle the button's background
                toggleButtonBackground();
            }
        });

        imageDialog.show();
    }

    private void toggleButtonBackground() {
        if (viewOnceCheckbox.isChecked()) {
            // Set the background to the checked state
            viewOnceButton.setBackground(ContextCompat.getDrawable(this, R.drawable.button_rounded_solid));
            viewOnceButton.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            // Set the background to the unchecked state
            viewOnceButton.setBackground(ContextCompat.getDrawable(this, R.drawable.button_rounded));
            viewOnceButton.setTextColor(ContextCompat.getColor(this, R.color.black));
        }
    }

    private void sendImageMessage() {
        try {
            InputStream inputStream = getContentResolver().openInputStream(imageUri);
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            String base64Image = ImageUtil.getCompressedBase64Image(bitmap);

            String message = imageMessageInput.getText().toString().trim();
            if(message.isEmpty())
                message = null;

            boolean viewOnce = viewOnceCheckbox.isChecked();

            // Send this message
            sendMessageToUser(message, base64Image, viewOnce);

            imageDialog.dismiss();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void sendMessageToUser(String message, String image, boolean isOneTime){
        chatroomModel.setLastMessageTimestamp(Timestamp.now());
        chatroomModel.setLastMessageSenderId(FirebaseUtil.currentUserId());
        if (image != null && !image.isEmpty()) {
            chatroomModel.setLastMessage("📷 Photo");
        } else {
            chatroomModel.setLastMessage(message);
        }

        FirebaseUtil.getChatroomReference(chatroomId).set(chatroomModel);

        ChatMessageModel chatMessageModel = new ChatMessageModel(message,FirebaseUtil.currentUserId(),Timestamp.now(), null, image, isOneTime);
        FirebaseUtil.getChatroomMessageReference(chatroomId).add(chatMessageModel)
                .addOnCompleteListener(new OnCompleteListener<DocumentReference>() {
                    @Override
                    public void onComplete(@NonNull Task<DocumentReference> task) {
                        if(task.isSuccessful()){
                            messageInput.setText("");
                            sendNotification(message, image, isOneTime);
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
    }

    void sendNotification(String message, String image, boolean isOneTime) {
        FirebaseUtil.currentUserDetails().get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                UserModel currentUser = task.getResult().toObject(UserModel.class);
                try {
                    JSONObject jsonObject = new JSONObject();
                    JSONObject messageObj = new JSONObject();
                    JSONObject notificationObj = new JSONObject();

                    // Notification content
                    notificationObj.put("title", currentUser.getUsername());
                    // Determine message body and icon based on imageUri
                    if (image != null && !image.isEmpty()) {
                        if(isOneTime){
                            notificationObj.put("body", "📷¹ Photo");
                        }else{
                            notificationObj.put("body", "📷 Photo");
                        }
                    } else {
                        notificationObj.put("body", message);
                    }

                    // Message Data (Custom Payload)
                    JSONObject dataObj = new JSONObject();
                    dataObj.put("userId", currentUser.getUserId());

                    // FCM v1 structure
                    messageObj.put("token", otherUser.getFcmToken());
                    messageObj.put("notification", notificationObj);
                    messageObj.put("data", dataObj);

                    jsonObject.put("message", messageObj);

                    // Call API with new FCM v1 format
                    callApi(this, jsonObject);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }


    // Define an ExecutorService
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void callApi(final Context context, final JSONObject jsonObject) {
        executor.execute(() -> {
            try {
                MediaType JSON = MediaType.get("application/json; charset=utf-8");
                OkHttpClient client = new OkHttpClient();

                // FCM v1 API URL
                String url = "url here";

                // Retrieve access token off the main thread
                String token = getAccessToken(context);

                RequestBody body = RequestBody.create(jsonObject.toString(), JSON);
                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .build();

                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        System.out.println("Failed to send notification: " + e.getMessage());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        System.out.println("FCM Response: " + response.body().string());
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void showDeletionOptionsDialog(AtomicBoolean allMessagesFromCurrentUser, Set<Integer> selectedPositions, String currentUserId) {
        // Build options based on selection
        String[] options;
        if (allMessagesFromCurrentUser.get()) {
            options = new String[]{"Delete for me", "Delete for everyone", "Cancel"};
        } else {
            options = new String[]{"Delete for me", "Cancel"};
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete Messages")
                .setItems(options, (dialog, which) -> {
                    // Process based on which option is selected
                    if (options[which].equals("Delete for me")) {
                        processDeletion(selectedPositions, currentUserId, false);
                    } else if (options[which].equals("Delete for everyone")) {
                        processDeletion(selectedPositions, currentUserId, true);
                    } else { // Cancel
                        // Do nothing.
                    }
                })
                .show();
    }

    private void processDeletion(Set<Integer> selectedPositions, String currentUserId, boolean deleteForEveryone) {
        // Iterate over selected messages and perform the chosen action.
        for (Integer pos : selectedPositions) {
            String documentId = adapter.getSnapshots().getSnapshot(pos).getId();
            DocumentReference messageRef = FirebaseUtil.getChatroomMessageReference(chatroomId).document(documentId);
            messageRef.get().addOnSuccessListener(documentSnapshot -> {
                if (documentSnapshot.exists()) {
                    ChatMessageModel messageModel = documentSnapshot.toObject(ChatMessageModel.class);
                    if (deleteForEveryone) {
                        // Delete permanently regardless of who sent it.
                        messageRef.delete().addOnCompleteListener(task -> {
                            if (task.isSuccessful()){
                                Log.d("Delete", "Message permanently deleted: " + documentId);
                            } else {
                                Log.e("Delete", "Permanent deletion failed for message: " + documentId);
                            }
                        });
                    } else {
                        // "Delete for me": Mark the message as deleted for current user only.
                        // But only update if not already deleted by someone else.
                        if (messageModel.getDeletedByUserId() == null) {
                            messageRef.update("deletedByUserId", currentUserId).addOnCompleteListener(task -> {
                                if(task.isSuccessful()){
                                    Log.d("Delete", "Message marked as deleted for current user: " + documentId);
                                } else {
                                    Log.e("Delete", "Failed to mark message as deleted: " + documentId);
                                }
                            });
                        } else {
                            // If already deleted by the other user, remove it permanently.
                            messageRef.delete().addOnCompleteListener(task -> {
                                if(task.isSuccessful()){
                                    Log.d("Delete", "Message permanently deleted (after both users deleted): " + documentId);
                                } else {
                                    Log.e("Delete", "Permanent deletion failed for message: " + documentId);
                                }
                            });
                        }
                    }
                }
            });
        }
        // Clear selection after processing all selected messages
        adapter.clearSelections();
    }

    @Override
    public void onImageOpen(int position, boolean viewOnce, Dialog dialog) {
        this.openImagePosition = position;
        this.imagePopupOpen = true;
        this.imageViewOnce = viewOnce;
        this.imagePopupDialog = dialog;

        if(viewOnce){
            Log.d("ChatActivity", "Start the model");
            startCamera();
        }
    }

    @Override
    public void onImageClose() {
        this.imagePopupOpen = false;
        if(this.imageViewOnce){
            handleViewOnceDeletion();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                this.cameraProvider = cameraProvider;

                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 640)) // Match input size
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), this::processImageProxy);

                //preview.setSurfaceProvider(previewView.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraXApp", "Error starting camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImageProxy(ImageProxy imageProxy) {
        if (imageProxy == null || imageProxy.getImage() == null) {
            return;
        }

        Image image = imageProxy.getImage();
        Bitmap bitmap = toBitmap(image);
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, DetectionModel.INPUT_SIZE, DetectionModel.INPUT_SIZE, true);
        ByteBuffer inputBuffer = convertBitmapToByteBuffer(resizedBitmap);

        if (mDetectionModel.isDeviceDetected(inputBuffer, image.getWidth(), image.getHeight())) {
            Log.d("ChatActivity", "Device detected");

            // Trigger only once
            if (!deviceDetectionStarted) {
                deviceDetectionStarted = true;

                // Show overlay
                runOnUiThread(() -> {
                    imagePopupDialog.findViewById(R.id.imageViewFull).setVisibility(View.GONE);
                    imagePopupDialog.findViewById(R.id.deviceWarningOverlay).setVisibility(View.VISIBLE);
                });

                // Start countdown to close everything
                new android.os.Handler().postDelayed(() -> {
                    runOnUiThread(() -> {
                        // Hide overlay and close popup
                        if (imagePopupDialog != null && imagePopupDialog.isShowing()) {
                            imagePopupDialog.dismiss();
                        }
                        // Delete the message + stop camera
                        handleViewOnceDeletion();
                        adapter.setIsImagePopupOpen(false);
                    });
                }, DETECTION_DISPLAY_DURATION);
            }
        }

        imageProxy.close();
    }

    private void handleViewOnceDeletion() {
        String documentId = adapter.getSnapshots().getSnapshot(this.openImagePosition).getId();
        DocumentReference messageRef = FirebaseUtil.getChatroomMessageReference(chatroomId).document(documentId);
        messageRef.update("image", "Opened")
                .addOnSuccessListener(aVoid -> adapter.notifyItemChanged(openImagePosition));
        Log.d("ChatActivity", "Stop the model and delete the message: " + documentId);

        // Stop the camera
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }

        imageViewOnce = false; // reset
        deviceDetectionStarted = false; // reset for next time
    }


}
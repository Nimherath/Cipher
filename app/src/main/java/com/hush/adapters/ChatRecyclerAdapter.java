package com.hush.adapters;

import static com.hush.utils.ImageUtil.getBitmapImage;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.firebase.ui.firestore.FirestoreRecyclerAdapter;
import com.firebase.ui.firestore.FirestoreRecyclerOptions;
import com.hush.Models.ChatMessageModel;
import com.hush.R;
import com.hush.utils.FirebaseUtil;

import java.util.HashSet;
import java.util.Set;

public class ChatRecyclerAdapter extends FirestoreRecyclerAdapter<ChatMessageModel, ChatRecyclerAdapter.ChatModelViewHolder> {

    Context context;
    private Dialog imageDialog;
    private boolean isImagePopupOpen = false;

    // Maintain a set of selected positions (or you could store document ids)
    private Set<Integer> selectedPositions = new HashSet<>();
    // Callback to notify activity/fragment about selection changes
    private OnSelectionChangedListener selectionChangedListener;
    private OnImageOpenListener imageOpenListener;

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    public interface OnImageOpenListener {
        void onImageOpen(int position, boolean viewOnce, Dialog dialog);

        void onImageClose();
    }

    public void setViewOnceImageOpenListener(OnImageOpenListener listener){
        this.imageOpenListener = listener;
    }

    public ChatRecyclerAdapter(@NonNull FirestoreRecyclerOptions<ChatMessageModel> options, Context context) {
        super(options);
        this.context = context;
    }

    public void setIsImagePopupOpen(boolean val){
        isImagePopupOpen = val;
    }

    @Override
    protected void onBindViewHolder(@NonNull ChatModelViewHolder holder, int position, @NonNull ChatMessageModel model) {
        // If the current user deleted this message, do not bind it (or hide it)
        if (FirebaseUtil.currentUserId().equals(model.getDeletedByUserId())) {
            // Hide the item
            holder.itemView.setVisibility(View.GONE);
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            params.height = 0;
            holder.itemView.setLayoutParams(params);
            return;
        } else {
            // Ensure the view is visible for non-deleted messages
            holder.itemView.setVisibility(View.VISIBLE);
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            params.height = RecyclerView.LayoutParams.WRAP_CONTENT;
            holder.itemView.setLayoutParams(params);

            // Handle long press to start selection mode
            holder.itemView.setOnLongClickListener(v -> {
                Log.d("Adapter", "Message position: "+ position);
                Log.d("Adapter", "Message adapter position: "+ holder.getBindingAdapterPosition());
                Log.d("Adapter", getSnapshots().getSnapshot(position).getId());
                toggleSelection(position, holder.getBindingAdapterPosition());
                return true;
            });

            // Handle regular click: if any item is selected, toggle selection
            holder.itemView.setOnClickListener(v -> {
                if(!selectedPositions.isEmpty()){
                    toggleSelection(position, holder.getBindingAdapterPosition());
                } else if(model.getImage() != null && !isImagePopupOpen && !model.getImage().equals("Opened")){
                    if(!(model.getSenderId().equals(FirebaseUtil.currentUserId()) && model.isOneTime())){
                        openImagePopup(getBitmapImage(model.getImage()), model.isOneTime(), position);
                    }
                }
            });
        }

        // Determine if current message is selected
        boolean isSelected = selectedPositions.contains(position);

        // Set background based on selection state (customize colors as needed)
        if(isSelected){
            holder.itemView.setBackgroundColor(context.getResources().getColor(R.color.selected_item));
        } else {
            holder.itemView.setBackgroundColor(context.getResources().getColor(android.R.color.transparent));
        }


        String message = model.getMessage();
        String formattedTime = android.text.format.DateFormat.format("hh:mm a", model.getTimestamp().toDate()).toString();

        if (model.getSenderId().equals(FirebaseUtil.currentUserId())) {
            holder.leftChatLayout.setVisibility(View.GONE);
            holder.rightChatLayout.setVisibility(View.VISIBLE);
            configureImage(holder.rightChatLayout, holder.rightChatImage, model.getImage(), model, holder.rightOnetimeImageLayout, holder.rightViewOnceTxt);
            setTextMessage(holder.rightChatTextview, message);
            holder.rightChatTime.setText(formattedTime);
        } else {
            holder.rightChatLayout.setVisibility(View.GONE);
            holder.leftChatLayout.setVisibility(View.VISIBLE);
            configureImage(holder.leftChatLayout, holder.leftChatImage, model.getImage(), model, holder.leftOnetimeImageLayout, holder.leftViewOnceTxt);
            setTextMessage(holder.leftChatTextview, message);
            holder.leftChatTime.setText(formattedTime);
        }
    }

    private void setTextMessage(TextView textView, String message) {
        if (message != null) {
            textView.setVisibility(View.VISIBLE);
            textView.setText(message);
        } else {
            textView.setVisibility(View.GONE);
        }
    }

    private void configureImage(LinearLayout layout, ImageView imageView, String imageString, ChatMessageModel model, LinearLayout viewOnceLayout, TextView viewOnceTxt) {
        if (imageString != null) {
            if(model.isOneTime()){
                imageView.setVisibility(View.GONE);
                viewOnceLayout.setVisibility(View.VISIBLE);
                layout.getLayoutParams().width = LinearLayout.LayoutParams.WRAP_CONTENT;
                if(imageString.equals("Opened")){
                    viewOnceTxt.setText("Opened");
                }else{
                    viewOnceTxt.setText("Photo");
                }
            }else{
                viewOnceLayout.setVisibility(View.GONE);
                imageView.setVisibility(View.VISIBLE);
                Bitmap image = getBitmapImage(imageString);
                imageView.setImageBitmap(image);
                layout.getLayoutParams().width = LinearLayout.LayoutParams.MATCH_PARENT;
            }
        } else {
            viewOnceLayout.setVisibility(View.GONE);
            imageView.setVisibility(View.GONE);
            layout.getLayoutParams().width = LinearLayout.LayoutParams.WRAP_CONTENT;
        }
    }

    private void openImagePopup(Bitmap image, boolean viewOnce, int position) {
        // Ensure context is an instance of Activity
        if (!(context instanceof Activity)) {
            return; // Prevent crash if context is not valid
        }

        Activity activity = (Activity) context;

        // Ensure the activity is not finishing or destroyed before showing dialog
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        Dialog dialog = new Dialog(activity);
        dialog.setContentView(R.layout.dialog_image_viewer);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        ImageView imageView = dialog.findViewById(R.id.imageViewFull);
        imageView.setImageBitmap(image);

        ImageView closeButton = dialog.findViewById(R.id.btnClose);
        closeButton.setOnClickListener(v ->{
            dialog.dismiss();
            isImagePopupOpen = false;
            imageOpenListener.onImageClose();
        });

        imageOpenListener.onImageOpen(position, viewOnce, dialog);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    Log.d("Dialog", "Back pressed in dialog");
                    isImagePopupOpen = false;
                    imageOpenListener.onImageClose();
                    dialog.dismiss();
                    return true;
                }
                return false;
            }
        });

        dialog.show();
        isImagePopupOpen = true;
    }


    private void toggleSelection(int position, int adapterPosition) {
        if(selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        notifyItemChanged(position);

        if(selectionChangedListener != null){
            selectionChangedListener.onSelectionChanged(selectedPositions.size());
        }
    }

    // Public method to clear selections (after deletion or cancel action)
    public void clearSelections(){
        selectedPositions.clear();
        notifyDataSetChanged();
        if(selectionChangedListener != null){
            selectionChangedListener.onSelectionChanged(0);
        }
    }

    // Getter to retrieve selected items positions
    public Set<Integer> getSelectedPositions(){
        return selectedPositions;
    }

    @NonNull
    @Override
    public ChatModelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.chat_message_recycler_row,parent,false);
        return new ChatModelViewHolder(view);
    }

    class ChatModelViewHolder extends RecyclerView.ViewHolder{

        LinearLayout leftChatLayout,rightChatLayout, leftOnetimeImageLayout, rightOnetimeImageLayout;
        TextView leftChatTextview,rightChatTextview, leftViewOnceTxt, rightViewOnceTxt;
        ImageView leftChatImage, rightChatImage;
        TextView leftChatTime, rightChatTime;

        public ChatModelViewHolder(@NonNull View itemView) {
            super(itemView);

            leftChatLayout = itemView.findViewById(R.id.left_chat_layout);
            rightChatLayout = itemView.findViewById(R.id.right_chat_layout);
            leftChatTextview = itemView.findViewById(R.id.left_chat_textview);
            rightChatTextview = itemView.findViewById(R.id.right_chat_textview);
            leftChatImage = itemView.findViewById(R.id.left_chat_message_image);
            rightChatImage = itemView.findViewById(R.id.right_chat_message_image);
            leftOnetimeImageLayout = itemView.findViewById(R.id.view_once_layout_left);
            rightOnetimeImageLayout = itemView.findViewById(R.id.view_once_layout_right);
            leftViewOnceTxt = itemView.findViewById(R.id.view_once_txt_left);
            rightViewOnceTxt = itemView.findViewById(R.id.view_once_txt_right);
            leftChatTime = itemView.findViewById(R.id.left_chat_time);
            rightChatTime = itemView.findViewById(R.id.right_chat_time);

        }
    }
}

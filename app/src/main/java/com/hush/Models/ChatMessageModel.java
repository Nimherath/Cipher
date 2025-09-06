package com.hush.Models;

import com.google.firebase.Timestamp;

public class ChatMessageModel {
    private String message;
    private String senderId;
    private Timestamp timestamp;
    private String deletedByUserId;
    private String image;
    private boolean oneTime;

    public ChatMessageModel() {
    }

    public ChatMessageModel(String message, String senderId, Timestamp timestamp, String deletedByUserId, String image, boolean isOneTime) {
        this.message = message;
        this.senderId = senderId;
        this.timestamp = timestamp;
        this.deletedByUserId = deletedByUserId;
        this.image = image;
        this.oneTime = isOneTime;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getImage(){
        return this.image;
    }

    public void setImage(String image){
        this.image = image;
    }

    public boolean isOneTime(){
        return this.oneTime;
    }

    public void setOneTime(boolean isOneTime){
        this.oneTime = isOneTime;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Timestamp timestamp) {
        this.timestamp = timestamp;
    }

    public String getDeletedByUserId() {
        return deletedByUserId;
    }
    public void setDeletedByUserId(String deletedByUserId) {
        this.deletedByUserId = deletedByUserId;
    }
}

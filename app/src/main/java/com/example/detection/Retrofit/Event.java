package com.example.detection.Retrofit;

import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

import java.util.List;


public class Event {
    @SerializedName("EventHeader")
    private EventHeader mEventHeader;

    @SerializedName("EventBodies")
    @Nullable
    private List<EventBody> mEventBodies;


    public static class EventHeader {
        @SerializedName("UserId")
        private String mUserId;

        @SerializedName("CameraId")
        private int mCameraId;

        @SerializedName("Created")
        private String mCreated;

        @SerializedName("Path")
        private String mPath;

        @SerializedName("IsRequiredObjectDetection")
        private boolean mIsRequiredObjectDetection;

        public void setUserId(String userId) {
            mUserId = userId;
        }

        public void setCameraId(int cameraId) {
            mCameraId = cameraId;
        }

        public void setCreated(String created) {
            mCreated = created;
        }

        public void setPath(String path) {
            mPath = "data:image/jpeg;base64," + path;
        }

        public void setIsRequiredObjectDetection(boolean isRequiredObjectDetection) {
            mIsRequiredObjectDetection = isRequiredObjectDetection;
        }
    }

    public static class EventBody {
        @SerializedName("Left")
        private final int mLeft;

        @SerializedName("Top")
        private final int mTop;

        @SerializedName("Right")
        private final int mRight;

        @SerializedName("Bottom")
        private final int mBottom;

        @SerializedName("Label")
        private final String mLabel;

        public EventBody(int left, int top, int right, int bottom, String label) {
            mLeft = left;
            mTop = top;
            mRight = right;
            mBottom = bottom;
            mLabel = label;
        }
    }

    public void setEventHeader(EventHeader eventHeader) {
        mEventHeader = eventHeader;
    }

    public void setEventBodies(List<EventBody> eventBodies) {
        mEventBodies = eventBodies;
    }
}

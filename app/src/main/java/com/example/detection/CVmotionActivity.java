package com.example.detection;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.detection.Bluetooth.BluetoothConnect;
import com.example.detection.DB.ID;
import com.example.detection.DB.RoomDB;
import com.example.detection.Retrofit.Event;
import com.example.detection.Retrofit.EventService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class CVmotionActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {
    private CameraBridgeViewBase mOpenCvCameraView;
    private Mat img_A;
    private Mat img_B;
    private Mat output;
    private boolean isFirst = true;

    //시간 측정
    private DataProcess dataProcess;
    private long timeCount_1 = 0L;
    private long motionCount = 0;

    private BluetoothConnect bluetoothConnect;
    private Async async;
    private ID id;
    private EventService service;
    private final ArrayList<Integer> sendToVideo = new ArrayList<>();
    private boolean isBooleanMotion = true;
    private boolean isDetect = false;

    private final BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            if (status == LoaderCallbackInterface.SUCCESS) {
                mOpenCvCameraView.enableView();
            } else {
                super.onManagerConnected(status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cvmotion);

        id = RoomDB.getInstance(this).userDAO().getAll().get(0);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mOpenCvCameraView = findViewById(R.id.activity_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        mOpenCvCameraView.setCameraIndex(1); //front == 1, back == 0

        checkCvPermissionCheck();

        setRetrofit();

        async = new Async(this, this);

        startBluetooth();

        dataProcess = new DataProcess();

        //서버로 부터 제어 정보 수신 및 모터 제어 정보 수신, WebRTC 신호 정보 수신
        async.receiveMQTT(MqttClass.TOPIC_MOTOR, MqttClass.TOPIC_WEBRTC, MqttClass.TOPIC_WEBRTC_FIN);
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {

        if (isFirst) {
            img_A = inputFrame.gray();
            img_B = inputFrame.gray();
            isFirst = false;
        }
        Mat img_C = inputFrame.gray();
        output = inputFrame.rgba();

        //썸넬 전송
        async.sendMqtt(MqttClass.TOPIC_PREVIEW, dataProcess.matToBitmap(output, 5), null, 0.5f);

        Core.flip(output, output, 1);

        Mat diff1 = new Mat();
        Mat diff2 = new Mat();
        Core.absdiff(img_A, img_B, diff1);
        Core.absdiff(img_B, img_C, diff2);

        Mat diff1Thresh = new Mat();
        Mat diff2Thresh = new Mat();
        int thresholdMove = 50;
        Imgproc.threshold(diff1, diff1Thresh, thresholdMove, 255, Imgproc.THRESH_BINARY);
        Imgproc.threshold(diff2, diff2Thresh, thresholdMove, 255, Imgproc.THRESH_BINARY);

        Mat diff = new Mat();
        Core.bitwise_and(diff1Thresh, diff2Thresh, diff);

        int diffCount = Core.countNonZero(diff);

        int diffCompare = 10000;
        if (diffCount > diffCompare) {
            Mat nonZero = new Mat();
            Core.findNonZero(diff, nonZero);
            Rect rect = Imgproc.boundingRect(nonZero);
            //좌우 반전이라 좌표도 반전
            Imgproc.rectangle(output, new Point(output.cols() - rect.x, rect.y), new Point(output.cols() - rect.x - rect.width, rect.y + rect.height),
                    new Scalar(0, 255, 0), 7);
            Imgproc.putText(output, dataProcess.saveTime(), new Point(10, 50), Imgproc.FONT_HERSHEY_DUPLEX
                    , 2, new Scalar(0, 0, 255));
            nonZero.release();

            //시간 측정 및 갯수 세기
            timeCount_1 = SystemClock.elapsedRealtime();
            motionCount++;
            isDetect = true;
        }

        //시간 비교
        Long timeCount_2 = SystemClock.elapsedRealtime();
        //만약 30초간 객체가 감지되지 않았다면 count 를 0으로 수정한다.
        if (dataProcess.diffTime(timeCount_1, timeCount_2, 30)) {
            motionCount = 0;
        }

        //count 가 5 이상이면 전송을 해당 사진을 전송한다.
        if (motionCount >= 5 && isDetect) {
            sendMotion();
            isDetect = false;
        }

        //사진의 갯수가 10개가 넘어가면 서버에게 사진 -> 영상으로 변환하라 알림.
        if (sendToVideo.size() >= 10 && isBooleanMotion) {
            sendMotionMakeVideo();
            isBooleanMotion = false;
        } else if (sendToVideo.size() < 10 && !isBooleanMotion) {
            isBooleanMotion = true;
        }

        img_A.release();
        img_A = img_B.clone();
        img_B.release();
        img_B = img_C.clone();
        img_C.release();
        diff1.release();
        diff2.release();
        diff1Thresh.release();
        diff2Thresh.release();
        diff.release();
        return output;
    }

    public void setRetrofit() {
        Retrofit retrofit = new Retrofit.Builder().baseUrl("https://" + MqttClass.SERVER_ADDRESS + ":8097/")
                .addConverterFactory(GsonConverterFactory.create()).build();

        service = retrofit.create(EventService.class);
    }

    //http 전송
    public void sendMotion() {
        if (id != null) {
            Event event = new Event();
            Event.EventHeader header = new Event.EventHeader();
            header.setUserId(id.getUserId());
            header.setCameraId(Integer.parseInt(id.getCameraId()));
            header.setCreated(dataProcess.saveTime());
            header.setPath(dataProcess.bitmapToString(dataProcess.matToBitmap(output, 4)));
            header.setIsRequiredObjectDetection(false);

            event.setEventHeader(header);

            //post
            Call<String> call = service.sendEvent(event);
            call.enqueue(new Callback<String>() {
                @Override
                public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                    //db 에 저장된 사진 id 값들을 저장한다. 이후 영상으로 변환할때 id값 들을 전달하여 서버에서 사진을 영상으로 변환하게 한다.
                    if (response.body() != null) {
                        sendToVideo.add(Integer.parseInt(response.body()));
                        Log.d("response",response.body());
                    }
                }

                @Override
                public void onFailure(@NonNull Call<String> call, @NonNull Throwable t) {
                }
            });
        }
    }

    //서버에서 사진들을 모아 영상으로 변환하라 요청한다.
    public void sendMotionMakeVideo() {
        JSONObject jsonObject = new JSONObject();
        JSONArray eventIds = new JSONArray();

        for (int i : sendToVideo) {
            eventIds.put(i);
        }
        sendToVideo.clear();
        try {
            jsonObject.put("EventHeaderIds", eventIds);
            async.sendMqtt(MqttClass.TOPIC_MAKE_VIDEO, null, jsonObject, 0);

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void startBluetooth() {
        //블루투스 연결 객체 생성
        bluetoothConnect = new BluetoothConnect(this);
        try {
            //블루투스 연결
            bluetoothConnect.bluetoothConnect();
            //블루투스 클래스 mqtt 클래스에 전송
            async.setBluetoothConnect(bluetoothConnect);

        } catch (IllegalArgumentException e) {
            //블루투스 모터제어를 안하는 경우
            e.printStackTrace();
        }
    }

    public void checkCvPermissionCheck() {
        List<? extends CameraBridgeViewBase> cameraViews = Collections.singletonList(mOpenCvCameraView);

        for (CameraBridgeViewBase cameraBridgeViewBase : cameraViews) {
            cameraBridgeViewBase.setCameraPermissionGranted();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, mLoaderCallback);
        } else {
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    @Override
    protected void onDestroy() {

        //만약 앱이 종료된다면, 현재 남아있는 사진 list 를 영상으로 만들라하고 종료한다.
        if (sendToVideo != null && sendToVideo.size() > 0) {
            sendMotionMakeVideo();
        }

        super.onDestroy();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
        async.close();
        bluetoothConnect.close();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
    }

    @Override
    public void onCameraViewStopped() {
        output.release();
    }

}
package com.example.detection;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.example.detection.Bluetooth.BluetoothConnect;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

//카메라가 실행되는 액티비티
public class CameraActivity extends AppCompatActivity {
    private PreviewView previewView;   //미리보기 화면 클래스
    private RectView rectView;  //RectF 객체가 그려지는 View 클래스
    private ProcessCameraProvider processCameraProvider; //카메라 제공 클래스
    private ProcessOnnx processOnnx; //Onnx 처리 클래스
    private OrtEnvironment ortEnvironment;
    private OrtSession session;
    private BluetoothConnect bluetoothConnect; //블루투스 연결 클래스
    private Async async;   //비동기 처리 클래스
    private int fireCount = 0; //불이 났다고 생각하는 횟수를 측정
    private DataProcess dataProcess; //서버로 전송을 위한 정보 처리 클래스
    private boolean booleanFire = false; //불이 난 시간 차를 측정하기위해 쓰이는 불리안
    private Long timeCount_1 = 0L;  //시간 비교
    private Long timeCount_2 = 0L;  //시간 비교
    static public float scale = 1f; //미리보기 사진의 크기를 수정할 수 있다.
    static public float interval_time = 0.5f; //미리보기 사진의 전송 시간차를 수정할 수 있다.


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        previewView = findViewById(R.id.previewView);
        rectView = findViewById(R.id.rectView);

        //자동꺼짐 해제
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //비동기 처리 클래스 (mqtt 전송을 위해)
        async = new Async(this, this);

        //블루투스 실행하기
        startBluetooth();

        //서버로 전송을 위한 정보 처리 클래스
        dataProcess = new DataProcess();

        //Onnx 처리 과정 클래스 켜기
        processOnnx = new ProcessOnnx(this);

        //각종 모델 불러오기
        load();

        //카메라 빌더
        try {
            ListenableFuture<ProcessCameraProvider> cameraProviderListenableFuture = ProcessCameraProvider.getInstance(this);
            processCameraProvider = cameraProviderListenableFuture.get();
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }

        //서버로 부터 제어 정보 수신 및 모터 제어 정보 수신, WebRTC 신호 정보 수신
        async.receiveMQTT(MqttClass.TOPIC_CONTROL, MqttClass.TOPIC_MOTOR, MqttClass.TOPIC_WEBRTC,MqttClass.TOPIC_WEBRTC_FIN);

        //카메라 켜기
        startCamera();
    }

    //블루투스 연결하기
    public void startBluetooth() {
        //블루투스 연결 객체 생성
        bluetoothConnect = new BluetoothConnect(this);
        try {
            //블루투스 연결
            bluetoothConnect.bluetoothConnect();
            //블루투스 클래스 mqtt 클래스에 전송
            async.setBluetoothConnect(bluetoothConnect);

        }catch (IllegalArgumentException e){
            //블루투스 모터제어를 안하는 경우
            e.printStackTrace();
        }
    }


    //카메라 켜기
    public void startCamera() {
        //화면을 가운데를 기준으로 전체 화면으로 보여주기
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        //카메라 렌즈 중 자기가 고를 렌즈 선택
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        //16:9의 비율로 화면 보기
        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build();

        //preview 에서 받아와서 previewView 에 그리기
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        //이미지 분석
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                //분석에 들어갈 사진 또한 16:9의 비율
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                //분석 중이면 그 다음 화면이 대기중인 것이 아니라 계속 받아오는 화면으로 새로고침 함. 분석이 끝나면 그 최신 사진을 다시 분석
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();


        //그림을 그릴 rectView 클래스에 label 정보 (화재, 연기) 배열을 전달한다.
        rectView.setClasses(processOnnx.classes);

        imageAnalysis.setAnalyzer(Executors.newCachedThreadPool(), imageProxy -> {
            //이미지 처리 메소드
            imageProcessing(imageProxy);
            imageProxy.close();
        });
        //생명주기는 이 클래스에 귀속
        processCameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    @SuppressLint("UnsafeOptInUsageError")
    public void imageProcessing(ImageProxy imageProxy) {
        //시간측정하기
        long start = System.currentTimeMillis();
        //이미지 받아오기
        Image image = imageProxy.getImage();

        if (image != null) {
            //이미지를 비트맵으로 변환
            Bitmap bitmap = processOnnx.imageToBitmap(image);
            //서버로 전송할 이미지는 크기를 줄여야한다. 클라이언트가 원한다면 scale 을 키울 수 있다.
            Bitmap serverBitmap = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale / 3), Math.round(bitmap.getHeight() * scale / 3), true);
            //이미지 서버로 전송 (0.5초마다) 현재 크기 너비 : 높이 = 426 : 240
            async.sendMqtt(MqttClass.TOPIC_PREVIEW, serverBitmap, null, interval_time);
            //비트맵 크기를 수정한다(640x640).
            Bitmap bitmap_640 = processOnnx.rescaleBitmap(bitmap);
            //비트맵을 다시 FloatBuffer 형식으로 변환한다. (rgb 값이 나열되어있는 것)
            FloatBuffer imgDataFloat = processOnnx.bitmapToFloatBuffer(bitmap_640);
            //OrtSession 의 이름
            String inputName = session.getInputNames().iterator().next();
            //모델의 요구 입력값 배열 설정 [1,3,640,640] 모델마다 상이할수 있음.
            long[] shape = {ProcessOnnx.BATCH_SIZE, ProcessOnnx.PIXEL_SIZE, ProcessOnnx.INPUT_SIZE, ProcessOnnx.INPUT_SIZE};
            try {
                //이미지 데이터를 입력 텐서로 변환
                OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnvironment, imgDataFloat, shape);
                //추론
                OrtSession.Result result = session.run(Collections.singletonMap(inputName, inputTensor));
                //yolo v5 모델의 출력 크기는 [1][25200][5+alpha] 이다.
                //차례대로 첫번째는 입력 사진의 갯수 두번째는 연산한 결과의 갯수이다.
                //마지막 세번째 배열은 좌표값을 의미하는 값 4개 + 정확도 1개 + alpha(label 속 데이터의 갯수)이다. 화재 모델의 경우 fire, smoke 로 두개이다.
                float[][][] output = (float[][][]) result.get(0).getValue();
                //세번째 배열을 부가설명하자면, 0~3은 좌표값 4는 확률 값 5~는 학습된 데이터의 갯수이다. 즉 따라서 고정되는 5를 빼면 데이터의 개수를 알 수 있다.

                //yolo v8 모델의 출력 크기는 [1][6][8400]이다.
                //v5와 달리 좌표값 4개 + alpha (label 속 데이터의 갯수)이다. 이제 정확도(confidence)가 사라지고
                // 해당 label 의 배열안에서 최대 값을 구하면 된다.

                //결과값 가져오기
                int rows;
                ArrayList<Result> results;
                if(processOnnx.labelName.equals("label_fire.txt")) {
                    // v5 : 연산하는 총량으로 (((640/32)^2 + (640/16)^2 + (640/8)^2)*3) 이다.
                    rows = output[0].length;
                    results = processOnnx.outputsToNMSPredictions(output, rows);
                }else{
                    // v8 : 연산하는 총량으로 8400이다.
                    rows = output[0][0].length;
                    results = processOnnx.outputsToNMSPredictionsV8(output, rows);
                }

                //rectF 의 크기를 화면의 비율에 맞게 수정한다.
                results = rectView.transFormRect(results);

                //화면에 출력하게 결과값을 rectView 에 전달한다.
                rectView.clear();
                rectView.resultToList(results);
                rectView.invalidate();

                //만약 유의미한 결과가 나왔다면시간을 측정한다. 그리고 fireCount 를 증가한다.
                if (results.size() > 0) {
                    if (booleanFire) {
                        timeCount_1 = SystemClock.elapsedRealtime();
                    } else {
                        timeCount_2 = SystemClock.elapsedRealtime();
                    }
                    fireCount++;
                    //만약 5분간 화재가 감지되지 않았다면 count 를 0으로 수정한다.
                    if (dataProcess.diffTime(timeCount_1, timeCount_2, 300)) {
                        fireCount = 0;
                    }
                    booleanFire = !booleanFire;
                }

                //count 가 5 이상이면 전송을 해당 사진을 잘라서 전송한다.
                if (fireCount >= 5) {
                    JSONObject rectJson = new JSONObject();
                    JSONArray rectArray = new JSONArray();

                    //만약 유의미한 result 값이 나온다면 서버에 전체 사진 및 객체의 좌표값을 전송한다.
                    for (Result _result : results) {
                        Rect rect = new Rect();
                        float scaleX = (bitmap.getWidth() / (float) rectView.getWidth());
                        float scaleY = (bitmap.getHeight() / (float) rectView.getHeight());
                        rect.left = (int) (_result.rect.left * scaleX);
                        rect.right = (int) (_result.rect.right * scaleX);
                        rect.top = (int) (_result.rect.top * scaleY);
                        rect.bottom = (int) (_result.rect.bottom * scaleY);
                        // 검출된 정보들을 json 객체로 만들어서 array 의 형태로 변환해서 넣는다.
                        JSONObject infoJson = new JSONObject();
                        infoJson.put("Left",rect.left);
                        infoJson.put("Right",rect.right);
                        infoJson.put("Top",rect.top);
                        infoJson.put("Bottom",rect.bottom);
                        infoJson.put("Label",processOnnx.classes[_result.classIndex]);
                        //rect 객체들과 해당되는 클래스 이름 (화재 or 연기)를  json Array 에 담는다.
                        rectArray.put(infoJson);
                    }
                    //json array 들을 하나의 json object 로 합쳐서 전송한다.
                    rectJson.put("EventDetails", rectArray);

                    //비트맵을 원본 크기로 키워야한다. 현재 너비 : 높이 = 1440 : 3100
                    Bitmap sendBitmap = Bitmap.createScaledBitmap(bitmap, rectView.getWidth(), rectView.getHeight(), true);
                    async.sendMqtt(MqttClass.TOPIC_DETECT, sendBitmap, rectJson, 0);
                    //다시 count 를 0으로 바꾼다.
                    fireCount = 0;
                }
            } catch (OrtException | JSONException e) {
                e.printStackTrace();
            }
        }

        //종료 시간 확인하기
        long end = System.currentTimeMillis();
        Log.d("time : ", Long.toString(end - start));
    }

    //각종 파일 불러오기
    public void load() {
        //asset 모델 파일 (.onnx) 불러오기
        processOnnx.loadModel();
        //데이터 셋 (.txt) 파일 불러오기
        processOnnx.loadDataSet();
        //OnnxRuntime 설정 클래스
        ortEnvironment = OrtEnvironment.getEnvironment();
        try {
            session = ortEnvironment.createSession(this.getFilesDir().getAbsolutePath() + "/" + ProcessOnnx.fileName, new OrtSession.SessionOptions());
        } catch (OrtException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onStop() {
        try {
            session.endProfiling();
        } catch (OrtException e) {
            e.printStackTrace();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        async.close();
        try {
            session.close();
            bluetoothConnect.close();
            ortEnvironment.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }
}


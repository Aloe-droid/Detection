package com.example.detection;

import android.os.Bundle;
import android.view.SurfaceView;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.Collections;
import java.util.List;

public class CVmotionActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    private CameraBridgeViewBase mOpenCvCameraView;
    private Mat img_A;
    private Mat img_B;
    private Mat output;
    private boolean isFirst = true;

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

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mOpenCvCameraView = findViewById(R.id.activity_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
        mOpenCvCameraView.setCameraIndex(1); //front == 1, back == 0

        checkCvPermissionCheck();

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

        Mat kernel = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_CROSS, new Size(3, 3));
        Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_OPEN, kernel);

        int diffCount = Core.countNonZero(diff);


        int diffCompare = 10000;
        if (diffCount > diffCompare) {
            Mat nonZero = new Mat();
            Core.findNonZero(diff, nonZero);
            Rect rect = Imgproc.boundingRect(nonZero);
            //좌우 반전이라 좌표도 반전
            Imgproc.rectangle(output, new Point(output.cols() - rect.x, rect.y), new Point(output.cols() - rect.x - rect.width, rect.y + rect.height),
                    new Scalar(0, 255, 0), 7);
            Imgproc.putText(output, "Motion Detected!", new Point(10, 30), Imgproc.FONT_HERSHEY_DUPLEX
                    , 1, new Scalar(0, 0, 255));
            nonZero.release();
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
        kernel.release();
        return output;
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
        super.onDestroy();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
    }

    @Override
    public void onCameraViewStopped() {
        output.release();
    }

}
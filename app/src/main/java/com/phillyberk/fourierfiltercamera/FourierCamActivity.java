package com.phillyberk.fourierfiltercamera;

import com.phillyberk.fourierfiltercamera.FourierCamSettingsDialog.NoticeDialogListener;
import android.app.Activity;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.contrib.Contrib;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import android.app.DialogFragment;

public class FourierCamActivity extends Activity implements CvCameraViewListener2, OnTouchListener, NoticeDialogListener {
    private static final String TAG = "FourierFilterCam::Act";

    private FourierCamView mOpenCvCameraView;
    private List<Size> mResolutionList;

    private boolean dftFlag = false;
    private Mat zeroPadded;
    private Mat complexI;
    private Mat complexFilter;
    private Mat magImg;
    private Mat returnMat;

    private int dftRows;
    private int dftCols;
    private int imgXSize;
    private int imgYSize;
    private int imgCenterX;
    private int imgCenterY;
    float aspectRatio;

    private List<Mat> complexPlanes;
    private List<Mat> filterPlanes;
    private Mat imgFloat;
    private Mat imgUint;
    private Mat kernel;

    private ImageButton fourierButton;
    private ImageButton inverseFourierButton;
    private Button eraseFilterButton;
    private Button invertFilterButton;
    private Button settingsButton;

    private List<Mat> rgbChannels;
    private int resolutionValue = 6;

    private Spinner filterListSpinner;

    private float mX, mY;
    private String [] mResolutionListString;

    // Parameters to tweak
    private static final float TOUCH_TOLERANCE = 4;
    int pointerSize = 10;
    private int annulusWidth = 20;
    private int blurSize = 10;

    private int defaultTouchPointX = 40;
    private int defaultTouchPointY = 40;

    private Mat touchMask;

    private int filterType = 0;
    private boolean filterInverted = false;

    // Filter Mode constants (same order as array defined in strings.xml)
    private static final int FILTER_MODE_FREEFORM = 0;
    private static final int FILTER_MODE_BOX = 1;
    private static final int FILTER_MODE_HALFBOX = 2;
    private static final int FILTER_MODE_CIRCLE = 3;
    private static final int FILTER_MODE_HALFCIRCLE = 4;
    private static final int FILTER_MODE_ANNULUS = 5;

    private boolean colorFilterFlag = false;
    private boolean isotropicFilterFlag = false;

    private FourierCamSettingsDialog settingsDialog;
    private DialogFragment settingsDialogFragment;

    private boolean firstSwitch = true;

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i(TAG, "OpenCV loaded successfully");
                    mOpenCvCameraView.enableView();
                    mOpenCvCameraView.setOnTouchListener(FourierCamActivity.this);
                }
                break;
                default: {
                    super.onManagerConnected(status);
                }
                break;
            }
        }
    };

    public FourierCamActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.tutorial3_surface_view);

        mOpenCvCameraView = (FourierCamView) findViewById(R.id.fourier_filter_cam_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        settingsDialogFragment = new FourierCamSettingsDialog();

        // Populate resolution list for settings menu

        // Set up buttons
        fourierButton = (ImageButton) findViewById(R.id.fourierTransformButton);
        inverseFourierButton = (ImageButton) findViewById(R.id.inverseFourierTransformButton);
        eraseFilterButton = (Button) findViewById(R.id.clearFilterButton);
        invertFilterButton = (Button) findViewById(R.id.invertFilterButton);
        settingsButton = (Button) findViewById(R.id.settingsButton);
        fourierButton.setVisibility(mOpenCvCameraView.VISIBLE);
        inverseFourierButton.setVisibility(mOpenCvCameraView.INVISIBLE);
        settingsButton = (Button) findViewById(R.id.settingsButton);

        // Set up default filter spinner
        filterListSpinner = (Spinner) findViewById(R.id.filterListSpinner);
        // Create an ArrayAdapter using the string array and a default spinner layout
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.filter_array, android.R.layout.simple_spinner_item);
        // Specify the layout to use when the list of choices appears
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner
        filterListSpinner.setAdapter(adapter);

        filterListSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Log.d("SPINNER", Integer.toString(position));

                if (position <= 5 && position >= 0) {
                    filterType = position;
                    // Draw default fikt
                    if (filterType != FILTER_MODE_FREEFORM)
                        drawFilterType(new Point(imgCenterX + defaultTouchPointX, imgCenterY + defaultTouchPointY));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });


        eraseFilterButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                touchMask = Mat.ones(touchMask.rows(), touchMask.cols(), touchMask.type());
                filterInverted = false;
            }
        });

        settingsButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                openSettingsDialog();
            }
        });

        invertFilterButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                filterInverted = !filterInverted;
                invertFilter();
            }
        });

        fourierButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                dftFlag = true;
                fourierButton.setVisibility(View.INVISIBLE);
                inverseFourierButton.setVisibility(View.VISIBLE);
                filterListSpinner.setVisibility(View.VISIBLE);
                filterListSpinner.setVisibility(View.VISIBLE);
                if (firstSwitch)
                {
                    Toast.makeText(getApplicationContext(), "Draw filters on the screen using your finger", Toast.LENGTH_SHORT).show();
                    firstSwitch = false;
                }
            }
        });

        inverseFourierButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                dftFlag = false;
                fourierButton.setVisibility(View.VISIBLE);
                inverseFourierButton.setVisibility(View.INVISIBLE);
                filterListSpinner.setVisibility(View.GONE);
            }
        });

    }

    @Override
    public void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume() {
        super.onResume();
        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_3, this, mLoaderCallback);
    }

    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    public void onCameraViewStarted(int width, int height) {

        mResolutionList = mOpenCvCameraView.getResolutionList();
        mResolutionListString = new String[mResolutionList.size()];
        filterListSpinner.setVisibility(mOpenCvCameraView.INVISIBLE);

        ListIterator<Size> resolutionItr = mResolutionList.listIterator();
        int idx = 0;
        while (resolutionItr.hasNext()) {
            Size element = resolutionItr.next();
            mResolutionListString[idx] = Integer.valueOf(element.width).toString() + "x" + Integer.valueOf(element.height).toString();
            idx++;
        }
        resolutionValue = idx-2;
        // Set default resolution
        Size resolution = mResolutionList.get(resolutionValue);
        mOpenCvCameraView.setResolution(resolution);
        changeVariableSizes();

        filterListSpinner.setVisibility(View.GONE);

        Toast.makeText(this, "Touch screen to take a picture", Toast.LENGTH_SHORT).show();
    }

    public void onCameraViewStopped() {
    }

    public void changeVariableSizes()
    {
        Size resolution = mOpenCvCameraView.getResolution();

        int height = resolution.height;
        int width = resolution.width;

        pointerSize = height / 30;
        blurSize = pointerSize / 4;

        imgXSize = width;
        imgYSize = height;

        imgCenterX = (int) Math.round(imgXSize / 2.0);
        imgCenterY = (int) Math.round(imgYSize / 2.0);

        dftRows = Core.getOptimalDFTSize(imgYSize);
        dftCols = Core.getOptimalDFTSize(imgXSize); // on the border

        zeroPadded = new Mat(dftCols, dftRows, CvType.CV_32FC1); // expand input

        imgFloat = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC1);
        imgUint = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_8UC1);

        complexI = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC2);
        complexFilter = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC2);

        magImg = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC1);

        imgFloat = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC1);
        imgUint = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_8UC1);

        complexPlanes = new ArrayList<Mat>();
        complexPlanes.add(imgFloat);
        complexPlanes.add(imgFloat);

        filterPlanes = new ArrayList<Mat>();
        filterPlanes.add(imgFloat);
        filterPlanes.add(imgFloat);

        returnMat = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_8UC3);

        rgbChannels = new ArrayList<Mat>();
        rgbChannels.add(imgUint);
        rgbChannels.add(imgUint);
        rgbChannels.add(imgUint);

        touchMask = Mat.ones(height, width, CvType.CV_32FC1);

        aspectRatio = (float)imgXSize / (float)imgYSize;
    }

    public Mat onCameraFrame(CvCameraViewFrame inputFrame)
    {

        if (dftFlag) // Display Fourier Transform of Image
        {
            Contrib.applyColorMap(fftMagnitude(inputFrame.gray()), returnMat, Contrib.COLORMAP_JET);
        } else {
            if (colorFilterFlag)
                returnMat = applyFourierFilter(inputFrame.rgba());
            else
                returnMat = applyFourierFilterGray(inputFrame.gray());
        }

        return returnMat;
    }


    private Mat fftMagnitude(Mat imGray) {
        imGray.convertTo(imGray, CvType.CV_32FC1); // Convert to Floating Point

        Core.dft(imGray, complexI, Core.DFT_COMPLEX_OUTPUT, imGray.rows());
        Core.split(complexI, complexPlanes);
        Core.magnitude(complexPlanes.get(0), complexPlanes.get(1), imgFloat);
        Core.log(imgFloat, imgFloat);
        imgFloat = fftShift(imgFloat);

        Core.normalize(imgFloat, imgFloat, 0, 255, Core.NORM_MINMAX);
        imgFloat = imgFloat.mul(touchMask); // Apply filtering
        imgFloat.convertTo(imgUint, CvType.CV_8UC1);

        // Release Matricies
        complexPlanes.get(0).release();
        complexPlanes.get(1).release();
        imGray.release();
        complexI.release();
        imgFloat.release();

        return imgUint;
    }

    private Mat applyFourierFilter(Mat imRgb) {
        imRgb.convertTo(imRgb, CvType.CV_32FC3); // convert to floating-point

        //ImgProc.copyMakeBorder(imRgb, zeroPadded, 0, imgYSize - imRgb.rows(), 0,
        //        imgXSize - imRgb.cols(), ImgProc.BORDER_CONSTANT);

        //zeroPadded = Mat.zeros(imRgb.rows(),imRgb.cols(),CvType.CV_32FC1);

        Core.split(imRgb, rgbChannels);
        Imgproc.blur(touchMask, imgFloat, new org.opencv.core.Size(blurSize, blurSize));
        Mat touchMaskShifted = fftShift(imgFloat);
        touchMaskShifted.convertTo(touchMaskShifted, CvType.CV_32FC1);

        filterPlanes.set(0, touchMaskShifted);
        filterPlanes.set(1, Mat.zeros(touchMaskShifted.rows(), touchMaskShifted.cols(), touchMaskShifted.type()));
        Core.merge(filterPlanes, complexFilter);

        // Filter all channels
        // TODO - account for differences in spatial frequencies in terms of wavelength
        for (int ch = 0; ch < imRgb.channels(); ch++) {
            // Apply Convolution filter for each channel
            imgFloat = rgbChannels.get(ch);
            Core.dft(imgFloat, complexI, Core.DFT_COMPLEX_OUTPUT, imgFloat.rows());
            Core.mulSpectrums(complexI, complexFilter, complexI, 1);
            Core.idft(complexI, imgFloat, Core.DFT_REAL_OUTPUT, imgFloat.rows());
            rgbChannels.set(ch, imgFloat);
        }

        // Combine Color Channels
        Core.merge(rgbChannels, imRgb);

        // Normalize and scale to uint8
        Core.normalize(imRgb, imRgb, 0, 255, Core.NORM_MINMAX);
        imRgb.convertTo(imRgb, CvType.CV_8UC3);

        // Release Matricies
        complexPlanes.get(0).release();
        complexPlanes.get(1).release();
        filterPlanes.get(0).release();
        filterPlanes.get(1).release();
        complexFilter.release();
        rgbChannels.get(0).release();
        rgbChannels.get(1).release();
        rgbChannels.get(2).release();
        magImg.release();
        complexI.release();

        return imRgb;
    }

    private Mat applyFourierFilterGray(Mat imGray) {
        imGray.convertTo(imGray, CvType.CV_32FC1); // convert to floating-point

        //ImgProc.copyMakeBorder(imRgb, zeroPadded, 0, imgYSize - imRgb.rows(), 0,
        //        imgXSize - imRgb.cols(), ImgProc.BORDER_CONSTANT);

        //zeroPadded = Mat.zeros(imRgb.rows(),imRgb.cols(),CvType.CV_32FC1);

        Imgproc.blur(touchMask,imgFloat,new org.opencv.core.Size(blurSize,blurSize));
        Mat touchMaskShifted = fftShift(imgFloat);
        touchMaskShifted.convertTo(touchMaskShifted, CvType.CV_32FC1);

        filterPlanes.set(0, touchMaskShifted);
        filterPlanes.set(1, Mat.zeros(touchMaskShifted.rows(), touchMaskShifted.cols(), touchMaskShifted.type()));
        Core.merge(filterPlanes, complexFilter);

        Core.dft(imGray, complexI, Core.DFT_COMPLEX_OUTPUT, imgFloat.rows());
        Core.mulSpectrums(complexI, complexFilter, complexI, 1);
        Core.idft(complexI, imGray, Core.DFT_REAL_OUTPUT, imgFloat.rows());

        // Normalize and scale to uint8
        Core.normalize(imGray, imGray, 0, 255, Core.NORM_MINMAX);
        imGray.convertTo(imGray, CvType.CV_8UC1);

        // Release Matricies
        filterPlanes.get(0).release();
        filterPlanes.get(1).release();
        complexFilter.release();
        complexI.release();

        return imGray;
    }


    public static Mat fftShift(Mat inMat) {
        return circularShift(inMat, (int) java.lang.Math.ceil((double) inMat.cols() / 2.0),
                (int) java.lang.Math.ceil((double) inMat.rows() / 2.0));
    }

    /*
    public static Mat ifftShift(Mat inMat) {
        return circularShift(inMat, (int) java.lang.Math.floor((double) inMat.cols() / 2.0),
                (int) java.lang.Math.floor((double) inMat.rows() / 2.0));
    }
    */


    public static Mat circularShift(Mat mat, int x, int y) {
        int w = mat.cols();
        int h = mat.rows();
        Mat result = Mat.zeros(h, w, mat.type());

        int shiftR = x % w;
        int shiftD = y % h;
        //java modulus gives negative results for negative numbers
        if (shiftR < 0)
            shiftR += w;
        if (shiftD < 0)
            shiftD += h;

        /* extract 4 submatrices
                      |---| shiftR
             ______________
            |         |   |
            |    1    | 2 |
            |_________|___|  ___ shiftD
            |         |   |   |
            |    3    | 4 |   |
            |         |   |   |
            |_________|___|  _|_
         */
        Mat shift1 = mat.submat(0, h - shiftD, 0, w - shiftR);
        Mat shift2 = mat.submat(0, h - shiftD, w - shiftR, w);
        Mat shift3 = mat.submat(h - shiftD, h, 0, w - shiftR);
        Mat shift4 = mat.submat(h - shiftD, h, w - shiftR, w);

        /* and rearrange
             ______________
            |   |         |
            | 4 |    3    |
            |   |         |
            |___|_________|
            |   |         |
            | 2 |    1    |
            |___|_________|
         */

        shift1.copyTo(new Mat(result, new Rect(shiftR, shiftD, w - shiftR, h - shiftD)));
        shift2.copyTo(new Mat(result, new Rect(0, shiftD, shiftR, h - shiftD)));
        shift3.copyTo(new Mat(result, new Rect(shiftR, 0, w - shiftR, shiftD)));
        shift4.copyTo(new Mat(result, new Rect(0, 0, shiftR, shiftD)));

        shift1.release();
        shift2.release();
        shift3.release();
        shift4.release();

        return result;
    }

    private void invertFilter() {
        Core.subtract(Mat.ones(touchMask.rows(), touchMask.cols(), touchMask.type()), touchMask, touchMask);
    }

    private void drawFilterType(Point touchPoint) {
        switch (filterType) {
            case FILTER_MODE_FREEFORM: {
                // Draw a circle at pointer
                if (filterInverted)
                    Core.circle(touchMask, touchPoint, pointerSize, new Scalar(1), -1);
                else
                    Core.circle(touchMask, touchPoint, pointerSize, new Scalar(0), -1);
                break;
            }
            case FILTER_MODE_BOX: {
                touchMask = Mat.ones(touchMask.rows(), touchMask.cols(), CvType.CV_32FC1);
                // Calculate x and y of corner
                float rx = Math.abs((float) touchPoint.x - (float) imgCenterX);
                float ry = Math.abs((float) touchPoint.y - (float) imgCenterY);

                Point corner1 = new Point(imgCenterX + rx, imgCenterY + ry);
                Point corner2 = new Point(imgCenterX - rx, imgCenterY - ry);
                Core.rectangle(touchMask, corner1, corner2, new Scalar(0), -1);

                break;
            }
            case FILTER_MODE_HALFBOX: {
                touchMask = Mat.ones(touchMask.rows(), touchMask.cols(), CvType.CV_32FC1);
                // Calculate x and y of corner
                float rx = Math.abs((float) touchPoint.x - (float) imgCenterX);
                float ry = Math.abs((float) touchPoint.y - (float) imgCenterY);

                Point corner1 = new Point(imgCenterX + rx, imgCenterY + ry);
                Point corner2 = new Point(imgCenterX, imgCenterY - ry);
                Core.rectangle(touchMask, corner1, corner2, new Scalar(0), -1);

                break;
            }
            case FILTER_MODE_CIRCLE: {
                touchMask = Mat.ones(touchMask.rows(), touchMask.cols(), CvType.CV_32FC1);
                float rx = Math.abs((float) touchPoint.x - (float) imgCenterX);
                float ry = Math.abs((float) touchPoint.y - (float) imgCenterY);

                double radius = Math.sqrt(rx * rx + ry * ry);
                Core.circle(touchMask, new Point(imgCenterX, imgCenterY), (int) Math.round(radius), new Scalar(0), -1);

                break;
            }
            case FILTER_MODE_HALFCIRCLE: {
                touchMask = Mat.ones(touchMask.rows(), touchMask.cols(), CvType.CV_32FC1);
                float rx = Math.abs((float) touchPoint.x - (float) imgCenterX);
                float ry = Math.abs((float) touchPoint.y - (float) imgCenterY);

                double radius = Math.max(Math.sqrt(rx * rx + ry * ry), 0);
                Core.circle(touchMask, new Point(imgCenterX, imgCenterY), (int) Math.round(radius), new Scalar(0), -1);

                // Clear half of the circle
                Point corner1 = new Point(imgCenterX, 0);
                Point corner2 = new Point(imgXSize, imgYSize);
                Core.rectangle(touchMask, corner1, corner2, new Scalar(1), -1);

                break;
            }
            case FILTER_MODE_ANNULUS: {
                touchMask = Mat.ones(touchMask.rows(), touchMask.cols(), CvType.CV_32FC1);
                float rx = Math.abs((float) touchPoint.x - (float) imgCenterX);
                float ry = Math.abs((float) touchPoint.y - (float) imgCenterY);

                double radiusOut = Math.max(Math.sqrt(rx * rx + ry * ry), 0);
                double radiusIn = radiusOut - annulusWidth;

               // if (useRealFourierCoverage) {
               //     Core.el
               // }else{
                    Core.circle(touchMask, new Point(imgCenterX, imgCenterY), (int) Math.round(radiusOut), new Scalar(0), -1);
                    Core.circle(touchMask, new Point(imgCenterX, imgCenterY), (int) Math.round(radiusIn), new Scalar(1), -1);
                //}

                break;
            }
            default:
                break;


        }

        // Apply Windowing Function
        if (filterType != FILTER_MODE_FREEFORM) {
            //Imgproc.filter2D(touchMask, touchMask, CvType.CV_32F, kernel);
            if (filterInverted)
                invertFilter();
        }
    }

    //@SuppressLint("SimpleDateFormat")
    @Override
    public boolean onTouch(View v, MotionEvent event) {

        int pointX = (int) (event.getX() * ((float) touchMask.cols() / (float) v.getWidth()));
        int pointY = (int) (event.getY() * ((float) touchMask.rows() / (float) v.getHeight()));
        Point touchPoint = new Point(pointX, pointY);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (dftFlag) {
                    mX = pointX;
                    mY = pointY;
                    drawFilterType(touchPoint);
                    //Core.circle(touchMask, touchPoint, pointerSize, new Scalar(0), -1);
                } else
                    takePicture();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dftFlag) {
                    //drawFilterType(touchPoint);
                    float dx = Math.abs(pointX - mX);
                    float dy = Math.abs(pointY - mY);
                    if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                        mX = pointX;
                        mY = pointY;
                        drawFilterType(touchPoint);
                    }
                    return true;
                } else
                    break;

            }
            case MotionEvent.ACTION_UP: {
                return false;
            }
            default:
                return false;
        }
        return true;
    }

    // Code to take a picture and save to SD
    void takePicture() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        String currentDateandTime = sdf.format(new Date());
        String fileName = Environment.getExternalStorageDirectory().getPath() +
                "/sample_picture_" + currentDateandTime + ".jpg";
        mOpenCvCameraView.takePicture(fileName);
        Toast.makeText(this, "Saved picture as " + fileName, Toast.LENGTH_SHORT).show();
    }

    // Get/Set methods for settings dialog
    void setResolutionIdx(int idx) {
        resolutionValue = idx;
        Size resolution = mResolutionList.get(resolutionValue);
        mOpenCvCameraView.setResolution(resolution);
        changeVariableSizes();
    }

    void setIsotropicFilterFlag(boolean flag) {
        isotropicFilterFlag = flag;
    }

    void setColorFilterFlag(boolean flag) {
        colorFilterFlag = flag;
    }

    boolean getIsotropicFilterFlag() {
        return isotropicFilterFlag;
    }

    boolean getColorFilterFlag() {
        return colorFilterFlag;
    }

    String[] getResolutionListString()
    {
        return mResolutionListString;
    }

    public void openSettingsDialog()
    {
        settingsDialogFragment.show(getFragmentManager(), "acquireSettings");
    }

    public int getResolutionId()
    {
        return resolutionValue;
    }

    @Override
    public void onDialogPositiveClick(DialogFragment dialog) {

    }

    @Override
    public void onDialogNegativeClick(DialogFragment dialog) {

    }

}

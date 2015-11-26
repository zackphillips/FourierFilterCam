package fourierfiltercam.phillyberk.com.fourierfiltercamera;

import android.app.Activity;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
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

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

public class FourierCamActivity extends Activity implements CvCameraViewListener2, OnTouchListener {
    private static final String TAG = "FourierFilterCam::Act";

    private FourierCamView mOpenCvCameraView;
    private List<Size> mResolutionList;
    private MenuItem[] mEffectMenuItems;
    private SubMenu mColorEffectsMenu;
    private MenuItem[] mResolutionMenuItems;
    private SubMenu mResolutionMenu;
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
    private List<Mat> complexPlanes;
    private List<Mat> filterPlanes;
    private Mat imgFloat;
    private Mat imgUint;

    private ImageButton fourierButton;
    private ImageButton inverseFourierButton;
    private Button eraseFilterButton;
    private Button invertFilterButton;

    private List<Mat> rgbChannels;
    private List<Mat> rgbaChannels;
    private int resolutionValue = 5;

    private float mX, mY;
    private static final float TOUCH_TOLERANCE = 4;
    int pointerSize = 10;

    private Mat touchMask;


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

        // Set up buttons
        fourierButton = (ImageButton) findViewById(R.id.fourierTransformButton);
        inverseFourierButton = (ImageButton) findViewById(R.id.inverseFourierTransformButton);
        eraseFilterButton = (Button) findViewById(R.id.clearFilterButton);
        invertFilterButton = (Button) findViewById(R.id.invertFilterButton);

        fourierButton.setVisibility(mOpenCvCameraView.VISIBLE);
        inverseFourierButton.setVisibility(mOpenCvCameraView.INVISIBLE);

        eraseFilterButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                touchMask = Mat.ones(touchMask.rows(), touchMask.cols(), touchMask.type());
            }
        });

        invertFilterButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                Core.subtract(Mat.ones(touchMask.rows(), touchMask.cols(), touchMask.type()), touchMask, touchMask);
            }
        });

        fourierButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                dftFlag = true;
                fourierButton.setVisibility(mOpenCvCameraView.INVISIBLE);
                inverseFourierButton.setVisibility(mOpenCvCameraView.VISIBLE);
            }
        });

        inverseFourierButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                dftFlag = false;
                fourierButton.setVisibility(mOpenCvCameraView.VISIBLE);
                inverseFourierButton.setVisibility(mOpenCvCameraView.INVISIBLE);
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
        Size resolution = mResolutionList.get(resolutionValue);
        mOpenCvCameraView.setResolution(resolution);
        height = resolution.height;
        width = resolution.width;

        // Now that we know the image size, set up variables
        imgXSize = width;
        imgYSize = height;

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
        complexPlanes.add(magImg);
        complexPlanes.add(magImg);

        filterPlanes = new ArrayList<Mat>();
        filterPlanes.add(magImg);
        filterPlanes.add(magImg);

        returnMat = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_8UC3);

        rgbChannels = new ArrayList<Mat>();
        rgbChannels.add(imgUint);
        rgbChannels.add(imgUint);
        rgbChannels.add(imgUint);

        rgbaChannels = new ArrayList<Mat>();
        rgbaChannels.add(imgUint);
        rgbaChannels.add(imgUint);


        touchMask = Mat.ones(height, width, CvType.CV_32FC1);
    }

    public void onCameraViewStopped() {
    }

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        if (dftFlag) // Display Fourier Transform of Image
        {
            Contrib.applyColorMap(fftMagnitude(inputFrame.gray()), returnMat, Contrib.COLORMAP_JET);

        }

        else // Display Filtered Image
            returnMat = applyFourierFilter(inputFrame.rgba());

        Log.i("TEMPTEST", String.format("returnMat type: %d", returnMat.type()));
        // Type is 24 - 8UC4
        return returnMat;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        List<String> effects = mOpenCvCameraView.getEffectList();

        if (effects == null) {
            Log.e(TAG, "Color effects are not supported by device!");
            return true;
        }

        mColorEffectsMenu = menu.addSubMenu("Color Effect");
        mEffectMenuItems = new MenuItem[effects.size()];

        int idx = 0;
        ListIterator<String> effectItr = effects.listIterator();
        while (effectItr.hasNext()) {
            String element = effectItr.next();
            mEffectMenuItems[idx] = mColorEffectsMenu.add(1, idx, Menu.NONE, element);
            idx++;
        }

        mResolutionMenu = menu.addSubMenu("Resolution");
        mResolutionList = mOpenCvCameraView.getResolutionList();
        mResolutionMenuItems = new MenuItem[mResolutionList.size()];

        ListIterator<Size> resolutionItr = mResolutionList.listIterator();
        idx = 0;
        while (resolutionItr.hasNext()) {
            Size element = resolutionItr.next();
            mResolutionMenuItems[idx] = mResolutionMenu.add(2, idx, Menu.NONE,
                    Integer.valueOf(element.width).toString() + "x" + Integer.valueOf(element.height).toString());
            idx++;
        }

        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "called onOptionsItemSelected; selected item: " + item);
        if (item.getGroupId() == 1) {
            mOpenCvCameraView.setEffect((String) item.getTitle());
            Toast.makeText(this, mOpenCvCameraView.getEffect(), Toast.LENGTH_SHORT).show();
        } else if (item.getGroupId() == 2) {
            int id = item.getItemId();
            Size resolution = mResolutionList.get(id);
            mOpenCvCameraView.setResolution(resolution);
            resolution = mOpenCvCameraView.getResolution();
            String caption = Integer.valueOf(resolution.width).toString() + "x" + Integer.valueOf(resolution.height).toString();
            Toast.makeText(this, caption, Toast.LENGTH_SHORT).show();
        }

        return true;
    }

    private Mat fftMagnitude(Mat imGray) {
        imGray.convertTo(imGray, CvType.CV_32FC1);

        Core.dft(imGray, complexI, Core.DFT_COMPLEX_OUTPUT, imGray.rows());
        Core.split(complexI, complexPlanes);
        Core.magnitude(complexPlanes.get(0), complexPlanes.get(1), magImg);
        Core.log(magImg, magImg);
        magImg = fftShift(magImg);

        Core.normalize(magImg, magImg, 0, 255, Core.NORM_MINMAX);
        magImg = magImg.mul(touchMask); // Apply filtering
        magImg.convertTo(imgUint, CvType.CV_8UC1);

        // Release Matricies
        complexPlanes.get(0).release();
        complexPlanes.get(1).release();
        imGray.release();
        complexI.release();
        magImg.release();

        return imgUint;
    }

    private Mat applyFourierFilter(Mat imRgb) {
        imRgb.convertTo(imRgb, CvType.CV_32FC3); // convert to floating-point

        //Imgproc.copyMakeBorder(imGray, zeroPadded, 0, imgYSize - imGray.rows(), 0,
        //        imgXSize - imGray.cols(), Imgproc.BORDER_CONSTANT);

        //zeroPadded = Mat.zeros(imRgb.rows(),imRgb.cols(),CvType.CV_32FC1);


        Core.split(imRgb, rgbChannels);

        Mat touchMaskShifted = fftShift(touchMask);
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

        // Normalize to uint8
        Core.normalize(imRgb, imRgb, 0, 255, Core.NORM_MINMAX);
        imRgb.convertTo(imRgb, CvType.CV_8UC3);

        // Release Matricies
        complexPlanes.get(0).release();
        complexPlanes.get(1).release();
        filterPlanes.get(1).release();
        filterPlanes.get(1).release();
        complexFilter.release();
        rgbChannels.get(0).release();
        rgbChannels.get(1).release();
        rgbChannels.get(2).release();
        magImg.release();
        complexI.release();

        return imRgb;
    }


    public static Mat fftShift(Mat inMat) {
        return circularShift(inMat, (int) java.lang.Math.ceil((double) inMat.cols() / 2.0),
                (int) java.lang.Math.ceil((double) inMat.rows() / 2.0));
    }

    public static Mat ifftShift(Mat inMat) {
        return circularShift(inMat, (int) java.lang.Math.floor((double) inMat.cols() / 2.0),
                (int) java.lang.Math.floor((double) inMat.rows() / 2.0));
    }

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


    private void touch_start(float x, float y) {

    }

    private void touch_move(float x, float y) {
        Log.i("TOUCHTEST", "MOVE DRAWING");
        float dx = Math.abs(x - mX);
        float dy = Math.abs(y - mY);
        if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
            mX = x;
            mY = y;
            Point touchPoint = new Point(x, y);
            Core.circle(touchMask, touchPoint, pointerSize, new Scalar(0), -1);
        }
    }

    //@SuppressLint("SimpleDateFormat")
    @Override
    public boolean onTouch(View v, MotionEvent event) {

        // Only respond to touches if we're viewing the fft
        if (dftFlag) {
            // Scale point position to mat size
            int pointX = (int) (event.getX() * ((float) touchMask.cols() / (float) v.getWidth()));
            int pointY = (int) (event.getY() * ((float) touchMask.rows() / (float) v.getHeight()));
            Point touchPoint = new Point(pointX, pointY);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    mX = pointX;
                    mY = pointY;
                    Core.circle(touchMask, touchPoint, pointerSize, new Scalar(0), -1);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = Math.abs(pointX - mX);
                    float dy = Math.abs(pointY - mY);
                    if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                        mX = pointX;
                        mY = pointY;
                        Core.circle(touchMask, touchPoint, pointerSize, new Scalar(0), -1);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    return false;
                }
                default:
                    return false;
            }
        }
        return true;
    }
}


// Code to take a picture and save to SD
/*
SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
String currentDateandTime = sdf.format(new Date());
String fileName = Environment.getExternalStorageDirectory().getPath() +
                       "/sample_picture_" + currentDateandTime + ".jpg";
mOpenCvCameraView.takePicture(fileName);
Toast.makeText(this, fileName + " saved", Toast.LENGTH_SHORT).show();
*/

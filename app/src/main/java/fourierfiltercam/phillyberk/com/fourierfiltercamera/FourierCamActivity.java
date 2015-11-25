package fourierfiltercam.phillyberk.com.fourierfiltercamera;

import android.annotation.SuppressLint;
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
import android.view.View.OnTouchListener;
import android.view.WindowManager;
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
    private boolean dftFlag = true;
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


    private List<Mat> rgbChannels;
    private List<Mat> rgbaChannels;

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
        Size resolution = mResolutionList.get(4);
        mOpenCvCameraView.setResolution(resolution);
        height = resolution.height;
        width = resolution.width;

        // Now that we know the image size, set up variables
        imgXSize = width;
        imgYSize = height;

        dftRows = Core.getOptimalDFTSize(imgYSize);
        dftCols = Core.getOptimalDFTSize(imgXSize); // on the border

        zeroPadded = new Mat(dftCols, dftRows, CvType.CV_32FC1); // expand input

        complexI = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC2);
        complexFilter = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC2);

        magImg = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC1);

        complexPlanes = new ArrayList<Mat>();
        complexPlanes.add(magImg);
        complexPlanes.add(magImg);

        filterPlanes = new ArrayList<Mat>();
        filterPlanes.add(magImg);
        filterPlanes.add(magImg);

        returnMat = Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_8UC1);

        rgbChannels = new ArrayList<Mat>();
        rgbChannels.add(magImg);
        rgbChannels.add(magImg);
        rgbChannels.add(magImg);

        rgbaChannels = new ArrayList<Mat>();
        rgbaChannels.add(magImg);
        rgbaChannels.add(magImg);
        rgbaChannels.add(magImg);

        touchMask = Mat.ones(height, width, CvType.CV_32FC1);
        //Core.multiply(touchMask, new Scalar(255), touchMask); // Scale to full value
    }

    public void onCameraViewStopped() {
    }

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        if (dftFlag) // Display Fourier Transform of Image
            Contrib.applyColorMap(fftMagnitude(inputFrame.gray()), returnMat, Contrib.COLORMAP_JET);
        else // Display Filtered Image
            returnMat = applyFourierFilter(inputFrame.gray(), touchMask);

        Log.i("TEMPTEST", String.format("returnMat type: %d", returnMat.type()));
        // Release frame
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

        Imgproc.copyMakeBorder(imGray, zeroPadded, 0, imgYSize - imGray.rows(), 0,
                imgXSize - imGray.cols(), Imgproc.BORDER_CONSTANT);

        complexPlanes.set(0, zeroPadded);
        complexPlanes.set(1, Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC1));

        filterPlanes.set(0, touchMask);
        filterPlanes.set(1, touchMask);

        Core.merge(complexPlanes, complexI);
        Core.merge(filterPlanes, complexFilter);
        Core.dft(complexI, complexI);
        complexI = fftShift(complexI);

        Core.split(complexI, complexPlanes);

        Core.magnitude(complexPlanes.get(0), complexPlanes.get(1), magImg);
        Core.log(magImg, magImg);

        // Apply alpha mask
        magImg = magImg.mul(touchMask);

        Core.normalize(magImg, magImg, 0, 255, Core.NORM_MINMAX);
        magImg.convertTo(magImg, CvType.CV_8UC1);
        Imgproc.cvtColor(magImg, magImg, Imgproc.COLOR_GRAY2RGBA);

        // Release Matricies
        complexPlanes.get(0).release();
        complexPlanes.get(1).release();
        imGray.release();
        complexI.release();
        return magImg;
    }

    private Mat applyFourierFilter(Mat imGray, Mat filter) {
        imGray.convertTo(imGray, CvType.CV_32FC1);

        //Imgproc.copyMakeBorder(imGray, zeroPadded, 0, imgYSize - imGray.rows(), 0,
        //        imgXSize - imGray.cols(), Imgproc.BORDER_CONSTANT);

        complexPlanes.set(0, zeroPadded);
        complexPlanes.set(1, Mat.zeros(zeroPadded.rows(), zeroPadded.cols(), CvType.CV_32FC1));

        Core.merge(complexPlanes, complexI);
        Core.dft(complexI, complexI);
        Core.merge(filterPlanes, complexFilter);
        Log.i("TEMPTEST", String.format("complexI type: %d", complexI.type()));
        Log.i("TEMPTEST", String.format("complexFilter type: %d", complexFilter.type()));

        complexFilter.convertTo(complexFilter, CvType.CV_32FC2);
        complexI = complexI.mul(complexFilter);

        Core.idft(complexI, complexI);

        Core.split(complexI, complexPlanes);

        Core.magnitude(complexPlanes.get(0), complexPlanes.get(1), magImg);

        Log.i("magImg type1", String.format("magImg type: %d", magImg.type()));

        Core.normalize(magImg, magImg, 0, 255, Core.NORM_MINMAX);

        Log.i("magImg type2", String.format("magImg type: %d", magImg.type()));
        magImg.convertTo(imGray, CvType.CV_8UC1);

        Log.i("magImg type3", String.format("imGray type: %d", imGray.type()));

        // Release Matricies
        complexPlanes.get(0).release();
        complexPlanes.get(1).release();
        magImg.release();
        complexI.release();
        return imGray;

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

        return result;
    }


    private void touch_start(float x, float y) {
        mX = x;
        mY = y;
        Point touchPoint = new Point(x, y);
        Core.circle(touchMask, touchPoint, pointerSize, new Scalar(0), -1);
        Log.i("TOUCHTEST", "START DRAWING");
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

    private void touch_up() {
        Log.i("TOUCHTEST", "DONE DRAWING");
    }

    @SuppressLint("SimpleDateFormat")
    @Override
    public boolean onTouch(View v, MotionEvent event) {

        // Scale point position to mat size
        int pointX = (int) (event.getX() * ((float) touchMask.cols() / (float) v.getWidth()));
        int pointY = (int) (event.getY() * ((float) touchMask.rows() / (float) v.getHeight()));

        Point touchPoint = new Point(pointX, pointY);
        Core.circle(touchMask, touchPoint, pointerSize, new Scalar(0), -1);

        Log.i("TOUCHTEST", String.format("event type: %d", event.getActionMasked()));

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                touch_start(pointX, pointY);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                touch_move(pointX, pointY);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                touch_up();
                return false;
            }
            default:
                return false;
        }



        /*
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        String currentDateandTime = sdf.format(new Date());
        String fileName = Environment.getExternalStorageDirectory().getPath() +
                               "/sample_picture_" + currentDateandTime + ".jpg";
        mOpenCvCameraView.takePicture(fileName);
        Toast.makeText(this, fileName + " saved", Toast.LENGTH_SHORT).show();
        */

    }
}

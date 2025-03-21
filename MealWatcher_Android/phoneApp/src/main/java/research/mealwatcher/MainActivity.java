/**
 <MealWatcher is a phone & watch application to record motion data from a watch and smart ring>
 Copyright (C) <2023>  <James Jolly, Faria Armin, Adam Hoover>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package research.mealwatcher;

import static java.util.Map.entry;
import static research.mealwatcher.ControlWatch.record_off_msg;
import static research.mealwatcher.ControlWatch.record_on_msg;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    public static int AppState;        /* 0=>home screen; 1=>taking picture; 2=>survey; 3=> settings */
    public static int PrePost;      /* 0=>pre picture; 1=>post picture.*/
    public static int currentView;    /* which XML file (GUI) currently displayed. */
    public static Button buttonID, buttonRetake, buttonUsePhoto;    /* used to interact with all button widgets. */
    public static Button pictureAfterMeal; /* Button used to take picture after meal is done. */
    static Button uploadDropbox; //Upload the remaining file in the dropbox by clicking it
    static TextView filesRemaining;

    static int failedUpload;
    static View.OnClickListener pictureButtonListener;      /* callback for "camera" button in home activity*/
    static View.OnClickListener takePictureButtonListener;  /* callback for "take picture" button */
    static View.OnClickListener watchRecordButtonOnClickListener;  /* callback for click event of record button */
    static View.OnClickListener ringRecordButtonOnClickListener;
    static RadioGroup.OnCheckedChangeListener prePostButtonListener;
    static Button settingsSubmitButton; /* Button holder for settings button */
    static View.OnClickListener settingsSubmitButtonOnClickListener; /* callback for click event of setting_submit_button */

    static View.OnClickListener imagePreviewRetakeListener; /* callback for click event of imagePreview_btnRetake */

    static View.OnClickListener imagePreviewUsePhotoListener; /* callback for click event of imagePreview_btnUpload */

    static Spinner ring_id_spinner;

    static Spinner location_id_spinner;
    static EditText pid_value; /* field which stores participant id value. */
    static Spinner watch_wrist_spinner; /* field which stores watch id value. */
    static Spinner button_position_spinner;
    static String prev_pid_value; /* Variable to store the previous value of participant id.*/
    static String prev_location_value; /* Variable to store the previous value of location.*/


    static int prev_ring_id_value; /* Variable to store the previous position of ring id value in ring id array.*/

    static int prev_location_id_value; /* Variable to store the previous position of location id value in location array.*/

    static int prev_watch_wrist; /* Variable to store the previous value of watch wrist preference.*/
    static int prev_button_position;

    public static MainActivity mainUIThread;    /* global variable to access main thread */
    private static SharedPreferences sharedPreferences; /* Object to store the participant_id, ring_id and watch_id. */

    private TextureView textureView;    /* "surface" to which images will be drawn; defined in camera.xml */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();    /* used to convert Android camera orientation to JPEG orientation */

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    protected CameraDevice cameraDevice;    /* pointer to info about camera */
    protected CameraCaptureSession cameraCaptureSessions;   /* info about "capture session", in this app either continuous preview or single take picture */
    protected CaptureRequest.Builder captureRequestBuilder; /* pointer to info used to create "capture session" */
    private Size imageDimension;    /* size of image */
    private ImageReader imageReader;    /* pointer to image currently displayed on-screen; image captured from here (not directly from camera) */
    private static final int REQUEST_CAMERA_PERMISSION = 200;   /* the value 200 has no meaning; when passed in, we check for the same return value to indicate success */
    private HandlerThread mBackgroundThread;    /* extra thread used while camera is active for GUI */
    private Handler mBackgroundHandler;         /* event handler associated with extra camera thread */
    private int image_capture_done;             /* state variable to indicate if takePicture() has completed */
    private File takenPicture;               /* file pointer used to store image */
    private File takenImage;               /* file pointer used to store image in ImageFolder*/
    private OutputStream output;     /* file stream used to store image */
    static Button watchRecordButton; /* Button used to start sensor recording on watch. */
    static Button ringStatusButton; /* Button used to show the connection status of the ring. */
    static Button stopRingButton; /* Button used to disconnect from ring */
    static View.OnClickListener stopRingOnClickListener; /* Listener to stop the ring. */
    static Button ringRecordButton; /* Button used to start recording on ring. */
    static Button surveyButton; /* Button used to start the survey. */
    static Button reviewPhotosButton; /* Button used to navigate users to review photos layout. */
    static Button settingsButton; /* Button used to navigate users to settings. */
    static Button launchWatchApp; /* Button to navigate to home from settings. */
    static View.OnClickListener launchWatchAppListener; /* Button to launch watch application. */
    static Button reviewPhotoHomeButton; /* Button to navigate to home from review_photos layout. */
    List<File> imageFiles;
    static View.OnClickListener surveyButtonOnClickListener;
    static View.OnClickListener settingButtonOnClickListener;
    static View.OnClickListener reviewPhotoOnClickListener;
    //static View.OnClickListener settingsHomeButtonListener;
    static View.OnClickListener reviewPhotoHomeButtonListener;

    // Intent to start a service which listens to watch messages.
    public static Intent watchServiceIntent;
    // Intent to start
    public static Intent ringServiceIntent;
    public static Context applicationContext;
    static String watchRecordStatus = "false"; /* Variable to keep track if recording is on or off.*/
    static String pictureFileName = null;
    static File newImageFolder;
    static String settingChanged = "false";

    static String ringRecordingState = "false";
    static String prePictureTaken = "false";
    static String postPictureTaken = "false";
    static String logFileName = null;
    static String currentLogFileName = null;
    static File logFile;

    private String filePrefix;
    private RecyclerView recyclerView;
    private ImageAdapter imageAdapter;
    private int currentPosition = 0;
    private TextView textTime;
    private TextView textDate;
    private TextView imageNumber;
    static final Map<String, String> months = Map.ofEntries(
            entry("JANUARY", "Jan"),
            entry("FEBRUARY", "Feb"),
            entry("MARCH", "Mar"),
            entry("APRIL", "Apr"),
            entry("MAY", "May"),
            entry("JUNE", "Jun"),
            entry("JULY", "Jul"),
            entry("AUGUST", "Aug"),
            entry("SEPTEMBER", "Sep"),
            entry("OCTOBER", "Oct"),
            entry("NOVEMBER", "Nov"),
            entry("DECEMBER", "Dec")
    );
    static final Map<String, String> monthNumbers = Map.ofEntries(
            entry("JANUARY", "01"),
            entry("FEBRUARY", "02"),
            entry("MARCH", "03"),
            entry("APRIL", "04"),
            entry("MAY", "05"),
            entry("JUNE", "06"),
            entry("JULY", "07"),
            entry("AUGUST", "08"),
            entry("SEPTEMBER", "09"),
            entry("OCTOBER", "10"),
            entry("NOVEMBER", "11"),
            entry("DECEMBER", "12")
    );
    static final Map<Integer, String> weekDay = Map.ofEntries(
            entry(1, "Sun"),
            entry(2, "Mon"),
            entry(3, "Tues"),
            entry(4, "Wed"),
            entry(5, "Thurs"),
            entry(6, "Fri"),
            entry(7, "Sat")
    );

    static final String[] REQUIRED_PERMISSIONS;

    static {
        // Build.VERSION_CODES.S = 31
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            REQUIRED_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.ACCESS_FINE_LOCATION};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            REQUIRED_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.ACCESS_FINE_LOCATION};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) /* Build.VERSION_CODES.Q = 29 */ {
            REQUIRED_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.ACCESS_FINE_LOCATION};
        } else {
            REQUIRED_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
    }

    static ConstraintLayout layoutBeforeMeal;
    static ConstraintLayout layoutAfterMeal;
    static Button beforeMeal; /* button used to change the checklist to before meal checklist. */
    static View.OnClickListener beforeMealOnClickListener;
    static Button afterMeal; /* button used to change the checklist to after meal checklist. */
    static View.OnClickListener afterMealOnClickListener;

    static CheckBox startWatchSensors; /* Check box which tracks if recording is started on watch or not.*/
    static CheckBox connectToRing; /* Check box which tracks if connection to ring is made or not. */
    static CheckBox turnOnRing; /* Check box to track if ring is turned on or not. */
    static CheckBox takeBeforeMealPicture; /* Check box to tracks if picture is taken or not before the meal. */
    static CheckBox stopWatchSensors; /* Checkbox which tracks if recording is stopped on watch. */
    static CheckBox turnOffRing; /* Check box which tracks if ring is turned off or not. */
    static CheckBox takeAfterMealPicture; /* Check box which tracks if picture is taken or not after the meal. */
    static CheckBox takeSurvey; /* Check box which tracks if survey is taken or not. */

    private LogFunction logFunction;
    private Handler recordingHandler; //For closing the app after 1 hour
    private Runnable recordingEndRunnable;
    static boolean watchRecordStarted;

    static boolean isRecordingDone;
    private static Intent batteryStatus;

    private static boolean isToastShown;
    private static boolean storingChargeFirstTime;


    BroadcastReceiver batteryLevelReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context context, Intent intent){
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float battPct = level * 100 /(float)scale;

            if(battPct <= 50.0 && !isToastShown){
                showToast( "Please recharge your phone soon!!", 1);

                logFunction.information("Battery", "Phone's charge is below 50%, the value is: " + battPct + "%");
                isToastShown = true;
                AlertDialog.Builder batteryAllert = new AlertDialog.Builder(MainActivity.this);
                batteryAllert.setMessage("Please recharge your phone before the next recording.");
                batteryAllert.setTitle("Recharge your phone");
                batteryAllert.setCancelable(false);
                batteryAllert.setPositiveButton("Ok", (DialogInterface.OnClickListener)(dialog, which) -> {
                    logFunction.information("Battery", "Participant acknowledged it.");
                    dialog.cancel();
                });

                // Create the Alert dialog
                AlertDialog alertDialog = batteryAllert.create();
                // Show the Alert Dialog box
                alertDialog.show();

                //playAudio();
            }

        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //For making the battery settings unrestricted
        PowerManager powerManager = (PowerManager) getApplicationContext().getSystemService(POWER_SERVICE);
        String packageName = "research.mealwatcher";

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent i = new Intent();

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                i.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + packageName));
                startActivity(i);
            }
        }

        // Lock screen orientation to portrait mode.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.home);
        mainUIThread = this;
        recordingHandler = new Handler();

        init();        /* create button callback functions */

        //Initializing all the layout component
        launchWatchApp = (Button) findViewById(R.id.launchWatchApp);
        launchWatchApp.setOnClickListener(launchWatchAppListener);

        layoutBeforeMeal = (ConstraintLayout) findViewById(R.id.constraintLayout);
        layoutAfterMeal = (ConstraintLayout) findViewById(R.id.constraintLayoutAfterMeal);
        beforeMeal = (Button) findViewById(R.id.beforeMealButton);
        beforeMeal.setOnClickListener(beforeMealOnClickListener);
        afterMeal = (Button) findViewById(R.id.afterMealButton);
        afterMeal.setOnClickListener(afterMealOnClickListener);

        stopRingButton = (Button) findViewById(R.id.stopRingButton);
        stopRingButton.setOnClickListener(stopRingOnClickListener);
        startWatchSensors = (CheckBox) findViewById(R.id.startWatchSensor_checkbox);
        connectToRing = (CheckBox) findViewById(R.id.connectRing_checkBox);
        turnOnRing = (CheckBox) findViewById(R.id.turnRingOn_checkbox);
        takeBeforeMealPicture = (CheckBox) findViewById(R.id.takePicture_checkBox);

        stopWatchSensors = (CheckBox) findViewById(R.id.stopWatchSensor_checkbox);
        turnOffRing = (CheckBox) findViewById(R.id.turnRingOff_checkbox);
        takeAfterMealPicture = (CheckBox) findViewById(R.id.takePictureAfterMeal_checkBox);
        takeSurvey = (CheckBox) findViewById(R.id.survey_checkBox);
        buttonID = (Button) findViewById(R.id.picture);
        buttonID.setOnClickListener(pictureButtonListener);
        pictureAfterMeal = (Button) findViewById(R.id.pictureAfterMeal);
        pictureAfterMeal.setOnClickListener(pictureButtonListener);
        surveyButton = (Button) findViewById(R.id.survey_button);
        surveyButton.setOnClickListener(surveyButtonOnClickListener);
        settingsButton = (Button) findViewById(R.id.relativeLayout).findViewById(R.id.settings);
        settingsButton.setOnClickListener(settingButtonOnClickListener);
        reviewPhotosButton = (Button) findViewById(R.id.review_photos);
        reviewPhotosButton.setOnClickListener(reviewPhotoOnClickListener);

        watchRecordButton = (Button) findViewById(R.id.watchRecordButton);
        setButtonText(watchRecordButton, record_off_msg);
        setButtonColor(watchRecordButton, Color.RED);
        ringRecordButton = (Button) findViewById(R.id.ringRecordButton);
        ringRecordButton.setText("...Tap HERE To Connect To Ring");
        ringRecordButton.setOnClickListener(ringRecordButtonOnClickListener);

        ringStatusButton = (Button) findViewById(R.id.ringStatusButton);
        setButtonColor(ringStatusButton, Color.RED);
        setButtonText(ringStatusButton, record_off_msg);

    }

    @Override
    protected void onStart() {
        logFunction.information("Activity", "onStart");
        super.onStart();
    }

    // TODO: Add a permission to access the camera.
    private void getRequiredPermissions() {
        logFunction.information("Phone","Getting required permissions");
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            showToast("No Bluetooth hardware?", 0);
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(REQUIRED_PERMISSIONS, 1);
        }

        /*
        If Bluetooth is not enabled, prompt user to turn on bluetooth so that watch and mobile gets
        connected via Bluetooth.
         */
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
        }
    }

    static void setButtonText(Button button, String text) {
        button.setText(text);
    }

    static void setButtonColor(Button button, int color) {
        button.setBackgroundColor(color);
    }

    static void informUser(String msg) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show();

    }


    /* onResume is always called by onCreate (see Android activity lifecycle) */
        /* Thus a background thread is started here when the app is started
        /* It is also (re)started when the app is resumed from pause (pushed to background) */

    @Override
    protected void onResume() {
        super.onResume();
        logFunction.information("Activity", "onResume");

        /* start a background thread, to be used during camera picture capture */
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        if (currentView == R.layout.camera) {
            if (textureView.isAvailable()) {
                openCamera();
            } else {
                textureView.setSurfaceTextureListener(textureListener);
            }
        }
        recordingHandler.removeCallbacks(recordingEndRunnable);
    }

    @Override
    protected void onPause() {
        logFunction.information("Activity", "onPause");
        recordingHandler.postDelayed(recordingEndRunnable, 60*60*1000);
        /* stop the background thread */
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        /* close the camera so other apps can use it (if camera is currently in use) */
        if (currentView == R.layout.camera || currentView == R.layout.image_preview) {
            // CLosing camera should be done on separate thread such that the main UI logic is not interrupted.
            // In other words, to ensure that our application doesn't gets stuck or crashed.
            Thread thread_to_close_camera = new Thread(new Runnable() {
                @Override
                public void run() {
                    closeCamera();
                }
            });
            thread_to_close_camera.start();
        }

        super.onPause();
    }


    /* change the display content to one of {home, camera, survey, settings} */
    @Override
    public void onContentChanged() {


        if (currentView == R.layout.home) {
            logFunction.information("UI", "Home");

            launchWatchApp = (Button) findViewById(R.id.launchWatchApp);
            launchWatchApp.setOnClickListener(launchWatchAppListener);

            layoutBeforeMeal = (ConstraintLayout) findViewById(R.id.constraintLayout);
            layoutAfterMeal = (ConstraintLayout) findViewById(R.id.constraintLayoutAfterMeal);
            if (PrePost == 0) {
                layoutBeforeMeal.setVisibility(View.VISIBLE);
                layoutAfterMeal.setVisibility(View.GONE);
            } else if (PrePost == 1) {
                layoutAfterMeal.setVisibility(View.VISIBLE);
                layoutBeforeMeal.setVisibility(View.GONE);
            }
            beforeMeal = (Button) findViewById(R.id.beforeMealButton);
            beforeMeal.setOnClickListener(beforeMealOnClickListener);
            afterMeal = (Button) findViewById(R.id.afterMealButton);
            afterMeal.setOnClickListener(afterMealOnClickListener);

            stopRingButton = (Button) findViewById(R.id.stopRingButton);
            stopRingButton.setOnClickListener(stopRingOnClickListener);

            startWatchSensors = (CheckBox) findViewById(R.id.startWatchSensor_checkbox);
            stopWatchSensors = (CheckBox) findViewById(R.id.stopWatchSensor_checkbox);
            if (watchRecordStatus.equals("true")) {
                startWatchSensors.setChecked(true);
                stopWatchSensors.setChecked(false);
            } else {
                startWatchSensors.setChecked(false);
                stopWatchSensors.setChecked(true);

            }
            connectToRing = (CheckBox) findViewById(R.id.connectRing_checkBox);
            turnOnRing = (CheckBox) findViewById(R.id.turnRingOn_checkbox);
            turnOffRing = (CheckBox) findViewById(R.id.turnRingOff_checkbox);
            if (ringRecordingState.equals("true")) {
                connectToRing.setChecked(true);
                turnOnRing.setChecked(true);
                turnOffRing.setChecked(false);
            } else {
                connectToRing.setChecked(false);
                turnOnRing.setChecked(false);
                turnOffRing.setChecked(true);
            }
            takeBeforeMealPicture = (CheckBox) findViewById(R.id.takePicture_checkBox);
            if (prePictureTaken.equals("true")) {
                takeBeforeMealPicture.setChecked(true);
            }

            takeAfterMealPicture = (CheckBox) findViewById(R.id.takePictureAfterMeal_checkBox);
            if (postPictureTaken.equals("true")) {
                takeAfterMealPicture.setChecked(true);
            }
            takeSurvey = (CheckBox) findViewById(R.id.survey_checkBox);

            buttonID = (Button) findViewById(R.id.picture);
            buttonID.setOnClickListener(pictureButtonListener);
            pictureAfterMeal = (Button) findViewById(R.id.pictureAfterMeal);
            pictureAfterMeal.setOnClickListener(pictureButtonListener);
            surveyButton = (Button) findViewById(R.id.survey_button);
            surveyButton.setOnClickListener(surveyButtonOnClickListener);
            settingsButton = (Button) findViewById(R.id.settings);
            settingsButton.setOnClickListener(settingButtonOnClickListener);
            reviewPhotosButton = (Button) findViewById(R.id.review_photos);
            reviewPhotosButton.setOnClickListener(reviewPhotoOnClickListener);

            watchRecordButton = (Button) findViewById(R.id.watchRecordButton);
            ringRecordButton = (Button) findViewById(R.id.ringRecordButton);
            ringRecordButton.setText("...Tap HERE to connect to Ring");
            ringRecordButton.setOnClickListener(ringRecordButtonOnClickListener);
            ringStatusButton = (Button) findViewById(R.id.ringStatusButton);

            // Maintaining state when record button is active.
            if (watchRecordStatus.equals("true")) {
                setButtonText(watchRecordButton, record_on_msg);
                // Setting green color indication the recording started.
                setButtonColor(watchRecordButton, Color.parseColor("#008000"));
            } else {
                setButtonText(watchRecordButton, record_off_msg);
                // Setting red color indication the recording started.
                setButtonColor(watchRecordButton, Color.parseColor("#FF0000"));
            }
            if (ringRecordingState.equals("true")) {
                setButtonText(ringStatusButton, record_on_msg);
                // Setting green color indication the recording started.
                setButtonColor(ringStatusButton, Color.parseColor("#008000"));
            } else {
                setButtonText(ringStatusButton, record_off_msg);
                // Setting red color indication the recording started.
                setButtonColor(ringStatusButton, Color.parseColor("#FF0000"));
            }

        } else if (currentView == R.layout.camera) {
            logFunction.information("UI", "Camera");
            textureView = (TextureView) findViewById(R.id.texture); // "surface" to which images will be drawn; defined in camera.xml
            textureView.setSurfaceTextureListener(textureListener);
            buttonID = (Button) findViewById(R.id.btn_takepicture);
            buttonID.setOnClickListener(takePictureButtonListener);
        } else if (currentView == R.layout.image_preview) {
            logFunction.information("UI", "Img_View");
            buttonRetake = findViewById(R.id.btn_retake);
            buttonRetake.setOnClickListener(imagePreviewRetakeListener);
            buttonUsePhoto = findViewById(R.id.btn_usepicture);
            buttonUsePhoto.setOnClickListener(imagePreviewUsePhotoListener);
            ImageView imageView = findViewById(R.id.imageView);


            File imgFile = new File(pictureFileName);
            // on below line we are checking if the image file exist or not.
            if (imgFile.exists()) {
                // on below line we are creating an image bitmap variable
                // and adding a bitmap to it from image file.
                Bitmap imgBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                // on below line we are setting bitmap to our image view.
                imageView.setImageBitmap(imgBitmap);
            }

        } else if (currentView == R.layout.settings_layout) {
            logFunction.information("UI", "Settings");
            settingsButton.setOnClickListener(settingButtonOnClickListener);
            failedUpload = logFunction.failedToUpload();

            settingChanged = "false";
            uploadDropbox = findViewById(R.id.buttonUpload); //Remaining files can be uploaded by clicking this button
            filesRemaining = findViewById(R.id.fileRemain);

            uploadDropbox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    ControlWatch.uploadButton = "clicked";

                    watchServiceIntent = new Intent(applicationContext, ControlWatch.class);
                    watchServiceIntent.setAction("upload_to_dropbox");
                    Log.d("DropBoxUpload", "Dropbox thread is started as the user clikced the button");
                    startService(watchServiceIntent);

                }
            });



            //Setting all the values at the beginning of the settings layout started

            pid_value = (EditText) findViewById(R.id.pid_value);
            pid_value.setText(prev_pid_value);

            ring_id_spinner = (Spinner) findViewById(R.id.ring_spinner);
            location_id_spinner = (Spinner) findViewById(R.id.location_spinner);
            button_position_spinner = (Spinner) findViewById(R.id.button_spinner);
            watch_wrist_spinner = (Spinner) findViewById(R.id.wrist_spinner);

            // Create an ArrayAdapter using the string array and a default spinner layout.
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                    this,
                    R.array.ring_ids,
                    android.R.layout.simple_spinner_item
            );

            // Create an ArrayAdapter for location using the string array and a default spinner layout.
            ArrayAdapter<CharSequence> locationAdapter = ArrayAdapter.createFromResource(
                    this,
                    R.array.location,
                    android.R.layout.simple_spinner_item
            );

            // Create an ArrayAdapter using the string array and a default spinner layout.
            ArrayAdapter<CharSequence> positionAdapter = ArrayAdapter.createFromResource(
                    this,
                    R.array.button,
                    android.R.layout.simple_spinner_item
            );


            ArrayAdapter<CharSequence> wristAdapter = ArrayAdapter.createFromResource(
                    this,
                    R.array.wrist,
                    android.R.layout.simple_spinner_item
            );

            // Specify the layout to use when the list of choices appears.
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            locationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            wristAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            positionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

            // Apply the adapter to the spinner.
            ring_id_spinner.setAdapter(adapter);
            location_id_spinner.setAdapter(locationAdapter);
            button_position_spinner.setAdapter(positionAdapter);
            watch_wrist_spinner.setAdapter(wristAdapter);


            ring_id_spinner.setSelection(prev_ring_id_value);
            location_id_spinner.setSelection(prev_location_id_value);
            button_position_spinner.setSelection(prev_button_position);
            watch_wrist_spinner.setSelection(prev_watch_wrist);

            filesRemaining.setText(String.valueOf(failedUpload));
            System.out.println("FilesRemaining In the setting layout: " + failedUpload);

            settingsSubmitButton = (Button) findViewById(R.id.settings_submit_button);
            settingsSubmitButton.setOnClickListener(settingsSubmitButtonOnClickListener);

            ring_id_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                    if (prev_ring_id_value != position) {
                        System.out.println("ring id changed!");
                        settingChanged = "true";
                    }
                    prev_ring_id_value = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });


            location_id_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                    if (prev_location_id_value != position) {
                        settingChanged = "true";
                    }
                    prev_location_id_value = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            ring_id_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                    if (prev_ring_id_value != position) {
                        System.out.println("ring id changed!");
                        settingChanged = "true";
                    }
                    prev_ring_id_value = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            button_position_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                    if (prev_button_position != position) {
                        settingChanged = "true";
                    }
                    prev_button_position = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });

            watch_wrist_spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                    if (prev_watch_wrist != position) {
                        settingChanged = "true";
                    }
                    prev_watch_wrist = position;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parentView) {
                }
            });
            pid_value.addTextChangedListener(new TextWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    if (!prev_pid_value.equals(s.toString())) {
                        settingChanged = "true";
                    }
                    prev_pid_value = s.toString();
                }

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }
            });


            // Setting the previous values to restore the state.
            if (prev_pid_value.length() < 5) {
                prev_pid_value = "00000".substring(prev_pid_value.length()) + prev_pid_value; // This will ensure that the PID is four digit based on the PID value
            } else {
                prev_pid_value = prev_pid_value;
            }


            pid_value.setOnClickListener(v -> {
                confirmSettingsChanged();
            });
            ring_id_spinner.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        confirmSettingsChanged();
                    }
                    return true;
                }
            });

        } else if (currentView == R.layout.review_photos) {
            logFunction.information("UI", "Review");
            currentPosition = 0;
            textTime = findViewById(R.id.editTextTime);
            textDate = findViewById(R.id.editTextDate);
            imageNumber = findViewById(R.id.imageNumber);
            reviewPhotoHomeButton = findViewById(R.id.review_photos_home);
            reviewPhotoHomeButton.setOnClickListener(reviewPhotoHomeButtonListener);

            // Adding this section of code for retrieving photos from the app directory.
            recyclerView = findViewById(R.id.recyclerView);
            recyclerView.setLayoutManager(new LinearLayoutManager(this,
                    LinearLayoutManager.HORIZONTAL, true));

            // Set date and time for the current image displayed.
            try {
                setDateAndTime(imageFiles);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }

            // Create and set the adapter
            imageAdapter = new ImageAdapter(this, imageFiles);
            recyclerView.setAdapter(imageAdapter);
            recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);

                    // Calculate the visible item position in your layout manager
                    LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();

                    // Update the current position
                    currentPosition = firstVisibleItem;
                }
            });
            // Disable RecyclerView scrolling gesture
            recyclerView.setOnTouchListener((v, event) -> true);


            Button btnScrollLeft = findViewById(R.id.btnScrollLeft);
            Button btnScrollRight = findViewById(R.id.btnScrollRight);

            // Set click listeners
            btnScrollLeft.setOnClickListener(view -> {
                try {
                    scrollRecyclerView(recyclerView, -1, imageFiles);
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            });
            btnScrollRight.setOnClickListener(view -> {
                try {
                    scrollRecyclerView(recyclerView, 1, imageFiles);
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        super.onContentChanged();
    }

    private void scrollRecyclerView(RecyclerView recyclerView, int direction,
                                    List<File> imageFiles) throws ParseException {
        // Update the current position based on the direction
        currentPosition -= direction;
        System.out.println("Current position inside scroll recycler view: " + currentPosition);
        // Ensure the position is within the bounds of your data list
        if (currentPosition < 0) {
            currentPosition = 0;
        } else if (currentPosition >= imageFiles.size()) {
            currentPosition = imageFiles.size() - 1;
        }

        // Scroll the RecyclerView to the new position
        recyclerView.smoothScrollToPosition(currentPosition);
        // Set date and time for the current image displayed.
        setDateAndTime(imageFiles);
    }

    private void setDateAndTime(List<File> imageFiles) throws ParseException {
        // Extract date and time from the image file name
        int position = imageFiles.size() - 1;
        File imageFile = imageFiles.get(position - currentPosition);
        String fileName = imageFile.getName();
        // Assuming the fileName format is something like "PPPPP-yyyy-MM-dd-HH-mm-ss.jpg"
        String date = fileName.substring(6, 16);
        String time = fileName.substring(17, 24);
        time = time.substring(0, 2) + ":" + time.substring(3);

        SimpleDateFormat _24HourSDF = new SimpleDateFormat("HH:mm");
        SimpleDateFormat _12HourSDF = new SimpleDateFormat("hh:mm a");
        Date _24HourDt = _24HourSDF.parse(time);
        time = _12HourSDF.format(_24HourDt);

        LocalDate currentDate = LocalDate.parse(date);
        // Get day from date
        int day = currentDate.getDayOfMonth();
        // Get month from date
        Month month = currentDate.getMonth();
        // Get year from date
        int year = currentDate.getYear();
        Calendar cal = Calendar.getInstance();

        Date dat = new SimpleDateFormat("dd/MM/yyyy").parse(day + "/" +
                monthNumbers.get(String.valueOf(month)) + "/" + year);
        cal.setTime(dat);
        String dayOfWeek = weekDay.get(cal.get(Calendar.DAY_OF_WEEK));

        textTime.setText(dayOfWeek + " " + time);
        textDate.setText(day + " " + months.get(String.valueOf(month)) + " " + year);
        imageNumber.setText((position - currentPosition + 1) + "/" + imageFiles.size());
    }


    /* callback function for "texture surface"; this is full-screen box defined in camera.xml */
    /* when it appears on-screen, the camera is opened, which then starts a live camera preview */
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            // open camera and start continuous preview of live images
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // this app will never change the texture surface size (image capture preview/size is always full-screen)
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };


    /* callback function for opening camera */
    /* when successful (camera is opened), it creates a preview = continuous display of live camera feed */
    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            /* create a capture session that continuously streams images (i.e. preview) */
            try {
                SurfaceTexture texture = textureView.getSurfaceTexture();
                texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
                Surface surface = new Surface(texture);
                captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                captureRequestBuilder.addTarget(surface);
                cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        if (cameraDevice == null) { // camera is already closed
                            return;
                        }
                        // when the session is ready, start displaying the preview (live camera feed)
                        cameraCaptureSessions = cameraCaptureSession;
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        try {
                            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }

                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        logFunction.error("Camera","Camera preview unavailable");
                        showToast("Camera preview unavailable", 0);
                    }
                }, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
                String prepostImg = (PrePost == 0) ? "pre" : "post";
                logFunction.error("Camera","Got an error: " + String.valueOf(e)  +" while accessing camera for" + prepostImg + " image." );
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            logFunction.error("Camera","Error with camera device " + error);
            cameraDevice.close();
            cameraDevice = null;
        }

    };


    /* gets cameraID and opens camera; called when texture listener is set, and if app resumes */
    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // ask permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{android.Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, cameraStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            String prepostImg = (PrePost == 0) ? "pre" : "post";
            logFunction.error("Camera","Got an error: "+ String.valueOf(e) +" while opening camera for" + prepostImg + " image");
        }

    }


    private void closeCamera() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

    }


    /* a sequential function that captures a single image; called within "take picture" button listener */
    /* at the end, sets the variable image_capture_done to 1 so we know everything is completed */
    protected void takePicture() {
        if (cameraDevice == null) {
            showToast("Could not open camera", 0);
            logFunction.information("Camera", "Could not open camera.");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = null;
            if (characteristics != null) {
                jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            }
            int width = 1280;
            int height = 720;
            boolean isSupported = false;

            if (jpegSizes != null && 0 < jpegSizes.length) {
                for (Size size : jpegSizes) {
                    if (size.getWidth() == width && size.getHeight() == height) {
                        isSupported = true;
                        System.out.println("Supporting the image size " + isSupported);
                        break; // Break out of the loop once the desired resolution is found
                    }
                }
                if (!isSupported) {
                    width = jpegSizes[0].getWidth();
                    height = jpegSizes[0].getHeight();
                }
            }
            ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(reader.getSurface());
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(reader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            /* find current Android orientation and convert to JPEG orientation */
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            if (prev_pid_value.length() < 5) {
                filePrefix = "00000".substring(prev_pid_value.length()) + prev_pid_value + "-"; // This will ensure that the folder name is four digit based on the PID value
            } else {
                filePrefix = prev_pid_value + "-";
            }

            //String filePrefix = prev_pid_value + "-";
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
            filePrefix += now.format(formatter);
            String prepost = (PrePost == 0) ? "pre" : "post";
            filePrefix += "-" + prepost;

            takenPicture = new File(MainActivity.this.getExternalFilesDir(null) + "/" + filePrefix + ".jpg");
            /* initialize callback function to listen for new image available */
            /* this function writes the image to storage */

            if(newImageFolder.exists()){
                pictureFileName = newImageFolder + "/" + filePrefix + ".jpg";
                takenImage = new File(pictureFileName);

            }


            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    try (Image image = reader.acquireLatestImage()) {
                        //writeToLog("Image is available for image reader");
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        output = new FileOutputStream(takenPicture);
                        output.write(bytes);
                        output.close();

                        output = new FileOutputStream(takenImage);
                        output.write(bytes);
                        output.close();
                        reader.close(); // have to call this or subsequent image capture may fail
                        image_capture_done = 1;
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        logFunction.error("Camera", "Error while closing image reader: " + String.valueOf(e));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            };
            /* start the callback function on the image reader */
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            /* set up an image capture "session" (configures camera, memory, etc.) */
            /* can take several hundred ms so runs asynchronously on background thread */
            /* when the image is finished capturing, it triggers the image reader listener callback function (above) */
            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                /* executed when created */ public void onConfigured(CameraCaptureSession session) {
                    try {
                        // writeToLog("Capturing the session");
                        session.capture(captureBuilder.build(), null, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        logFunction.error("Camera", "Error on capturing the image: " + String.valueOf(e));
                        e.printStackTrace();
                    }
                }

                @Override
                /* should never happen ... could have error message to user here to try again ... */ public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
            String prepostImg = (PrePost == 0) ? "pre" : "post";
            logFunction.error("Camera","Got an error: "+ String.valueOf(e)  +" while taking" + prepostImg + " image");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                showToast("This app requires permission to use the phone's camera.  Please add permission.", 1);
                logFunction.information("Permission", "User doesn't allow permission so closing the app");
                finish();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }


    void init() {
        //To write the log files
        logFunction = new LogFunction();

        applicationContext = getApplicationContext();
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        batteryStatus = applicationContext.registerReceiver(null, ifilter);

        isRecordingDone = false;
        storingChargeFirstTime = true;
        isToastShown = false;
        sharedPreferences = getSharedPreferences("myPreferences", 0);
        prev_pid_value = sharedPreferences.getString("prev_pid_value", "99999");
        prev_ring_id_value = Integer.parseInt(sharedPreferences.getString("prev_ring_id_value", "0"));
        prev_location_id_value = Integer.parseInt(sharedPreferences.getString("prev_location_id_value", "0"));
        prev_location_value = sharedPreferences.getString("prev_location_value", "None");
        System.out.println("Location at the beginning: " + prev_location_value);
        prev_button_position = Integer.parseInt(sharedPreferences.getString("prev_button_position", "0"));
        prev_watch_wrist = Integer.parseInt(sharedPreferences.getString("prev_wrist_position", "0"));
        String prev_button_position_value = sharedPreferences.getString("prev_button_position_value", "Right/Top");
        String prev_watch_wrist_value = sharedPreferences.getString("prev_wrist_position_value", "Right");
        failedUpload = sharedPreferences.getInt("failed_upload", 0);

        LocalDateTime now = LocalDateTime.now();
        logFileName = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"))+"-phone.log";
        currentLogFileName = prev_pid_value + "-" + logFileName;
        System.out.println("Log file name in the currentLogFile: " + currentLogFileName);

        logFunction.setApplicationContext(applicationContext);
        logFile = new File(applicationContext.getExternalFilesDir(null), currentLogFileName);
        logFunction.setLogFile(logFile);
        logFunction.openFile();
        String osVersion = Build.VERSION.RELEASE;
        int sdkVersion = Build.VERSION.SDK_INT;
        String versionName = BuildConfig.VERSION_NAME;
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;

        logFunction.information("Phone", "Version number of the app: " + versionName);
        logFunction.information("Phone", "Phone model: " + manufacturer + " " + model + " ,OS version: " + osVersion + " ,SDK: " + sdkVersion);
        logFunction.information("Description", "MT: Main_Thread, BT: Bluetooth connection.");
        logFunction.information("Settings", "PID: " + prev_pid_value + ", Location: " + prev_location_value + ", Wrist: " + prev_watch_wrist_value + ", Button: " + prev_button_position_value);

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = level * 100 / (float)scale;
        logFunction.information("Battery", "Battery level of the phone: " + batteryPct + "%");
        logFunction.information("Activity", "onCreate.");
        IntentFilter batteryLevelFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryLevelReceiver, batteryLevelFilter);

        logFunction.information("Dropbox", "Files failed to upload in the previous session: " + failedUpload);
        postPictureTaken = "false";
        prePictureTaken = "false";
        watchRecordStatus = "false";

        watchServiceIntent = new Intent(applicationContext, ControlWatch.class);
        watchServiceIntent.setAction("start_service");
        startService(watchServiceIntent);
        if(failedUpload != 0){
            watchServiceIntent.setAction("upload_to_dropbox");

            startService(watchServiceIntent);
        }


        logFunction.information("Ring_MT", "Starting ring service");

        ringServiceIntent = new Intent(applicationContext, ControlRing.class);
        ringServiceIntent.setAction("start_service");
        startService(ringServiceIntent);

         /* We are saving the images in the external storage and creating a folder inside the external
         storage also saving the image in there. As if we don't delete the images from the external storage,
         it is uploading all the images everytime. If we give the logic that only sent the current session
         image files, and any images failed to upload in the previous session, these images will not be sent to the DropBox ever.
         */
        String folderName = "ImagesFolder";
        newImageFolder = new File(MainActivity.this.getExternalFilesDir(null), folderName);

        if (!newImageFolder.exists()) {
            if (newImageFolder.mkdirs()) {
                logFunction.information( "Storage", "ImagesFolder created successfully");
            } else {
                logFunction.error("Storage", "Failed to create ImagesFolder");
            }
        }

        /*
        Get the required permissions needed for this application to run.
         */
        getRequiredPermissions();

        AppState = 0;
        PrePost = 0; // Selecting Pre picture by default.

        launchWatchAppListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // Starting the watch app ensures that the app on watch is started before we
                // start recording the sensor data on watch.

                System.out.println("Launch watch app button is clicked");
                watchServiceIntent.setAction("start_watch_app");
                startService(watchServiceIntent);
            }
        };

        beforeMealOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logFunction.information("MT", "Before meal's UI is displaying");
                PrePost = 0; // Before Meal
                layoutBeforeMeal.setVisibility(View.VISIBLE);
                layoutAfterMeal.setVisibility(View.GONE);
            }
        };
        afterMealOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logFunction.information("MT", "After meal's UI is displaying");
                PrePost = 1; // After Meal
                layoutAfterMeal.setVisibility(View.VISIBLE);
                layoutBeforeMeal.setVisibility(View.GONE);
            }
        };
        stopRingOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //will use this variable for checking if the ring has been disconnected for clicking the post picture button
                isRecordingDone = true;
                logFunction.information("Ring_MT", "Ring button is clicked for disconnecting the ring.");
                ringServiceIntent.setAction("disconnect_from_ring");
                startService(ringServiceIntent);
            }
        };


        ringRecordButtonOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logFunction.information("Ring_MT", "Ring button is clicked for pairing");
                ControlRing.isConnectedOnce = false;
                if(!ControlRing.recordingStarted){
                    ringServiceIntent.setAction("start_scanning_for_ring");
                    startService(ringServiceIntent);
                }
            }
        };

        pictureButtonListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //Pre-pic is clicked.
                if (PrePost == 0) {
                    logFunction.information("Camera" , "Pre picture is clicked");
                    MainActivity.mainUIThread.switchView(R.layout.camera);
                    takeBeforeMealPicture.setChecked(true);
                } else if (PrePost == 1) { // Post-pic is clicked.
                    // Stopping the recording when post-picture is captured.

                    logFunction.information("Camera" , "Post picture is clicked");
                    takeAfterMealPicture.setChecked(true);
                    MainActivity.mainUIThread.switchView(R.layout.camera);
                }
            }
        };
        takePictureButtonListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                image_capture_done = 0;
                takePicture();
                while (image_capture_done == 0) {   /* wait until async image capture is completed */
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        logFunction.error("Camera","Thread for image capture got an error: " + ex.toString());
                        Thread.currentThread().interrupt();
                    }
                }

                // CLosing camera should be done on separate thread such that the main UI logic is not interrupted.
                // In other words, to ensure that our application doesn't gets stuck or crashed.
                Thread thread_to_close_camera = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        closeCamera();
                    }
                });
                thread_to_close_camera.start();

                //Previewing the image of the pre/post
                MainActivity.mainUIThread.switchView(R.layout.image_preview);

            }
        };
        imagePreviewRetakeListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (takenPicture.exists()) {
                    takenPicture.delete();
                }
                if (takenImage.exists()) {
                    takenImage.delete();
                }
                MainActivity.mainUIThread.switchView(R.layout.camera);
            }
        };
        imagePreviewUsePhotoListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (PrePost == 1) { // If post picture is taken.
                    postPictureTaken = "true";
                    System.out.println("Setting color");
                    setButtonColor(surveyButton, Color.parseColor("#FFA500"));
                    System.out.println("Set color");
                    showToast("Please take the survey if you are done with the meal.", 1);
                } else {
                    prePictureTaken = "true";
                }
                MainActivity.mainUIThread.switchView(R.layout.home);
            }
        };
        settingsSubmitButtonOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // James's Github Tutorial Comment
                if (settingChanged.equals("true")) {

                    showToast("Settings Changed!", 0);
                    logFunction.information("Settings","Settings have been successfully changed! Storing the values to sharedPreferences");


                    // Storing the values of participant_id, watch_id and ring_id to SharedPreference object.
                    // This helps to restore state when app is reopened or when settings page is reopened in the current app session.
                    SharedPreferences.Editor mEditor = sharedPreferences.edit();

                    if (prev_pid_value.length() < 5) {
                        prev_pid_value = "00000".substring(prev_pid_value.length()) + prev_pid_value; // This will ensure that the PID is four digit based on the PID value
                    } else {
                        prev_pid_value = prev_pid_value;
                    }

                    pid_value.setText(prev_pid_value);
                    ring_id_spinner.setSelection(prev_ring_id_value);
                    location_id_spinner.setSelection(prev_location_id_value);
                    button_position_spinner.setSelection(prev_button_position);
                    watch_wrist_spinner.setSelection(prev_watch_wrist);
                    filesRemaining.setText(String.valueOf(failedUpload));

                    // Disabling the fields.
                    pid_value.setFocusable(false);
                    ring_id_spinner.setFocusable(false);

                    mEditor.putString("prev_pid_value", prev_pid_value);
                    System.out.println("Saving the PID: " + prev_pid_value);


                    mEditor.putString("prev_ring_id_value", String.valueOf(prev_ring_id_value));
                    mEditor.putString("prev_location_id_value", String.valueOf(prev_location_id_value));
                    mEditor.putString("prev_location_value", location_id_spinner.getSelectedItem().toString());
                    mEditor.putString("prev_button_position", String.valueOf(prev_button_position));
                    mEditor.putString("prev_button_position_value", button_position_spinner.getSelectedItem().toString());
                    mEditor.putString("prev_wrist_position", String.valueOf(prev_watch_wrist));
                    mEditor.putString("prev_wrist_position_value", watch_wrist_spinner.getSelectedItem().toString());

                    prev_location_value = location_id_spinner.getSelectedItem().toString(); //Which location is selected
                    mEditor.apply();

                    System.out.println("File name is renamed as PID changed");
                    logFunction.closeFile();
                    String newFileName = prev_pid_value + "-" + logFileName; // Adding PID at the beginning of the logfile
                    currentLogFileName = newFileName;
                    logFunction.fileRename(newFileName);
                    logFunction.openFile();
                    logFunction.information("File", "File name is renamed as PID is changed");
                    logFunction.information("Settings", "PID: " + prev_pid_value + " Location: " + prev_location_value + " Wrist position: " + watch_wrist_spinner.getSelectedItem().toString() + "Button position: " + button_position_spinner.getSelectedItem().toString());

                    //isPIDChanged = true;

                }
                MainActivity.mainUIThread.switchView(R.layout.home);
            }
        };
        surveyButtonOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takeSurvey.setChecked(true);
                // Create an Intent to launch the Survey Class
                Intent intent = new Intent(MainActivity.this, Survey.class);
                // Start the Survey!
                startActivity(intent);
                // Redirecting user to home page after completing the survey.
                MainActivity.mainUIThread.switchView(R.layout.home);
                PrePost = 1; // this should be kept as post value to display the after meal checklist.
            }
        };
        settingButtonOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.mainUIThread.switchView(R.layout.settings_layout);
            }
        };
        reviewPhotoOnClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Get the list of image files from internal storage
                File[] internalStorageFiles = newImageFolder.listFiles();
                imageFiles = new ArrayList<>();
                if (Objects.nonNull(internalStorageFiles)) {
                    for (File file : internalStorageFiles) {
                        if (file.getName().endsWith("jpeg") || file.getName().endsWith("jpg") ||
                                file.getName().endsWith("png")) {
                            imageFiles.add(file);
                        }
                    }
                }
                // Sort the files based on file names containing time and date
                Collections.sort(imageFiles, new Comparator<File>() {
                    @Override
                    public int compare(File file1, File file2) {
                        // Extract time and date from file names
                        String fileName1 = file1.getName();
                        String fileName2 = file2.getName();

                        // Assuming file names contain timestamps in yyyyMMddHHmmss format
                        String timeStamp1 = fileName1.substring(6, 25);
                        String timeStamp2 = fileName2.substring(6, 25);

                        // Compare timestamps
                        return timeStamp1.compareTo(timeStamp2);
                    }
                });

                if (imageFiles.size() > 0) {
                    MainActivity.mainUIThread.switchView(R.layout.review_photos);
                } else {
                    Toast.makeText(getApplicationContext(), "No images to show",
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        reviewPhotoHomeButtonListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.mainUIThread.switchView(R.layout.home);
            }
        };
        recordingEndRunnable = new Runnable() {
            @Override
            public void run() {
                System.out.println("Closing the app");
                logFunction.information("Phone", "App is forcefully closed as it exceeds the max duration");
                if(ControlRing.recordingStarted){
                    ringServiceIntent.setAction("disconnect_from_ring");
                }
                finishAffinity();
            }
        };


    }

    private void confirmSettingsChanged() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);

        // Set the message show for the Alert time
        builder.setMessage("Are you sure you want to make changes to the settings ?");

        // Set Alert Title
        builder.setTitle("Confirm");

        // Set Cancelable false for when the user clicks on the outside the Dialog Box then it will remain show
        builder.setCancelable(false);

        builder.setPositiveButton("Yes", (DialogInterface.OnClickListener) (dialog, which) -> {
            pid_value.setFocusable(true);
            pid_value.setFocusableInTouchMode(true);
            watch_wrist_spinner.setFocusable(true);
            watch_wrist_spinner.setFocusableInTouchMode(true);
            ring_id_spinner.setFocusable(true);
            ring_id_spinner.setFocusableInTouchMode(true);
            System.out.println("can we edit pid = " + pid_value.isFocusable() + pid_value.isEnabled());

            // Removing the onclick listener as we no longer need it as the user confirmed that settings has to be changed.
            pid_value.setOnClickListener(null);
            ring_id_spinner.setOnTouchListener(null);
            watch_wrist_spinner.setOnTouchListener(null);
            button_position_spinner.setOnTouchListener(null);

            // Closing the alert box.
            dialog.cancel();
        });

        // Set the Negative button with No name Lambda OnClickListener method is use of DialogInterface interface.
        builder.setNegativeButton("Cancel", (DialogInterface.OnClickListener) (dialog, which) -> {
            // If user click no then dialog box is canceled.
            dialog.cancel();
        });

        // Create the Alert dialog
        AlertDialog alertDialog = builder.create();
        // Show the Alert Dialog box
        alertDialog.show();
    }

    public static boolean isServiceRunningInForeground(Context context, Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                if (service.foreground) {
                    return true;
                }

            }
        }
        return false;
    }

    public void switchView(final int desired_view) {


        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            currentView = desired_view;
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setContentView(desired_view);
                }
            });
        }, 500);  // 500ms delay

    }



    @Override
    public void onBackPressed() {
        // Changing the layout from camera to home when the back button is pressed in camera layout.
        // When the back button is pressed in home layout, we are closing the application.
        if (currentView == R.layout.camera || currentView == R.layout.settings_layout ||
                currentView == R.layout.image_preview || currentView == R.layout.review_photos) {
            MainActivity.mainUIThread.switchView(R.layout.home);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onStop() {
        logFunction.information("Activity", "onStop");
        super.onStop();
    }

    @Override
    protected void onRestart() {
        logFunction.information("Activity", "onRestart");
        super.onRestart();
    }

    @Override
    public void onDestroy() {
        System.out.println("Recording Done: " + isRecordingDone);
        logFunction.information("Activity","onDestroy" );
        if(isRecordingDone){
            logFunction.information("Phone", "App is closing normally");
        }else{
            ControlWatch.sendDataItem("/phone_status", "state", "onStopUser");
            logFunction.error("Phone", "There is a crash in the phone app");
        }

        if(watchRecordStarted){ // Tf the watch was not used for recording, this will prevent error
            ControlWatch.sendDataItem("/phone_status", "status", "off");
            ControlWatch.cancelNotification();
        }
        recordingHandler.removeCallbacks(recordingEndRunnable);
        ControlRing.cancelNotification();

        stopService(watchServiceIntent);
        stopService(ringServiceIntent);
        unregisterReceiver(batteryLevelReceiver);
        logFunction.closeFile();

        super.onDestroy();
    }

    public void showToast(String message, int duration) {
        LayoutInflater inflater = getLayoutInflater();
        View layout = inflater.inflate(R.layout.toast_layout, findViewById(R.id.toast_root));

        TextView toastText = layout.findViewById(R.id.toast_text);
        toastText.setText(message);
        Toast toast = new Toast(getApplicationContext());
        if (duration == 0) {
            toast.setDuration(Toast.LENGTH_SHORT);
        } else {
            toast.setDuration(Toast.LENGTH_LONG);
        }
        toast.setView(layout);

        toast.show();
    }

}


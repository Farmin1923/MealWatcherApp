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

import static android.view.KeyEvent.KEYCODE_STEM_PRIMARY;
import static androidx.wear.input.WearableButtons.getButtonCount;
import static androidx.wear.input.WearableButtons.getButtonInfo;
import static androidx.wear.input.WearableButtons.getButtonLabel;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.wear.ambient.AmbientModeSupport;
import androidx.wear.input.WearableButtons;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * This class is the copy of MainActivity class but has different UI(only necessary UI for now).
 * The new UI includes only a single page activity. The activity has an option to record or to stop
 * recording the sensor data.
 */
public class MainActivity_new extends AppCompatActivity implements DataClient.OnDataChangedListener,
        AmbientModeSupport.AmbientCallbackProvider {

    private static final long TIMEOUT_DURATION_MS = 30000;
    public static Button watchRecordButtonID;
    public static int AppState;     // 0=>idle (no service running); 1=>recording;
    public static Intent serviceIntent;
    static PowerManager.WakeLock wakeLock; // Used to keep the CPU active
    public static Context applicationContext;
    public static long executionTime;
    public static NotificationManagerCompat mNotificationManager;
    public static Notification mNotification;
    static View.OnClickListener recordButtonOnClickListener; /* callback for click event of record button */
    static View.OnLongClickListener recordButtonOnLongClickListener; /* callback for long press event of record button */


    static String logFileName;
    static String logSyncFileName;
    static boolean isInitialUpload = true;
    static File logFile;
    /*
     * Declare an ambient mode controller, which will be used by
     * the activity to determine if the current mode is ambient.
     */
    private AmbientModeSupport.AmbientController ambientController;

    public static int currentView;      /* which XML file (GUI) currently displayed */
    public static int audioRecording;   /* count of seconds audio has been recording */
    public static int pingingUser;      /* count of alarms to ping user upon detection */
    private static DataClient dataClient;
    static String phoneAppStatus = "off";
    static boolean isStartWatchButtonClicked = false;
    static boolean isRecordingStarted = false; // will use it in the upload button. If this is true and the upload button is clicked at that time, no file will be uploaded
    static boolean shouldStartUploading = false;
    private static String recordButtonStatus = "off"; /*Variable holding record button status to ensure no duplicate requests are processed.*/
    private static final String[] REQUIRED_PERMISSIONS;

    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            REQUIRED_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            REQUIRED_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH_CONNECT};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            REQUIRED_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN};
        } else {
            REQUIRED_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN};
        }
    }

    /*
    The below timeoutHandler and timeoutRunnable are used for checking the status of the phone app.
     */
    private final Handler startRecordingTimeoutHandler = new Handler();
    private Runnable startRecordingTimeoutRunnable;
    private final Handler uploadFilesTimeoutHandler = new Handler();
    private Runnable uploadFilesTimeoutRunnable;
    /*
    Broadcast receiver to listen to intents from StayAwake service.
     */
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("com.example.ACTION_FINISH_TASK_ACTIVITY")) {
                finishAndRemoveTask();
            } else if (intent.getAction().equals("com.example.ACTION_FINISH_ACTIVITY")) {

                if(isRecordingFinished){
                    logFunction_watch.information("Watch", "App closed normally");
                }else {
                    logFunction_watch.error("Watch", "App is crashed");
                }

                filesFailedUpload = logFunction_watch.failedToUpload();
                SharedPreferences.Editor mEditor = sharedPreference.edit();
                mEditor.putInt("failed_upload", filesFailedUpload);
                mEditor.apply();

                StayAwake.notificationManager.cancel(1);
                recordButtonStatus = "off";
                unregisterReceiver(broadcastReceiver);
                unregisterReceiver(batteryLevelReceiver);
                stopService(serviceIntent);
                // Remove the timeout callback when the activity is destroyed
                startRecordingTimeoutHandler.removeCallbacks(startRecordingTimeoutRunnable);

                uploadFilesTimeoutHandler.removeCallbacks(uploadFilesTimeoutRunnable);

                // 9:43 9:59
                // Informing mobile app that watch app is stopped.
                sendMessageToMobile("/watch_app_status", "app", "stopped");

                logFunction_watch.information("Watch","Send a message to the mobile: 'Watch app is destroyed'");

                finishAffinity();
            }
        }
    };

    private static Button sendFile;

    static Boolean isSendFileClicked;


    private static SharedPreferences sharedPreference;
    static int filesFailedUpload;
    static TextView timeShow;

    private static final String MSG_START_RECORDING = "Record";
    private static final String MSG_UPLOAD = "Upload";
    private static PowerManager powerManager;
    static boolean delay = false;
    private static LogFunction_Watch logFunction_watch;

    private static boolean isRecordingFinished; // Will use to check if the app is closed after the recording is finished or the app crashed.
    private static Intent batteryStatus;
    private static boolean isToastShown;

    private Handler recordEndHandler;
    private Runnable recordEndTimeoutRunnable;
    private LocalDateTime recordingStartTime;  //Record end timeout handler is executed when the app enters in doze state.
    // So I am storing the time when the user clicked the start_recording button, to execute the handler after 1 hour



    BroadcastReceiver batteryLevelReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context context, Intent intent){
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            float battPct = level * 100 /(float)scale;
            System.out.println("Battery percentage: " + battPct);
            if(battPct <= 50.0 && !isToastShown){
                Toast.makeText(getApplicationContext(), "Please rechanrge your watch soon!!", Toast.LENGTH_LONG).show();
                logFunction_watch.information("Battery", "Watch's charge is below 50%, the value is: " + battPct + "%");
                isToastShown = true;
                AlertDialog.Builder batteryAllert = new AlertDialog.Builder(MainActivity_new.this, R.style.Base_ThemeOverlay_AppCompat_Dark);
                batteryAllert.setMessage("Please recharge your watch before the next recording.");
                batteryAllert.setTitle("Recharge your watch");
                batteryAllert.setCancelable(false);
                batteryAllert.setPositiveButton("Ok", (DialogInterface.OnClickListener)(dialog, which) -> {
                    logFunction_watch.information("Battery", "Participant acknowledged it.");
                    dialog.cancel();
                });

                // Create the Alert dialog
                AlertDialog alertDialog = batteryAllert.create();
                // Show the Alert Dialog box
                alertDialog.show();

            }
        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //For making the battery settings unrestricted

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + "research.mealwatcher"));
            startActivity(intent);
        }


        setContentView(R.layout.activity_main);

        applicationContext = getApplicationContext();

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        batteryStatus = applicationContext.registerReceiver(null, ifilter);

        AppState = 1;
        switchView(R.layout.activity_main);    // configures display

        audioRecording = 0;
        executionTime = 0;
        pingingUser = 0;


        init();     // creates all button callback functions



        //Showing the file number that are failed to upload
        sharedPreference = getSharedPreferences("myPreferences", 0);

        filesFailedUpload = sharedPreference.getInt("failed_upload", 0);
        File[] files = getExternalFilesDir(null).listFiles();
        logFunction_watch.information("Watch", "Number of files failed to upload in the previous session: " + filesFailedUpload);

        //Showing the watch time at the top
        timeShow = findViewById(R.id.textTime);

        // Update time initially
        updateTime();

        // Updates the time every minute
        new Thread(new Runnable() { //This thread is showing time at the top of the app
            @Override
            public void run() {
                while (true) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateTime();
                        }
                    });
                    try {
                        Thread.sleep(30 * 1000); // Update every half minute
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();


        // create IntentService (executes StayAwake.onCreate())
        serviceIntent = new Intent(applicationContext, StayAwake.class);

        // Register the broadcast receiver
        IntentFilter filter = new IntentFilter("com.example.ACTION_FINISH_ACTIVITY");
        registerReceiver(broadcastReceiver, filter);

        /*
        Enabling ambient mode. If we don't enable this the application will go to the recent apps,
        when the watch switched to ambient mode (When watch is not used, watch switches to low-power mode
        where screen turns off and notifications are disabled).
        */
        ambientController = AmbientModeSupport.attach(this);
        Wearable.getDataClient(this).addListener(this);

        startRecordingTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (isStartWatchButtonClicked) {
                    isStartWatchButtonClicked = false;
                    // This method is called when the timeout occurs
                    informUser();
                }
            }
        };

        uploadFilesTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (shouldStartUploading) {
                    System.out.println("phone is not communicating.");
                    logFunction_watch.information("Phone", "Phone app is not communicating so upload files timeout is executed.");
                    shouldStartUploading = false;
                    // TODO: Confirm here..
                    if (recordButtonStatus.equals("off")) {
                        StayAwake.sessionFinished = true;
                        serviceIntent.setAction("Clean");
                        startService(serviceIntent);
                        Intent closingIntent = new Intent("com.example.ACTION_FINISH_ACTIVITY");
                        sendBroadcast(closingIntent);

                    }
                }
            }
        };
        
        /*
        Get the required permissions needed to connect to bluetooth.
         */
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(REQUIRED_PERMISSIONS, 1);
        }

        /*
        If Bluetooth is not enabled, prompt user to turn on bluetooth so that watch and mobile gets
        connected via Bluetooth.
         */
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        }

        sendMessageToMobile("/watch_app_status", "app", "started");
    }

    private void informUser() {
        Toast.makeText(this, "Please start the phone app before recording", Toast.LENGTH_LONG).show();
        logFunction_watch.information("Phone", "Phone app is not connected");

    }

    void init() {
        logFunction_watch = new LogFunction_Watch();
        logFunction_watch.setApplicationContext(applicationContext);
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
        logFileName = now.format(formatter) + "-watch.log";
        logFile = new File(applicationContext.getExternalFilesDir(null), logFileName);
        logFunction_watch.setLogFile(logFile);
        logFunction_watch.openFile();
        String versionName = BuildConfig.VERSION_NAME;
        logFunction_watch.information("Watch", "Version number of the watch app: " + versionName);
        logFunction_watch.information("Watch", "Build version of the watch: " + Build.VERSION.SDK_INT);
        isToastShown = false;

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = level * 100 / (float)scale;
        logFunction_watch.information("Battery", "Battery level of the watch: " + batteryPct + "%");
        logFunction_watch.information("Activity","onCreate");

        IntentFilter batteryLevelFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryLevelReceiver, batteryLevelFilter);


        isRecordingStarted = false;
        isRecordingFinished = false;
        isSendFileClicked = false;

        //Handler and timeout runnable for closing the app after one hour
        recordEndHandler = new Handler();
        recordEndTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                LocalDateTime currentTime = LocalDateTime.now();
                long hoursPassed = Duration.between(recordingStartTime, currentTime).toHours();
                Log.d("Callback", "Manually check the time diff when the recording started: " + hoursPassed);

                logFunction_watch.information("TimeHandler", "Manually check the time diff when the recording started: " + hoursPassed + " hour");
                if((hoursPassed>=1) && isRecordingStarted){
                    isSendFileClicked = false;
                    System.out.println("isSendFileClicked: " + isSendFileClicked);
                    logFunction_watch.information("Watch","Send a message to the mobile: 'The record of the watch is stopped as the watch recording is greater than 1 hour.'");
                    stopRecording();
                    sendMessageToMobile("/record_button_status", "record", "off");
                    logFunction_watch.information("Watch","Send a message to the mobile: 'The record of the watch is stopped'");
                    isRecordingFinished = true;
                }
            }
        };

        dataClient = Wearable.getDataClient(this);

        sendFile = findViewById(R.id.sentFile);
        sendFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logFunction_watch.information("File_Sent", "Upload button is clicked.");
                System.out.println("Value of isRecordingStarted: " + isRecordingStarted);
                if(isRecordingStarted){
                    logFunction_watch.information("File_Sent", "Files are not sent as watch is recording");
                    Toast.makeText(getApplicationContext(), "Watch sensor is recording", Toast.LENGTH_SHORT).show();
                }else{
                    if (filesFailedUpload == 0) {
                        logFunction_watch.information("File_Sent", "No files to upload.");
                        Toast.makeText(getApplicationContext(), "No files to upload", Toast.LENGTH_SHORT).show();
                    } else {
                        isSendFileClicked = true;
                        System.out.println("Condition of sendFile: " + isSendFileClicked);

                        serviceIntent.setAction(MSG_UPLOAD);
                        startService(serviceIntent);

                    }
                }

            }
        });

        recordButtonOnClickListener = v -> {
            if (recordButtonStatus.equals("off")) {
                isStartWatchButtonClicked = true;
                sendMessageToMobile("/phone_status_check_for_recording", "status", "is_on?");
                logFunction_watch.information("User","Press the start recording");
                recordingStartTime = LocalDateTime.now();
                Log.d("TimeHandler", "Recording starts: " + recordingStartTime);
                logFunction_watch.information("Time","Recording starts: " + recordingStartTime + " ms");
                logFunction_watch.information("Watch","Send a message to the mobile: 'Is the phone app on for start recording'");
                // Start the timeout timer
                startRecordingTimeoutHandler.postDelayed(startRecordingTimeoutRunnable, TIMEOUT_DURATION_MS);

            }
        };
        recordButtonOnLongClickListener = v -> {
            if (recordButtonStatus.equals("on")) {
                isRecordingStarted = false;
                isSendFileClicked = false;
                System.out.println("isSendFileClicked: " + isSendFileClicked);
                stopRecording();
                sendMessageToMobile("/record_button_status", "record", "off");
                logFunction_watch.information("User", "User long pressed the recording button");
                logFunction_watch.information("Watch","Send a message to the mobile: 'The record of the watch is stopped'");
                isRecordingFinished = true;
            }
            return true; // Return 'true' to consume the long press event
        };

        watchRecordButtonID = (Button) findViewById(R.id.startWatchButton);
        // On click listener is used to start the recording on watch.
        watchRecordButtonID.setOnClickListener(recordButtonOnClickListener);
        // On long press listener is used to stop the recording on watch.
        watchRecordButtonID.setOnLongClickListener(recordButtonOnLongClickListener);

        // This is needed if the application goes into background and comes into foreground.
        if (recordButtonStatus.equals("on")) {
            // Sets green color indicating recording is started.
            watchRecordButtonID.setBackgroundColor(Color.parseColor("#008000"));
            // Sets text indicating that recording stared.
            watchRecordButtonID.setText("Stop Watch Recording");
            watchRecordButtonID.setAlpha(1.0f);
        }
    }

    static void sendMessageToMobile(String path, String key, String message) {
        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(path);
        putDataMapReq.getDataMap().putString(key, message);
        putDataMapReq.getDataMap().putLong("timestamp", System.currentTimeMillis());
        Task<DataItem> putDataTask = dataClient.putDataItem(putDataMapReq.asPutDataRequest().setUrgent());
        putDataTask.addOnSuccessListener(new OnSuccessListener<DataItem>() {
            @Override
            public void onSuccess(DataItem dataItem) {
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                logFunction_watch.error("Watch", "Data Sent Failed! :(" + key + " " + message);
                logFunction_watch.error("Watch", "Reason for failure of data send: " + e.toString());
            }
        });
    }

    private void updateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a");
        String currentTime = sdf.format(new Date());

        timeShow.setText(currentTime);

    }


    // TODO:is there any toggle value which is stopping the second recording.
    public void startRecording() {
        /*
        Conditional check is required to ensure no duplicate requests are processed.
         */
        if (recordButtonStatus.equals("off")) {

            recordButtonStatus = "on";
            AppState = 1;
            isRecordingStarted = true;


            serviceIntent.setAction(MSG_START_RECORDING);
            applicationContext.startForegroundService(serviceIntent);

            // Removing the callbacks before attaining it, to ensure previous timeout is not executing
            recordEndHandler.removeCallbacks(recordEndTimeoutRunnable);
            recordEndHandler.postDelayed(recordEndTimeoutRunnable,60*60*1000);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if(recordEndHandler.hasCallbacks(recordEndTimeoutRunnable)){
                    logFunction_watch.information("Callback", "The record end handler is started.");
                }
            }


            // Sets green color indicating recording is started.
            watchRecordButtonID.setBackgroundColor(Color.parseColor("#008000"));
            // Sets text indicating that recording stared.
            watchRecordButtonID.setText("Stop Watch Recording");
            watchRecordButtonID.setAlpha(1.0f);
        }
    }

    public void stopRecording() {
        /*
        Conditional check is required to ensure no duplicate requests are processed.
         */
        if (recordButtonStatus.equals("on")) {
            recordButtonStatus = "off";

            //Sets red color indicating that recording is stopped.
            watchRecordButtonID.setBackgroundColor(Color.parseColor("#FF0000"));
            // Sets text indicating that recording stared.
            watchRecordButtonID.setText("Start Watch Recording");
            watchRecordButtonID.setAlpha(1.0f);
            recordEndHandler.removeCallbacks(recordEndTimeoutRunnable);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if(!recordEndHandler.hasCallbacks(recordEndTimeoutRunnable)){
                    logFunction_watch.information("Callback", "The record end handler is removed.");
                }
            }

            // Checking if phone is available before transferring files to phone.
            sendMessageToMobile("/phone_status_check_for_upload", "status", "is_on?");

            logFunction_watch.information("Watch","Send a message to the mobile: 'Is the phone app on for sending files' " );

            shouldStartUploading = true;
            isInitialUpload = false;
            uploadFilesTimeoutHandler.postDelayed(uploadFilesTimeoutRunnable, TIMEOUT_DURATION_MS);
        }
    }



    public void switchView(final int desired_view) {
        currentView = desired_view;
        MainActivity_new.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setContentView(desired_view);
                watchRecordButtonID = (Button) findViewById(R.id.startWatchButton);
                watchRecordButtonID.setText("Start Watch Recording");
                watchRecordButtonID.setAlpha(1.0f);
                watchRecordButtonID.setClickable(true);
            }
        });
    }

    /*
    Method to listen to the messages from mobile application. The messages include
    1) To stop the watch application.
    2) To start the recording of sensor data on watch.
    3) To stop the recording of sensor data on watch.
     */
    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        System.out.println("in on data changed of watch!");
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                // DataItem changed
                DataItem item = event.getDataItem();
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                if (item.getUri().getPath().compareTo("/app_status") == 0) {
                    if (dataMap.getString("state").equals("stop")) {
                        if(recordButtonStatus.equals("off")){
                            logFunction_watch.information("Mobile","Got the message to stop this app");
                            Intent closingIntent = new Intent("com.example.ACTION_FINISH_ACTIVITY");
                            sendBroadcast(closingIntent);
                        }

                    }
                }
                if (item.getUri().getPath().compareTo("/wearos_app") == 0) {
                    System.out.println("DataMap = " + dataMap);
                    if (dataMap.getString("record").equals("on")) {
                        System.out.println("in recording on");
                        logFunction_watch.information("Mobile","Got the message to start recording");
                        startRecording();
                    } else if (dataMap.getString("record").equals("off")) {

                        stopRecording();
                    }
                }
                if (item.getUri().getPath().compareTo("/phone_status_for_recording") == 0) {
                    if (dataMap.getString("status").equals("on")) {
                        phoneAppStatus = "on";

                        logFunction_watch.information("Mobile","Response of the phone: 'Phone app is On'");
                        delay = true; // this will delay the queue of sending the files

                        // Cancel the timeout when data is received from the phone app
                        startRecordingTimeoutHandler.removeCallbacks(startRecordingTimeoutRunnable);
                        if (isStartWatchButtonClicked) {
                            // Making it false to ensure that we received the phone app status for
                            // purposes other than recording.
                            isStartWatchButtonClicked = false;
                            StayAwake.sessionFinished = false;
                            startRecording();

                            logFunction_watch.information("Watch","The recording started at the watch");

                            sendMessageToMobile("/record_button_status", "record", "on");
                            logFunction_watch.information("Watch","Send a message to the mobile: 'Watch recording is started'" );

                        }
                    } else if (dataMap.getString("status").equals("off")) {
                        logFunction_watch.information("Mobile","Response from the phone: 'Phone app is closed'");
                        phoneAppStatus = "off";
                    }
                }
                if (item.getUri().getPath().compareTo("/phone_status_for_uploading") == 0) {
                    if (dataMap.getString("status").equals("on")) {
                        phoneAppStatus = "on";
                        logFunction_watch.information("Mobile","Response of the phone: 'phone app is On' for uploading the files.");

                        // Cancel the timeout when data is received from the phone app
                        uploadFilesTimeoutHandler.removeCallbacks(uploadFilesTimeoutRunnable);
                        if (shouldStartUploading) { //11:27
                            shouldStartUploading = false;
                            logFunction_watch.information("Watch","Sending msg to upload to Stay Awake service as phone is available.");
                            delay = false;
                            StayAwake.sessionFinished = true;
                            serviceIntent.setAction("Clean");
                            startService(serviceIntent);
                            StayAwake.firstFileToUpload= true;
                            serviceIntent.setAction(MSG_UPLOAD);
                            startService(serviceIntent);
                        }
                    } else if (dataMap.getString("status").equals("off")) {
                        System.out.println("Phone app is closed!");
                        logFunction_watch.information("Mobile","Response from the phone: 'phone app is closed for uploading the files'");

                        phoneAppStatus = "off";
                    }
                }
                if (item.getUri().getPath().compareTo("/another_recording_file") == 0) {
                    // value will be of the form: "fileNumber prevFileName" i.e., "14 File_15".
                    String value = dataMap.getString("send");

                    String prevFileName = value.split(" ")[1];
                    System.out.println("Prev file sent successfully = " + prevFileName);
                    File prevFile = new File(getExternalFilesDir(null) + "/" + prevFileName);

                    logFunction_watch.information("Mobile","Response from the phone: 'phone app received a file: '" + prevFileName);

                    if (prevFile.exists()) {
                        if (!prevFileName.endsWith(".txt")) {
                            if (prevFile.delete()) {
                            }
                        }
                    }

                    int numOfFile = Integer.parseInt(value.split(" ")[0]);
                    logFunction_watch.information("Mobile","Response from the phone: 'Send another file'");

                    StayAwake.uploadFilesToPhone(getExternalFilesDir(null), dataClient,
                            isInitialUpload, numOfFile);
                }
            }
        }
    }

    @Override
    public void onResume() {
        logFunction_watch.information("Activity", "onResume()");
        super.onResume();
        System.out.println("in onResume() of watch");
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    public void onPause() {
        logFunction_watch.information("Activity", "onPause()");
        super.onPause();
    }

    public boolean foregroundServiceRunning() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (StayAwake.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onStop() {
        logFunction_watch.information("Activity", "onStop()");

        System.out.println("In on stop of watch");
        super.onStop();
    }



    /*
    Even though we are performing the clean up task(i.e., cleaning of resources) in onDestroy() method,
    it is not guaranteed that this method will be called when the app is destroyed.
     */
    public void onDestroy() {

        System.out.println("In destroy() of watch");
        logFunction_watch.closeFile();

        super.onDestroy();
    }

    @Override
    public AmbientModeSupport.AmbientCallback getAmbientCallback() {
        // If we don't return the object of MyAmbientCallBack the ambient mode is not working on the app.
        return new MyAmbientCallback();
    }

    private class MyAmbientCallback extends AmbientModeSupport.AmbientCallback {
        @Override
        public void onEnterAmbient(Bundle ambientDetails) {
            System.out.println("entered the ambient mode");
        }

        @Override
        public void onUpdateAmbient() {
            // Handle updating ambient mode
        }

        @Override
        public void onExitAmbient() {
            // Handle exiting ambient mode
        }
    }
}
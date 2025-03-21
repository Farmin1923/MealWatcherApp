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

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Objects;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.io.IOException;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

//Dropbox imports

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.File;


public class StayAwake extends IntentService {

    public StayAwake() {
        super("StayAwake");
    }

    public static int have_accel, have_gyro, have_mag, have_pose, have_linear_accel;         /* flags to sync sensors */
    public static float[] accel, gyro, magneto, quaternion, linear_accel;              /* data from sensors */
    public static long startingTime, currentTime;    /* how long app has been running */
    public static long runningSamples;              /* how much data collected */
    public static long runningTotalMS, runningHour, runningMin, runningSec;    /* display to user */
    private static Classifier classifier;
    private static String fileName;
    private static SensorManager sensorManager;
    private static SensorEventListener sensorCallback;
    private static boolean mIsSensorUpdateEnabled = false;
    private static final String MSG_START_RECORDING = "Record";
    private static final String MSG_UPLOAD = "Upload";
    private static final String CLEAN_UP = "Clean";
    private static long timeStamp;
    private static final int fileFormat = 1; /*0 = csv, 1 = binary(.data extension).*/
    private static String fileExtension = "data";
    private static String fileExtensionText = "log";
    private static Notification mNotification;
    static NotificationManager notificationManager;
    static ArrayList<File> dataFiles = new ArrayList<>();
    static int TotalDataReceived = 0;
    private static Thread fileSentThread;
    static Boolean firstFileToUpload;
    static boolean sessionFinished = false;
    private static LogFunction_Watch logFunction_watch;

    public static PowerManager powerManager;
    private static PowerManager.WakeLock wakeLock;


    /*
    The below uploadHandler and uploadRunnable are used for uploading the sensor data files which
    are not uploaded to phone. This case happens when phone app is closed while the sensor recording
    is one on watch app.
     */
    private final Handler uploadHandler = new Handler();
    private Runnable uploadRunnable;

    @Override
    public void onCreate() {
        logFunction_watch = new LogFunction_Watch();

        super.onCreate();
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
        if (fileFormat == 0) {
            fileExtension = "csv";
        } else {
            fileExtension = "data";
        }
        firstFileToUpload = true;

    }

    @Override
    public void onLowMemory() {
        logFunction_watch.information("Foreground_Activity", "onLowMemory");
        super.onLowMemory();
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
    }

    private void startRecording() {
        mIsSensorUpdateEnabled = true;

        int samplingRate = 10000;
        accel = new float[3];
        gyro = new float[3];
        magneto = new float[3];
        quaternion = new float[4];
        linear_accel = new float[3];
        float[] sensor_reading = new float[16];
        have_accel = 0;
        have_gyro = 0;
        have_mag = 0;
        have_pose = 0;
        have_linear_accel = 0;
        runningSamples = 0;

        // Initializing the classifier which creates a file to which sensor readings are written to.
        classifier = new Classifier();
        fileName = Classifier.fileName;

        sensorManager = (SensorManager) MainActivity_new.applicationContext.getSystemService(Context.SENSOR_SERVICE);
        // create the gyro sensor callback function
        sensorCallback = new SensorEventListener() {
            /*
            Called when there is a new sensor event. Note that "on changed" is somewhat of a
            misnomer, as this will also be called if we have a new reading from a sensor with
            the exact same sensor values (but a newer timestamp)
             */
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (mIsSensorUpdateEnabled) {
                    // callback code here
                    if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
//                        gyro[0] = event.values[0];
//                        gyro[1] = event.values[1];
//                        gyro[2] = event.values[2];
                        sensor_reading[0] = (float) (event.values[0] * 57.3);
                        sensor_reading[1] = (float) (event.values[1] * 57.3);
                        sensor_reading[2] = (float) (event.values[2] * 57.3);
                        have_gyro = 1;
                        timeStamp = event.timestamp;
                    }
                    if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
//                        accel[0] = event.values[0];
//                        accel[1] = event.values[1];
//                        accel[2] = event.values[2];
                        sensor_reading[3] = (float) (event.values[0] / 9.80665);
                        sensor_reading[4] = (float) (event.values[1] / 9.80665);
                        sensor_reading[5] = (float) (event.values[2] / 9.80665);
                        have_accel = 1;
                        timeStamp = event.timestamp;
                    }
                    if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                        magneto[0] = event.values[0];
                        magneto[1] = event.values[1];
                        magneto[2] = event.values[2];
                        sensor_reading[6] = event.values[0];
                        sensor_reading[7] = event.values[1];
                        sensor_reading[8] = event.values[2];
                        have_mag = 1;
                        timeStamp = event.timestamp;
                    }
                    if (have_accel == 1 && have_mag == 1) {
                        float R[] = new float[9];
                        float I[] = new float[9];
                        boolean success = SensorManager.getRotationMatrix(R, I, accel, magneto); //check this https://stackoverflow.com/questions/30780474/android-get-quaternion-data.
                        if (success) {
                            float[] mOrientation = new float[3];
                            SensorManager.getOrientation(R, mOrientation);
                            SensorManager.getQuaternionFromVector(quaternion, mOrientation);
                        }
                        have_pose = 1;
                        timeStamp = event.timestamp;
                        sensor_reading[9] = quaternion[0];
                        sensor_reading[10] = quaternion[1];
                        sensor_reading[11] = quaternion[2];
                        sensor_reading[12] = quaternion[3];
                    }
                    if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
//                        linear_accel[0] = event.values[0];
//                        linear_accel[1] = event.values[1];
//                        linear_accel[2] = event.values[2];
                        sensor_reading[13] = (float) (event.values[0] / 9.80665);
                        sensor_reading[14] = (float) (event.values[1] / 9.80665);
                        sensor_reading[15] = (float) (event.values[2] / 9.80665);
                        have_linear_accel = 1;
                        timeStamp = event.timestamp;
                    }
                    TotalDataReceived ++;
                    if (have_accel == 1 && have_gyro == 1 && have_mag == 1 && have_pose == 1 && have_linear_accel == 1) {
                        currentTime = System.currentTimeMillis();
                        runningTotalMS = currentTime - startingTime;
                        runningHour = runningTotalMS / 3600000;
                        runningMin = (runningTotalMS - (runningHour * 3600000)) / 60000;
                        runningSec = (runningTotalMS - (runningHour * 3600000) - (runningMin * 60000)) / 1000;




                        // call function in Classifier class to add data to buffer and run classifier
                        // see README.txt for explanation of android->our coord sys transform
                        // converting accel and linear accel from m/s^2 to G; converting gyro from rad/s to deg/s
                        // throttling sampling rate to 100 Hz (10 ms)
                        if (runningSamples < runningTotalMS / 10.0) { /* initially (runningTotalMS / 66.667) this will be 15 Hz.*/
                            classifier.newData(sensor_reading, timeStamp);
                            runningSamples++;
                        }
                        have_accel = 0;
                        have_gyro = 0;
                        have_mag = 0;
                        have_pose = 0;
                        have_linear_accel = 0;
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                //leave blank
            }
        };  // end of callback function
        // create the acceleration sensor
        Sensor accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        // create the gyro sensor
        Sensor gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        Sensor linearAcceloSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        Sensor orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        Sensor rotationalMatSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        //register the gyro sensor callback function with the OS
        boolean gyroSensorRegistered = sensorManager.registerListener(sensorCallback, gyroSensor, samplingRate, 10);
        //register the acceleration sensor callback function with the OS
        boolean accelSensorRegistered = sensorManager.registerListener(sensorCallback, accelSensor, samplingRate, 10);
        boolean magSensorRegistered = sensorManager.registerListener(sensorCallback, magSensor, samplingRate, 10);
        boolean linearAcceloRegistered = sensorManager.registerListener(sensorCallback, linearAcceloSensor, samplingRate, 10);
        boolean orientationRegistered = sensorManager.registerListener(sensorCallback, orientationSensor, samplingRate, 10);
        boolean rotationRegistered = sensorManager.registerListener(sensorCallback, rotationalMatSensor, samplingRate, 10);


        startingTime = System.currentTimeMillis();

        MainActivity_new.isInitialUpload = true;
    }

    private void startUploading() {

        // Uploads the files on the watch to phone in a separate thread.
        fileSentThread = new Thread(new Runnable() {


            @Override
            public void run() {
                if(firstFileToUpload){
                    firstFileToUpload = false;
                    System.out.println("Thread is running");
                    scanDir(getExternalFilesDir(null));
                    uploadFilesToPhone(getExternalFilesDir(null),
                            Wearable.getDataClient(getApplicationContext()), false, dataFiles.size());

                }else{
                    uploadFilesToPhone(getExternalFilesDir(null),
                            Wearable.getDataClient(getApplicationContext()), false, dataFiles.size());
                }

            }
        });
        fileSentThread.start();

    }

    static void scanDir(File directory) {
        File[] files = directory.listFiles();

        //TODO: check here.
        for (int index = 0; index < files.length; index++) {
            if(MainActivity_new.isSendFileClicked){
                if(files[index].getName().equals(Classifier.fileName)){
                    System.out.println("Files to not upload although the upload button is clicked: " + files[index].getName());
                    continue;
                }
            }else{
                if(files[index].getName().endsWith(".txt") || files[index].getName().equals(MainActivity_new.logFileName)) {
                    System.out.println("Files to continue: " + files[index].getName());
                    continue;
                }
            }


            dataFiles.add(files[index]);
        }

    }

    static void uploadFilesToPhone(File directory, DataClient dataClient,
                                   Boolean isInitialUpload, int numOfFile) {
        if (dataFiles.size() > 0 && dataFiles.size() == numOfFile) {

            compressAndSend(dataFiles.get(0), directory.getAbsolutePath(), dataClient, numOfFile);
            dataFiles.remove(0);

        } else if(numOfFile == 0 && !isInitialUpload) {
            System.out.println("Sending data ack!");
            MainActivity_new.filesFailedUpload = 0;
            MainActivity_new.sendMessageToMobile("/data_transfer_ack",
                    "all_files_sent", "yes");
            logFunction_watch.information("Watch","Send a message to the mobile: 'All files are sent.'");
            if(wakeLock.isHeld()){
                wakeLock.release();
                logFunction_watch.information("WakeLock","WakeLock is released.");
            }
        }
    }

    static void compressAndSend(File originalFile, String directory, DataClient dataClient,
                                int numOfFile) {
        try {
            // Create an Asset from the file using a FileDescriptor
            File file = new File(directory + "/" + originalFile.getName());
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_ONLY);
            Asset asset = Asset.createFromFd(pfd);

            sendFileToMobile("sensors_file", asset, originalFile.getName(), dataClient,
                    numOfFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void sendFileToMobile(String key, Asset data, String fileName, DataClient dataClient,
                                         int numOfFile) {

        PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/file_path");
        putDataMapReq.getDataMap().putAsset(key, data);
        putDataMapReq.getDataMap().putString("fileName", fileName);
        putDataMapReq.getDataMap().putLong("timestamp", System.currentTimeMillis());
        putDataMapReq.getDataMap().putInt("numOfFiles", numOfFile);
        Task<DataItem> putDataTask = dataClient.putDataItem(putDataMapReq.asPutDataRequest());
        logFunction_watch.information("Watch", "Send a file to the mobile named: " + fileName);

        putDataTask.addOnSuccessListener(new OnSuccessListener<DataItem>() {
            @Override
            public void onSuccess(DataItem dataItem) {
                logFunction_watch.information("Phone","Sending the file: " + fileName +" is successful.");
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                logFunction_watch.error("Phone","Data sent failed! " + e.toString());
                e.printStackTrace();
            }
        });
    }

    private static void cleanUp() {
        if(sessionFinished){
            if (Objects.nonNull(sensorManager)) {

                logFunction_watch.information("Watch","Unregistering the sensor manager in cleanup method");
                //unregister the callbacks for the sensors (otherwise the app wont end)
                sensorManager.unregisterListener(sensorCallback);
                mIsSensorUpdateEnabled = false;
            }
            TotalDataReceived = 0;

            try {
                //call closeClassifier to close the files to which sensor data is written.
                classifier.closeClassifier();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (Objects.nonNull(intent)) {
            final String action = intent.getAction();
            logFunction_watch.information("Watch", "Current action is executing: " + action);

            if (Objects.nonNull(action)) {
                switch (action) {
                    case MSG_START_RECORDING:
                        Thread backgroundThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                // For example, acquire a wake lock

                                // Register the channel with the system
                                notificationManager = getSystemService(NotificationManager.class);
                                NotificationChannel channel = new NotificationChannel("MealWatcher_notification_channel", "MealWatcher", NotificationManager.IMPORTANCE_DEFAULT);
                                notificationManager.createNotificationChannel(channel);

                                // Creating the notification
                                PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, new Intent(getApplicationContext(), StayAwake.class), PendingIntent.FLAG_IMMUTABLE);
                                mNotification = new Notification.Builder(getApplicationContext(), "MealWatcher_notification_channel").setSmallIcon(R.drawable.eatmon_notification_icon).setContentTitle("MealWatcher notification").setContentText("MealWatcher app running").setContentIntent(pendingIntent).setOngoing(true).build();
                                System.out.println("is notification null " + Objects.isNull(mNotification));
                                notificationManager.notify(1, mNotification);
                                startForeground(100, mNotification);

                                wakeLock.acquire();
                                if(wakeLock.isHeld()){
                                    logFunction_watch.information("WakeLock", "WakeLock is acquired");
                                }

                                startRecording();
                            }
                        });

                        // Start the thread
                        backgroundThread.start();

                        break;
                    case MSG_UPLOAD:
                        startUploading();
                        break;
                    case CLEAN_UP:
                        cleanUp();
                        break;
                    default:
                        break;
                }
            }
        }
        return START_STICKY;
    }
}

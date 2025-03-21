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

import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;
import static research.mealwatcher.ControlWatch.record_off_msg;
import static research.mealwatcher.ControlWatch.record_on_msg;
import static research.mealwatcher.MainActivity.applicationContext;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.welie.blessed.BluetoothBytesParser;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.BluetoothPeripheralCallback;
import com.welie.blessed.ConnectionPriority;
import com.welie.blessed.GattStatus;
import com.welie.blessed.HciStatus;
import com.welie.blessed.PhyOptions;
import com.welie.blessed.PhyType;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class ControlRing extends IntentService {

    public long TimeOffset; /* phone-measured time when 1st ring sensor data received, to sync ring data */
    private int TotalDataReceived = 0;
    public int WavePacketTotalBytes, DecodedPacketTotalBytes;
    private FileOutputStream fileOutputStream = null;
    private BufferedOutputStream bufferedOutputStream = null;
    private static BluetoothCentralManager central;
    private static final UUID DIS_SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb");
    private static final UUID MANUFACTURER_NAME_CHARACTERISTIC_UUID = UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb");
    private static final UUID MODEL_NUMBER_CHARACTERISTIC_UUID = UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb");
    private static final UUID CURRENT_TIME_CHARACTERISTIC_UUID = UUID.fromString("00002A2B-0000-1000-8000-00805f9b34fb");
    // UUIDs for the Battery Service (BAS)
    private static final UUID BTS_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb");
    // UUIDs for the Genki Wave ring
    private final UUID WAVE_SERVICE_UUID = UUID.fromString("65e9296c-8dfb-11ea-bc55-0242ac130003");
    private final UUID WAVE_API_CHARACTERISTIC_UUID = UUID.fromString("65e92bb1-8dfb-11ea-bc55-0242ac130003");
    private final UUID WAVE_CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public int b, i, j;
    public long ts;
    public long tsSystem;
    private BluetoothPeripheralCallback peripheralCallback;
    private BluetoothCentralManagerCallback bluetoothCentralManagerCallback;

    private static Timer timer;
    private static TimerTask timerTask;
    public static boolean recordingStarted = false; //Sensor reading is started or not


    private MediaPlayer mediaPlayer;
    public ByteBuffer Convert;
    static int RingConnection = 0; /* 0 not connected, 1 connected.*/
    private static BluetoothPeripheral RingPeripheral = null;
    static String ringSensorFile;
    // Handler to check the ring status
    private final Handler timeoutHandler = new Handler();
    private Runnable timeoutRunnable;
    public static final int MAX_PACKET_LENGTH = 256;

    public byte[] WavePacket = new byte[MAX_PACKET_LENGTH];
    public byte[] DecodedPacket = new byte[MAX_PACKET_LENGTH];
    public byte[] SavePacket = new byte[MAX_PACKET_LENGTH];
    private static Notification mNotification;
    static NotificationManager notificationManager;
    private Handler handler;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLockRing;



    public String[] RingMACs = {"DF:1A:E1:6B:31:36", "C9:4D:A9:38:E0:0E", "C6:14:EC:B8:C1:AD", "FC:D2:41:EB:E4:85",
            "C2:80:76:D8:CC:6F", "CA:3F:92:3B:35:05", "C0:74:E8:2C:DA:6E", "F0:C2:07:21:AB:78",
            "DF:D6:4D:4F:41:AB", "D5:0B:78:60:83:EE", "E9:A7:78:01:F2:64", "E6:FC:B2:D3:1A:B0",
            "E0:B7:28:1F:9D:1D", "F1:BF:DE:33:D4:7A", "DF:55:2D:B5:AB:7E", "E9:12:65:81:C0:BB",
            "DC:59:03:8B:3D:88","EA:3D:7C:6E:2F:84", "C4:61:54:73:81:6E"};

    public ControlRing() {
        super("ControlRing");
    }

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     *
     * @param name Used to name the worker thread, important only for debugging.
     */
    public ControlRing(String name) {
        super(name);
    }

    public boolean recordingFinished = false; // If the recording is finished or not.
    public static boolean gotDisconnected = false; //If the ring got disconnected
    public static boolean isConnectedOnce = false; // While the timerRing in MainActivity will try to reconnect the
    // ring, toast message "Couldn't connect..." will not be shown again and again
    private String filePrefix;
    private static LogFunction logFunction = new LogFunction();

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        System.out.println("in onhandling intent in ring");
    }

    @Override
    public void onCreate() {
        notificationManager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("MealWatcher_notification_channel",
                "MealWatcher",
                NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);

        // Creating the notification
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 1, new Intent(getApplicationContext(), ControlRing.class),
                PendingIntent.FLAG_IMMUTABLE);
        mNotification = new Notification.Builder(getApplicationContext(), "MealWatcher_notification_channel")
                .setSmallIcon(R.drawable.eatmon_notification_icon)
                .setContentTitle("MealWatcher ring notification")
                .setContentText("MealWatcher app running")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        System.out.println("is notification null " + Objects.isNull(mNotification));
        powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLockRing = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MealWatcherRing::RingWakelockTag");
        wakeLockRing.acquire();
        if(wakeLockRing.isHeld()){
            logFunction.information("Ring","WakeLock is acquired");
        }
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        System.out.println("in handling intent in ring");
        boolean isStickyOn = true;
        System.out.println("Value of recording started: " + recordingStarted);

        // Initializing the Handler
        handler = new Handler();

        if (intent != null) {
            final String action = intent.getAction();
            logFunction.information("Ring_MT", "Current action is executing: " + action);
            recordingFinished = intent.getBooleanExtra("finish", false);

            if (Objects.nonNull(action)) {
                switch (action) {
                    case "start_service":
                        System.out.println("Ring service started!");

                        break;
                    case "start_scanning_for_ring":
                        Log.d("BLuetooth","Ring number: " + MainActivity.prev_ring_id_value);

                        if(!recordingStarted){
                            // Register the channel with the system
                            displayNotification();
                            startForeground(101, mNotification);

                            logFunction.information("Ring_MT", "Notification channel created.");
                        }
                        peripheralCallback = new BluetoothPeripheralCallback() {

                            /* callback for receiving an advertisement of a service? */
                            @Override
                            public void onServicesDiscovered(@NotNull BluetoothPeripheral peripheral) {
                                Log.d("BLuetooth","onService Discovered");
                                // request a higher MTU (number of bytes sent at a time); Genki's API requests 247?; iOS always asks for 185? (comment from Blessed)
                                peripheral.requestMtu(247);     // my phone always returns max of 64?
                                // prioritize low latency over low power (alternatives are LOW_POWER, or BALANCED)
                                peripheral.requestConnectionPriority(ConnectionPriority.HIGH);
                                // request 2Mbit physical layer if available (default is 1Mbit)
                                peripheral.setPreferredPhy(PhyType.LE_2M, PhyType.LE_2M, PhyOptions.S2);
                                // read manufacturer and model number from the Device Information Service
                                peripheral.readCharacteristic(DIS_SERVICE_UUID, MANUFACTURER_NAME_CHARACTERISTIC_UUID);
                                peripheral.readCharacteristic(DIS_SERVICE_UUID, MODEL_NUMBER_CHARACTERISTIC_UUID);

                                // turn on notifications for other characteristics
                                peripheral.readCharacteristic(BTS_SERVICE_UUID, BATTERY_LEVEL_CHARACTERISTIC_UUID);
                                peripheral.setNotify(WAVE_SERVICE_UUID, WAVE_API_CHARACTERISTIC_UUID, true);
                                peripheral.setNotify(WAVE_SERVICE_UUID, WAVE_CCCD_UUID, true);
                            }   // end of onServicesDiscovered

                            @Override
                            public void onNotificationStateUpdate(@NotNull BluetoothPeripheral peripheral,
                                                                  @NotNull BluetoothGattCharacteristic characteristic,
                                                                  @NotNull GattStatus status) {
                                if (status == GattStatus.SUCCESS) {
                                    final boolean isNotifying = peripheral.isNotifying(characteristic);
                                }
                            }

                            @Override
                            public void onCharacteristicWrite(@NotNull BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull BluetoothGattCharacteristic characteristic, @NotNull GattStatus status) {
                                String LogMessageText;
                                if (status == GattStatus.SUCCESS) {
                                    LogMessageText = "SUCCESS: writing ";
                                    for (j = 0; j < value.length; j++)
                                        LogMessageText += value[j] + " ";
                                    LogMessageText += " to " + characteristic.getUuid();
                                    Log.d("RINGLOG", LogMessageText + "\n");
                                } else {
                                    LogMessageText = "FAILED: writing " + value + " to " + characteristic.getUuid();
                                    Log.d("RINGLOG", LogMessageText + "\n");
                                }
                            }

                            @Override
                            public void onCharacteristicUpdate(@NotNull BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull BluetoothGattCharacteristic characteristic, @NotNull GattStatus status) {
                                UUID characteristicUUID = characteristic.getUuid();
                                BluetoothBytesParser parser = new BluetoothBytesParser(value);

                                if (status != GattStatus.SUCCESS) return;

                                String LogMessageText;
                                if (characteristicUUID.equals(WAVE_API_CHARACTERISTIC_UUID)) {
                                    /* data packets arrive in multiple parts; this code stitches them together */
                                    /* maintains a growing buffer and searches for 0 (signifies end of COBS-encoded packet) */
                                    for (b = 0; b < value.length; b++) {
                                        WavePacket[WavePacketTotalBytes] = value[b];
                                        WavePacketTotalBytes++;
                                        if (WavePacketTotalBytes >= MAX_PACKET_LENGTH) { /* something went wrong during stitching/transmission; flush buffer and restart */
                                            WavePacketTotalBytes = 0;
                                            continue;
                                        }
                                        if (value[b] != 0)  /* waiting on end-of-packet byte (0 only happens at end of COBS-encoded packet) */
                                            continue;
                                        /* COBS decode packet */
                                        int lastJump = 0;
                                        byte jump = WavePacket[0];
                                        for (i = 1; i < WavePacketTotalBytes - 1; i++) {
                                            if (i == lastJump + jump) {
                                                DecodedPacket[i - 1] = 0;
                                                lastJump = i;
                                                jump = WavePacket[i];
                                            } else
                                                DecodedPacket[i - 1] = WavePacket[i];
                                        }
                                        DecodedPacketTotalBytes = WavePacketTotalBytes - 2;
                                        if (DecodedPacketTotalBytes != 109) {
                                            Log.d("RINGSENSORLOG", "Unknown packet\n");
                                        }
                                        /* check if data stream packet (starts with 3 1 105 0, and is 109 total bytes) */
                                        if (DecodedPacket[0] == 3 && DecodedPacket[1] == 1 &&
                                                DecodedPacket[2] == 105 && DecodedPacket[3] == 0) {
                                            /* Genki packet bytes are 0-3=header, 4-15=gyros, 16-27=accels, 28-39=mags, 40-55="raw pose" (quaternion),
                                             ** 56-71="current pose" (quaternion), 72-83=Euler angles, 84-95=linear accel, 96="peak detected",
                                             ** 97-100="peak velocity, 101-108=timestamp */
                                            /* we pull out 72 bytes:  gyros, accels, magnetos, pose (current), linear accel, timestamp */
                                            if(TotalDataReceived==0){
                                                logFunction.information("Ring","First ring sensor value is received and TotalDataReceived = 0");
                                            }
                                            for (i = 0; i < 36; i++)    /* gyros, accels, mags */
                                                SavePacket[i] = DecodedPacket[i + 4];
                                            for (i = 36; i < 52; i++)   /* pose (current) */
                                                SavePacket[i] = DecodedPacket[i + 20];
                                            for (i = 52; i < 64; i++)   /* linear accel */
                                                SavePacket[i] = DecodedPacket[i + 32];
                                            for (i = 64; i < 72; i++)   /* timestamp */
                                                SavePacket[i] = DecodedPacket[i + 37];

                                            /* synchronize timestamp with phone */
                                            ts = ByteBuffer.wrap(SavePacket, 64, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();    /* timestamp of current sensor reading */
                                            ts /= 1000;   /* ring timestamp units are microsec; convert to millisec */
                                            tsSystem = System.currentTimeMillis();
                                            if (TotalDataReceived == 0) {
                                                TimeOffset = System.currentTimeMillis() - ts;  /* adjust offset by first sensor reading timestamp */
                                                System.out.println("Offset is done!!");
                                            }
                                            ts += TimeOffset;  /* add 1970-Jan-1 offset to synchronize */

                                            Convert = (ByteBuffer) ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(ts).rewind();
                                            Convert.get(SavePacket, 64, 8);

                                            // Adding 8 extra bytes to save the timestamp which is used to analyse drift.
                                            //ts = System.currentTimeMillis();
                                            Convert = (ByteBuffer) ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(tsSystem).rewind();
                                            Convert.get(SavePacket, 72, 8);
                                            //Convert.get(SavePacket, 36, 8);

                                            /* write data to file */
                                            try {
                                                //bufferedOutputStream.write(SavePacket, 0, 72);
                                                bufferedOutputStream.write(SavePacket, 0, 80);

                                            } catch (IOException e) {
                                                logFunction.error("Ring_Fil", "Writing failed, showing error: " + e.toString());
                                                e.printStackTrace();
                                            }
                                            //dataReceived = 1;
                                            TotalDataReceived++;
                                        }
                                        WavePacketTotalBytes = 0;             // reset building new packet
                                    }
                                } else if (characteristicUUID.equals(WAVE_CCCD_UUID)) {
                                    LogMessageText = "update CCCD " + value + " from " + characteristic.getUuid();
                                    Log.d("RINGLOG", LogMessageText + "\n");
                                } else if (characteristicUUID.equals(CURRENT_TIME_CHARACTERISTIC_UUID)) {
                                    Date currentTime = parser.getDateTime();
                                    LogMessageText = "date/time " + currentTime;
                                    Log.d("RINGLOG", LogMessageText + "\n");
                                } else if (characteristicUUID.equals(BATTERY_LEVEL_CHARACTERISTIC_UUID)) {
                                    int batteryLevel = parser.getIntValue(FORMAT_UINT8);
                                    LogMessageText = "received battery level " + batteryLevel;
                                    Log.d("RINGLOG", LogMessageText + "\n");
                                } else if (characteristicUUID.equals(MANUFACTURER_NAME_CHARACTERISTIC_UUID)) {
                                    String manufacturer = parser.getStringValue(0);
                                    LogMessageText = "received manufacturer " + manufacturer;
                                    Log.d("RINGLOG", LogMessageText + "\n");
                                } else if (characteristicUUID.equals(MODEL_NUMBER_CHARACTERISTIC_UUID)) {
                                    String modelNumber = parser.getStringValue(0);
                                    LogMessageText = "received modelnumber " + modelNumber;
                                    Log.d("RINGLOG", LogMessageText + "\n");
                                }
                            }

                            @Override
                            public void onMtuChanged(@NotNull BluetoothPeripheral peripheral, int mtu, @NotNull GattStatus status) {
                                String LogMessageText = "new MTU set to " + mtu;
                                Log.d("RINGLOG", LogMessageText + "\n");
                            }

                        };      // end of peripheralCallback

                        /* set of callbacks for peripheral scanning, connecting, disconnecting */
                        bluetoothCentralManagerCallback = new BluetoothCentralManagerCallback() {
                            /* callback for receiving a connection accepted from a peripheral */
                            @Override
                            public void onConnectedPeripheral(@NotNull BluetoothPeripheral peripheral) {
                                timeoutHandler.removeCallbacks(timeoutRunnable);
                                RingConnection = 1;
                                RingPeripheral = peripheral;  /* copy to global variable; used to disconnect */
                                MainActivity.connectToRing.setChecked(true);
                                MainActivity.turnOnRing.setChecked(true);
                                MainActivity.turnOffRing.setChecked(false);
                                MainActivity.setButtonText(MainActivity.ringStatusButton, record_on_msg);
                                MainActivity.setButtonColor(MainActivity.ringStatusButton, Color.parseColor("#008000"));
                                MainActivity.ringRecordingState = "true";
                                gotDisconnected = false;
                                isConnectedOnce = true;


                                File directory = getExternalFilesDir(null);
                                if(MainActivity.prev_pid_value.length()<5){
                                    filePrefix   = "00000".substring(MainActivity.prev_pid_value.length()) + MainActivity.prev_pid_value + "-"; // This will ensure that the folder name is four digit based on the PID value
                                }else{
                                    filePrefix = MainActivity.prev_pid_value + "-";
                                }
                                final LocalDateTime now = LocalDateTime.now();
                                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
                                logFunction.information("Ring_BT", "Connected to the ring." );
                                if(!recordingStarted){
                                    TotalDataReceived = 0;
                                }
                                recordingStarted = true;


                                filePrefix += now.format(formatter) + "-ring.data";
                                ringSensorFile = filePrefix;
                                String fileName = directory + "/" + filePrefix;
                                logFunction.information("Ring_Fil","Ring file created.");

                                try {
                                    fileOutputStream = new FileOutputStream(fileName);
                                    bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
                                    logFunction.information("Ring_Fil","Buffered output stream opened successfully for writing data.");
                                } catch (FileNotFoundException e) {
                                    logFunction.error("Ring_Fil","Buffered output stream did not opened, and reason: " + e.toString());
                                    throw new RuntimeException(e);
                                }
                                }

                            /* callback for receiving an advertisement from a peripheral */
                            @Override
                            public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
                                /* stop scanning and connect if Wave ring found */
                                String LogMessageText = "DiscoveredPeripheral " + peripheral.getName() + " " + peripheral.getAddress();
                                if ((MainActivity.prev_ring_id_value > 0 &&
                                        peripheral.getAddress().equals(RingMACs[MainActivity.prev_ring_id_value]))) {
                                    logFunction.information("Ring_BT","Discovered the required ring: " + (MainActivity.prev_ring_id_value+1));
                                    central.stopScan();
                                    /* TO DO -- should this be done in a background service? */
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            logFunction.information("Ring_BT","Getting connected to the ring");
                                            central.connectPeripheral(peripheral, peripheralCallback);

                                        }
                                    }).start();
                                    WavePacketTotalBytes = 0;
                                }
                            }   // end of onDiscoveredPeripheral

                            /* callback for receiving a disconnect from a peripheral */
                            @Override
                            public void onDisconnectedPeripheral(@NotNull BluetoothPeripheral peripheral, @NotNull HciStatus status) {
                                try {

                                    stopForeground(STOP_FOREGROUND_REMOVE);

                                    recordingStarted = false;

                                    MainActivity.connectToRing.setChecked(false);
                                    MainActivity.turnOnRing.setChecked(false);
                                    MainActivity.turnOffRing.setChecked(true);

                                    MainActivity.setButtonText(MainActivity.ringStatusButton, record_off_msg);
                                    MainActivity.setButtonColor(MainActivity.ringStatusButton, Color.parseColor("#FF0000"));
                                    MainActivity.ringRecordingState = "false";

                                    if (Objects.nonNull(bufferedOutputStream)) {
                                        bufferedOutputStream.close();
                                        logFunction.information("Ring_MT", "Closing the file output stream.");
                                    }
                                    fileOutputStream.close();

                                    /* Reason if the ring doesn't disconnect successfully:

                                    CONNECTION_TIMEOUT: Indicates that the disconnection happens due to the peripheral being
                                    out of range or not responding.
                                    REMOTE_USER_TERMINATED_CONNECTION: The remote device (peripheral) initiated the disconnection.
                                    CONNECTION_TERMINATED_BY_LOCAL_HOST: The local host (Android device) terminated the connection,
                                    not necessarily due to a user action but possibly due to an internal condition or error.*/

                                    if(status == HciStatus.SUCCESS){
                                        cancelNotification();
                                        playAudio("stop");
                                        logFunction.information("Ring_MT","Ring file closed successfully.");
                                        logFunction.information("Ring_BT", "Peripheral disconnected successfully: " + peripheral.getName());
                                        showToast(applicationContext, "Ring got disconnected successfully", 0);
                                    }else{
                                        playAudio("start");
                                        gotDisconnected = true;
                                        logFunction.error("Ring_BT","Ring got disconnected");
                                        logFunction.error("Ring_BT", "Reason for disconnection: " + status);
                                        Log.e("BLE", "Reason for disconnection: " + status);
                                        showToast(applicationContext, "Disconnected from ring, Please try to connect it again.", 1);
                                    }



                                } catch (IOException e) {
                                    logFunction.error("Ring_BT", "Disconnection error: " + e.toString() );
                                    e.printStackTrace();
                                }
                                RingConnection = 0;
                            }
                        };      // end of bluetoothCentralManagerCallback
                        if (RingConnection == 0) {
                            logFunction.information("Ring_BT","Ring is not connected so starting scanning for ring!");
                            // create BluetoothCentral to receive advertisement/connected/disconnected callbacks on the main thread
                            central = new BluetoothCentralManager(getApplicationContext(),
                                    bluetoothCentralManagerCallback, new Handler(Looper.getMainLooper()));

                            central.scanForPeripherals();
                            timeoutRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    logFunction.information("Ring_BT","Scanned for 5 secs, couldn't discover the required ring.");
                                    central.stopScan();
                                    if(!isConnectedOnce){
                                        showToast(applicationContext, "Couldn't connect to ring, please try again!", 1);
                                    }

                                }
                            };

                            timeoutHandler.postDelayed(timeoutRunnable, 5000);
                        }
                        break;
                    case "disconnect_from_ring":
                        if (Objects.nonNull(RingPeripheral)) {
                            central.cancelConnection(RingPeripheral);
                        }
                }
            }
        }

        /*
        START_STICKY should be returned because the service is explicitly telling the system to
        restart it if it gets terminated by the operating system due to resource constraints (e.g., low memory).
        When sufficient resources become available again, the system will automatically restart
        the service and call onStartCommand() with a null Intent (indicating that no new Intent is
        being delivered).

        The START_STICKY behavior is particularly useful for services that perform background tasks
         or handle long-running operations. By using this return value, you ensure that the service
         keeps running and continues its work even after the system temporarily kills it to free up
          resources.
         */
        if (isStickyOn) {
            return START_STICKY;
        }
        return flags;
    }

    //For increasing the text size of the toast message
    public void showToast(Context context, String message, int duration) {
        // Use the context to create LayoutInflater and show custom toast
        Context appContext = getApplicationContext();
        LayoutInflater inflater = LayoutInflater.from(appContext);
        View layout = inflater.inflate(R.layout.toast_layout, null);

        TextView text = layout.findViewById(R.id.toast_text);
        text.setText(message);

        Toast toast = new Toast(context);
        if (duration == 0) {
            toast.setDuration(Toast.LENGTH_SHORT);
        } else {
            toast.setDuration(Toast.LENGTH_LONG);
        }

        toast.setView(layout);
        toast.show();
    }
    private void playAudio(String s) {
        try {
            // Set data source asynchronous
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm);
            mediaPlayer.setVolume(0.6f, 0.6f); // Set the volume to 60%
            if(s.equals("start")){
                System.out.println("Playing audio: " + s);
                mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mediaPlayer) {
                        // Start playing audio when prepared
                        mediaPlayer.start();
                        if(mediaPlayer.isPlaying()){
                            logFunction.information("Ring_BT", "Alerting the user that the ring got disconnected in the middle of recording");
                        }

                        // Pause audio after 3 seconds
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mediaPlayer.pause();
                                mediaPlayer.seekTo(0);
                            }
                        }, 3000); // 3000 milliseconds = 3 seconds
                    }
                });
                mediaPlayer.prepareAsync();
            }else{
                mediaPlayer.release();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void displayNotification() {
        notificationManager.notify(2, mNotification);
    }

    static void cancelNotification() {
        System.out.println("Cancelling the ring notification");
        notificationManager.cancel(2);
    }

    @Override
    public void onLowMemory() {
        logFunction.error("Foreground_Ring", "onLowMemory; Ring sensor sensor recording is going to be killed.");
        super.onLowMemory();
    }

    public void onDestroy() {
        logFunction.error("Foreground_Ring", "onDestroy.");
        if(wakeLockRing.isHeld()){
            wakeLockRing.release();
            logFunction.error("Ring", "Wakelock is released but the recording is ongoing");
            ControlWatch.sendDataItem("/phone_status", "state", "onStopDoze");
        }


        super.onDestroy();
    }


}

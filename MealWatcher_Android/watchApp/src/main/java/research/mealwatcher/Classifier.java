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

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * This code implements the eating detector of Dong et. al.
 * The implementation allows real-time processing.  It reads
 * one sensor reading at a time from accelerometers and gyroscopes,
 * smoothest it, checks for peaks, and upon finding a peak
 * classifies the peak-to-peak period of data.
 * *
 * The memory footprint is kept small by maintaining a
 * buffer of data since the last peak, and upon classifying a
 * peak-to-peak period, shifting the buffer to start at the new peak.
 * This code is intended to integrate with Java code for an
 * Android smartwatch within an Android Studio project.
 * More details about the whole design can be found in its README.
 * *
 * Note:  max time between analyses is 1 hour even if peak not found
 * *
 * The java code consists of 3 functions:
 * (1) InitClassifier()
 * *	allocates memory for buffers
 * *	opens file for writing data
 * *	calculates smoothing kernel
 * *	initializes counters
 * (2) NewData()
 * *	takes as input new sensor reading
 * *	writes data to file
 * *	looks for peaks
 * *	if peak found, analyze
 * *	if analysis = eating, return 1
 * *	all other paths return 0
 * (3) CloseClassifier()
 * *	releases memory
 * *	closes data file
 */

public class Classifier {
    // public static int MAX_SIZE = 72;
    public static int MAX_SIZE = 80;
    private static final int SAVE_FILE_FORMAT = 0; // 0 for byte data and 1 for csv.

    static BufferedWriter fptw; /* for writing live sensor data to file */
    private FileOutputStream fileOutputStream;
    static BufferedOutputStream bufferedOutputStream;
    private BufferedWriter fpte; /* for writing events file */
    private int totalData;
    private long timeOffset;
    // Accessed in StayAwake.java
    static String fileName;

    private static LogFunction_Watch logFunction_watch;

    public Classifier() {
        // Initializes all the file pointers and buffer output streams.
        init();

        totalData = 0; /* amount of data written to file */
    }

    public void init() {
        logFunction_watch = new LogFunction_Watch();
        /* get the localtime to use for filenames and events */
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
        String filePrefix = now.format(formatter) + "-watch";

        /* Should be checking that file opened successfully.
         ** If not, app will just crash with no feedback. */
        String csvFileName = "", binFileName = "";
        if (SAVE_FILE_FORMAT == 0) {
            binFileName = "storage/emulated/0/Android/data/research.mealwatcher/files/" + filePrefix + ".data";
            logFunction_watch.information("Watch","Created binary file with name = " + filePrefix + ".data");
            fileName = filePrefix + ".data";
        } else {
            csvFileName = "storage/emulated/0/Android/data/research.mealwatcher/files/" + filePrefix + ".csv";
            logFunction_watch.information("Watch","Created CSV file with name = " + filePrefix + ".csv");
            fileName = filePrefix + ".csv";
        }
        try {
            if(SAVE_FILE_FORMAT == 0) {
                fileOutputStream = new FileOutputStream(binFileName);
                bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
            } else {
                fptw = new BufferedWriter(new FileWriter(csvFileName));
            }
            fpte = new BufferedWriter(new FileWriter("storage/emulated/0/Android/data/research.mealwatcher/files/" + filePrefix + "-events.txt"));

            fpte.write("START " + now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            fpte.newLine();
            fpte.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void newData(float[] sensor_reading, long timestamp) {
        long timeStampSystem = System.currentTimeMillis();
        byte[] SavePacket;
        ByteBuffer Convert;

        SavePacket = new byte[MAX_SIZE];

        timestamp = (timestamp / 1000000); // Converting nanoseconds into milliseconds.
        // This offset is needed as we are getting timestamp from the device boot time and not the unix standard time.
        if (totalData == 0) {
            timeOffset = System.currentTimeMillis() - timestamp;
        }
        timestamp += timeOffset;
//        System.out.println("timeStamp after adding the offset = " + timestamp);

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
        String time_debug = now.format(formatter);
        LocalDateTime localDateTime = LocalDateTime.parse(time_debug, formatter);

        // Convert LocalDateTime to a long (epoch seconds)
        long epochSeconds = localDateTime.toEpochSecond(java.time.ZoneOffset.UTC);

        int length = 4, offset = 0;
        // Writing all sensor readings.
        for(int i=0; i<16; i++) {
            Convert = (ByteBuffer) ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(sensor_reading[i]).rewind();
            Convert.get(SavePacket, offset, length);
            offset = offset + 4;
        }
        // Writing timestamp.
        Convert = (ByteBuffer) ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(timestamp).rewind();
        Convert.get(SavePacket, offset, length+4);

        Convert = (ByteBuffer) ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(timeStampSystem).rewind();
        Convert.get(SavePacket, 72, 8);


        try {
            if (SAVE_FILE_FORMAT == 0) {
                bufferedOutputStream.write(SavePacket, 0, MAX_SIZE);
                bufferedOutputStream.flush();
            } else {
                /*
                Writing to a text file with comma separated format.
                 */
                for(int i=0; i<16; i++) {
                    fptw.write(Float.toString(sensor_reading[i]));
                    fptw.write(',');
                }
                fptw.write(Long.toString(timestamp));
                fptw.write(',');
                // Set the time zone to Eastern Standard Time (EST)
                ZoneId estZoneId = ZoneId.of("America/New_York");

                // Get the current time in the EST time zone
                ZonedDateTime currentTimeInEST = ZonedDateTime.now(estZoneId);

                fptw.write(String.valueOf(currentTimeInEST.toLocalTime()));
                fptw.newLine();
                fptw.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        totalData++;
    }

    public void closeClassifier() throws IOException {
        System.out.println("in close classifier");
        /* get the localtime and write to events file */
        LocalDateTime now = LocalDateTime.now();
        fpte.write("END " + now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        fpte.newLine();
        try {
            /* file pointer writing live sensor data to file */
            if(SAVE_FILE_FORMAT == 0) {
                System.out.println("Closing the buffered output stream");
                fileOutputStream.flush();
                bufferedOutputStream.flush();

                fileOutputStream.close();
                bufferedOutputStream.close();

                logFunction_watch.information("File", "Closed the bufferedOutputStream");
            } else {
                fptw.flush();
                fptw.close();
                logFunction_watch.information("File","Closed the csv file pointers");
            }
            fpte.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
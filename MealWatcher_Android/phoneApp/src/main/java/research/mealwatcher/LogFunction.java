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

import static research.mealwatcher.MainActivity.applicationContext;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogFunction {
    public File logFile;
    static FileOutputStream fos;
    public String logFileName;

    public static Context applicationContext;


    public LogFunction() {
    }

    public void setLogFile(File logFile) {
        this.logFile = logFile;
    }


    public Context getApplicationContext() {
        return applicationContext;
    }

    public void setApplicationContext(Context applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void information(String sourceLevels, String content){

        LocalDateTime now = LocalDateTime.now();
        String time = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS"));
        String text = time + "," + "I" + "," + sourceLevels + "," + content;
        try {
            fos.write(text.getBytes());
            fos.write("\n".getBytes());
            fos.flush();
        } catch (Exception exception) {
            exception.printStackTrace();
        }

    }

    public void error(String sourceLevels, String content){

        LocalDateTime now = LocalDateTime.now();
        String time = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS"));
        String text = time + "," + "E" + "," + sourceLevels + "," + content;
        try {
            fos.write(text.getBytes());
            fos.write("\n".getBytes());
            fos.flush();
        } catch (Exception exception) {
            exception.printStackTrace();
        }

    }
    public void debug(String sourceLevels, String content){

        LocalDateTime now = LocalDateTime.now();
        String time = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS"));
        String text = time + "," + "D" + "," + sourceLevels + "," + content;
        try {
            fos.write(text.getBytes());
            fos.write("\n".getBytes());
            fos.flush();
        } catch (Exception exception) {
            exception.printStackTrace();
        }

    }


    public void openFile(){
        try {
            fos = new FileOutputStream(logFile, true);
            fos.flush(); // Ensuring data is written out immediately
        } catch (Exception exception) {
            exception.printStackTrace();
            error("File", "Output stream for log file is not opened");
        }

    }
    public void closeFile(){
        try {
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();

        }
    }

    public void fileRename(String fileName){
        // Create a File object with the new file name
        File newFile = new File(logFile.getParent(), fileName);
        boolean renamed = logFile.renameTo(newFile);
        // Check if renaming was successful
        if (renamed) {
            logFile = new File(applicationContext.getExternalFilesDir(null), fileName);
            logFileName = fileName;
        } else {
            error("File", "Failed to rename the file.");
        }
    }

    public int failedToUpload() {

        // get list of files and upload
        int filesFailedUpload = 0;
        File[] files = applicationContext.getExternalFilesDir(null).listFiles();

        boolean filesExist = false;
        if (files.length > 0) {
            filesExist = true;
        }

        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().equals("authfile.json") || files[i].getName().endsWith(".txt")) {
                continue;       // skip this file
            }
            if(files[i].getName().equals("ImagesFolder")){
                continue;
            }if(files[i].getName().endsWith(".log")){
                continue;
            }

            else {
                filesFailedUpload++; // let user know if any uploads fail
            }
        }
        return filesFailedUpload;
    }
}

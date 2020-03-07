package com.gibbon.compactmultidex;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 * 串行处理
 */
public class SerialMultiDexExtractor extends DexElementsExtractor {

    @Override
    protected List<ExtractedDex> performExtractions(File sourceApk, File dexDir) throws IOException {
        long start = SystemClock.uptimeMillis();
        final String extractedFilePrefix = sourceApk.getName() + EXTRACTED_NAME_EXT;

        // Ensure that whatever deletions happen in prepareDexDir only happen if the zip that
        // contains a secondary dex file in there is not consistent with the latest apk.  Otherwise,
        // multi-process race conditions can cause a crash loop where one process deletes the zip
        // while another had created it.
        prepareDexDir(dexDir, extractedFilePrefix);
        if((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_EXTRACT_RAW_DEX_NOT_ZIP) != 0){
            prepareDexDir(MultiDex.getOptDexDir(dexDir), extractedFilePrefix);
        }

        List<ExtractedDex> files = new ArrayList<ExtractedDex>();

        final ZipFile apk = new ZipFile(sourceApk);
        try {

            int secondaryNumber = 2;

            ZipEntry dexFile = apk.getEntry(DEX_PREFIX + secondaryNumber + DEX_SUFFIX);
            while (dexFile != null) {
                long start2 = SystemClock.uptimeMillis();
                String suffix;
                if((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_EXTRACT_RAW_DEX_NOT_ZIP) != 0){
                    suffix = DEX_SUFFIX;
                }else {
                    suffix = EXTRACTED_SUFFIX;
                }
                String fileName = extractedFilePrefix + secondaryNumber + suffix;
                ExtractedDex extractedFile = new ExtractedDex(dexDir, fileName);
                files.add(extractedFile);

                Log.i(TAG, "Extraction is needed for file " + extractedFile);
                int numAttempts = 0;
                boolean isExtractionSuccessful = false;
                while (numAttempts < MAX_EXTRACT_ATTEMPTS && !isExtractionSuccessful) {
                    numAttempts++;

                    // Create a zip file (extractedFile) containing only the secondary dex file
                    // (dexFile) from the apk.
                    extract(apk, dexFile, extractedFile, extractedFilePrefix);

                    // Read zip crc of extracted dex
                    try {
                        long start3 = SystemClock.uptimeMillis();
                        if((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_EXTRACT_RAW_DEX_NOT_ZIP) != 0){
                            if((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_NOT_DEX_CRC)  != 0){
                                Log.i(TAG, suffix.substring(1)  + " \"" + extractedFile.getAbsolutePath() + "\" crc verify disabled");
                            }else {
                                Log.i(TAG, suffix.substring(1)  + " \"" + extractedFile.getAbsolutePath() + "\" crc verify enabled");
                                extractedFile.crc = getDexCrc(extractedFile);
                            }
                        }else {
                            if((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_NOT_DEX_CRC)  != 0){
                                Log.i(TAG, suffix.substring(1)  + " \"" + extractedFile.getAbsolutePath() + "\" crc verify disabled");
                            }else {
                                Log.i(TAG, suffix.substring(1)  + " \"" + extractedFile.getAbsolutePath() + "\" crc verify enabled");
                                extractedFile.crc = getZipCrc(extractedFile);
                            }
                        }
                        isExtractionSuccessful = true;
                        Log.i(TAG, "\"" + extractedFile.getAbsolutePath() + "\" , getCrc need " +  (SystemClock.uptimeMillis() - start3) + "ms");
                    } catch (IOException e) {
                        isExtractionSuccessful = false;
                        Log.w(TAG, "Failed to read crc from " + extractedFile.getAbsolutePath(), e);
                    }
                    Log.i(TAG, "extractDex apk Entry(classes" + secondaryNumber+ ".dex) to \"" + extractedFile.getAbsolutePath() + "\" need " + (SystemClock.uptimeMillis() - start2) + "ms");
                    // Log size and crc of the extracted zip file
                    Log.i(TAG, "Extraction " + (isExtractionSuccessful ? "succeeded" : "failed") +
                            " - length " + extractedFile.getAbsolutePath() + ": " +
                            extractedFile.length() + " - crc: " + extractedFile.crc);
                    if (!isExtractionSuccessful) {
                        // Delete the extracted file
                        extractedFile.delete();
                        if (extractedFile.exists()) {
                            Log.w(TAG, "Failed to delete corrupted secondary dex '" +
                                    extractedFile.getPath() + "'");
                        }
                    }
                }
                if (!isExtractionSuccessful) {
                    throw new IOException("Could not create zip file " +
                            extractedFile.getAbsolutePath() + " for secondary dex (" +
                            secondaryNumber + ")");
                }
                secondaryNumber++;
                dexFile = apk.getEntry(DEX_PREFIX + secondaryNumber + DEX_SUFFIX);
            }
        } finally {
            try {
                apk.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close resource", e);
            }
        }
        Log.i(TAG, "extract apk " + sourceApk.getAbsolutePath() + " need " + (SystemClock.uptimeMillis() - start) + "ms");
        return files;
    }
}

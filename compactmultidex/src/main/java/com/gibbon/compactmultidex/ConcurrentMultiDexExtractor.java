package com.gibbon.compactmultidex;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 * 并行处理
 */
public class ConcurrentMultiDexExtractor extends DexElementsExtractor {

    @Override
    protected List<ExtractedDex> performExtractions(File sourceApk, File dexDir) throws IOException {
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
        List<ZipEntryWrapper> entryWrappers = new ArrayList<>();

        final ZipFile apk = new ZipFile(sourceApk);
        try {
            int secondaryNumber = 2;
            int dexCount = 0;
            ZipEntry dexFile = apk.getEntry(DEX_PREFIX + secondaryNumber + DEX_SUFFIX);
            while (dexFile != null) {
                String suffix;
                if((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_EXTRACT_RAW_DEX_NOT_ZIP) != 0){
                    suffix = DEX_SUFFIX;
                }else {
                    suffix = EXTRACTED_SUFFIX;
                }
                String fileName = extractedFilePrefix + secondaryNumber + suffix;
                ExtractedDex extractedFile = new ExtractedDex(dexDir, fileName);
                files.add(extractedFile);
                entryWrappers.add(new ZipEntryWrapper(secondaryNumber , dexFile, extractedFile));
                secondaryNumber++;
                dexFile = apk.getEntry(DEX_PREFIX + secondaryNumber + DEX_SUFFIX);
            }

            int threadMaxSizeLimit = 0;
            if(MultiDex.CPU_COUNT == 1){
                threadMaxSizeLimit = MultiDex.CPU_COUNT * 2 + 1;
            }else if(MultiDex.CPU_COUNT == 2){
                threadMaxSizeLimit = MultiDex.CPU_COUNT * 2;
            }else {
                threadMaxSizeLimit = MultiDex.CPU_COUNT + 1;
            }
            int extraDexCount = entryWrappers.size();
            if(extraDexCount <= 0){
                return files;
            }
            int threadSize = 0;
            if(extraDexCount > threadMaxSizeLimit){
                threadSize = threadMaxSizeLimit;
            }else {
                threadSize = extraDexCount - 1;
            }
            Log.i(TAG, "cpu count: " + MultiDex.CPU_COUNT + ", threadMaxSizeLimit: " + threadMaxSizeLimit + ", extraDexCount: " + extraDexCount + ", final thread size: " + threadSize);

            List<ZipEntryGroup> groups = makeGroupList(threadSize + 1, entryWrappers);
            int size = groups.size();
            Log.i(TAG, "group size: " + size);
            FutureTask<Boolean>[] futureTasks = new FutureTask[size];
            for (int i =0; i < size; i++){
                ZipEntryGroup group = groups.get(i);
                Log.i(TAG, "group[" + i + "] = " + group);
                futureTasks[i] = new FutureTask<Boolean>(new ExtractCallable(apk, group, extractedFilePrefix, i));
            }
            Log.i(TAG, "Extracting apk");
            long start = SystemClock.uptimeMillis();
            for (int i = 1; i < size; i++){
                new Thread(futureTasks[i]).start();
            }
            if(futureTasks.length > 0){
                futureTasks[0].run();
            }

            try{
                for (int i = 0; i < size; i++){
                    Log.i(TAG, "extract task" + i);
                    Boolean success = futureTasks[i].get();
                    if(!success){
                        Log.i(TAG, "extract task" + i + " excute failed");
                        throw new IOException("extract apk failed");
                    }else {
                        Log.i(TAG, "extract task" + i + " excute success");
                    }
                }
            }catch (Exception e){
                Log.i(TAG, "Extracting apk failed, need " + (SystemClock.uptimeMillis() - start) + "ms");
                throw new IOException("extract apk failed");
            }
            Log.i(TAG, "Extracting apk success, need " + (SystemClock.uptimeMillis() - start) + "ms");
        } finally {
            try {
                apk.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close resource", e);
            }
        }

        return files;
    }

    @Override
    protected void extract(ZipFile apk, ZipEntry dexFile, File extractTo, String extractedFilePrefix) throws IOException, FileNotFoundException {
        if((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_EXTRACT_RAW_DEX_NOT_ZIP) != 0){
            InputStream in = apk.getInputStream(dexFile);
            BufferedOutputStream out = null;
            // Temp files must not start with extractedFilePrefix to get cleaned up in prepareDexDir()
            File tmp = File.createTempFile("tmp-" + extractedFilePrefix, DEX_SUFFIX,
                    extractTo.getParentFile());
            Log.i(TAG, "Extracting " + tmp.getPath());
            try {
                out = new BufferedOutputStream(new FileOutputStream(tmp));
                try {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int length = in.read(buffer);
                    while (length != -1) {
                        out.write(buffer, 0, length);
                        length = in.read(buffer);
                    }
                } finally {
                    out.close();
                }
                if (!tmp.setReadOnly()) {
                    throw new IOException("Failed to mark readonly \"" + tmp.getAbsolutePath() +
                            "\" (tmp of \"" + extractTo.getAbsolutePath() + "\")");
                }
                Log.i(TAG, "Renaming to " + extractTo.getPath());
                if (!tmp.renameTo(extractTo)) {
                    throw new IOException("Failed to rename \"" + tmp.getAbsolutePath() +
                            "\" to \"" + extractTo.getAbsolutePath() + "\"");
                }
            } finally {
                closeQuietly(in);
                tmp.delete(); // return status ignored
            }
        }else {
            super.extract(apk, dexFile, extractTo, extractedFilePrefix);
        }
    }

    private List<ZipEntryGroup> makeGroupList(int groupSize, List<ZipEntryWrapper> entryWrappers){
        List<ZipEntryGroup> groups =  averageGroup(groupSize, entryWrappers);
        Collections.sort(groups);
        return groups;
    }

    private List<ZipEntryGroup> averageGroup(int groupSize, List<ZipEntryWrapper> input) {
        ArrayList<ZipEntryGroup> resultList = new ArrayList<>(groupSize);
        if (groupSize > input.size() || groupSize <= 0) {
            return resultList;
        } else if (groupSize == input.size()) {
            for (ZipEntryWrapper entryWrapper : input) {
                ZipEntryGroup group = new ZipEntryGroup();
                group.add(entryWrapper);
                resultList.add(group);
            }
            return resultList;
        }

        for (int i = 0; i < groupSize; i++) {
            resultList.add(new ZipEntryGroup());
        }

        List<ZipEntryWrapper> sortedInput = input;
        int inputLen = input.size();
        Collections.sort(sortedInput);

        // 从最大的开始填充到结果中
        for (int i = 0; i < groupSize; i++) {
            resultList.get(i).add(sortedInput.get(inputLen - 1 - i));
        }
        // 从大到小遍历剩下的数字
        for (int i = inputLen - 1 - groupSize; i >= 0; i--) {
            ArrayList<Long> tempSum = new ArrayList<>(groupSize);
            for (ZipEntryGroup group : resultList) {
                tempSum.add(getSum(group) + sortedInput.get(i).getSize());
            }
            int minIndex = findMinIndex(tempSum); // 找出结果最小的那个分组
            resultList.get(minIndex).add(sortedInput.get(i)); // 将当前数加入那个分组
        }

        return resultList;
    }

    /**
     * @return The index of the min member
     */
    private static int findMinIndex(ArrayList<Long> list) {
        int res = 0;
        long t = Long.MAX_VALUE;
        for (int index = 0; index < list.size(); index++) {
            if (list.get(index) < t) {
                t = list.get(index);
                res = index;
            }
        }
        return res;
    }

    /**
     * @return Sum of group members
     */
    private static long getSum(ZipEntryGroup group) {
        return group.getSize();
    }

    private class ZipEntryGroup implements Comparable<ZipEntryGroup>{

        private final List<ZipEntryWrapper> entryWrappers ;

        public ZipEntryGroup() {
            this.entryWrappers = new ArrayList<>();
        }

        public boolean add(ZipEntryWrapper entryWrapper){
            return entryWrappers.add(entryWrapper);
        }

        @Override
        public int compareTo( ZipEntryGroup zipEntryGroup) {
            // 降序排列
            return getSize() - zipEntryGroup.getSize() > 0 ? -1 : 1;
        }

        public long getSize(){
            long size = 0;
            for (ZipEntryWrapper entryWrapper : entryWrappers){
                size += entryWrapper.getSize();
            }
            return size;
        }

        public List<ZipEntryWrapper> getEntryWrappers() {
            return entryWrappers;
        }

        @Override
        public String toString() {
            StringBuffer stringBuffer = new StringBuffer();
            stringBuffer.append("{size:");
            stringBuffer.append(getSize());
            stringBuffer.append(",");
            stringBuffer.append("dexs:");
            stringBuffer.append("{");
            int size = entryWrappers.size();
            for (int i = 0; i < size; i++){
                ZipEntryWrapper zipEntryWrapper = entryWrappers.get(i);
                stringBuffer.append("{");
                stringBuffer.append("size:");
                stringBuffer.append(zipEntryWrapper.getSize());
                stringBuffer.append(",");
                stringBuffer.append("dex:");
                stringBuffer.append("classes" + zipEntryWrapper.getNumber() + ".dex");
                stringBuffer.append("}");
                if(i != size - 1){
                    stringBuffer.append(",");
                }
            }
            stringBuffer.append("}");
            stringBuffer.append("}");
            return stringBuffer.toString();
        }
    }

    private class ZipEntryWrapper implements  Comparable<ZipEntryWrapper> {

        private final int number;
        private final ZipEntry zipEntry;
        private final ExtractedDex extractedDex;
        private final long size;

        public ZipEntryWrapper(int number, ZipEntry zipEntry, ExtractedDex extractedDex) {
            this.number = number;
            this.zipEntry = zipEntry;
            this.extractedDex = extractedDex;
            this.size = zipEntry.getSize();
        }

        public int getNumber() {
            return number;
        }

        public ZipEntry getZipEntry() {
            return zipEntry;
        }

        public ExtractedDex getExtractedDex() {
            return extractedDex;
        }

        public long getSize() {
            return size;
        }

        @Override
        public int compareTo( ZipEntryWrapper zipEntryWrapper) {
            return size - zipEntryWrapper.getSize() > 0 ? 1 : -1;
        }
    }

    private class ExtractCallable implements Callable<Boolean> {
        private ZipFile apk;
        private String extractedFilePrefix;
        private ZipEntryGroup  group;
        // just for log
        private int groupIndex;

        public ExtractCallable(ZipFile apk, ZipEntryGroup group , String extractedFilePrefix, int groupIndex) {
            this.apk = apk;
            this.group = group;
            this.extractedFilePrefix = extractedFilePrefix;
            this.groupIndex = groupIndex;
        }

        @Override
        public Boolean call() throws Exception {
            boolean flag = true;
            try {
                long start = SystemClock.uptimeMillis();
                Log.i(TAG, "group[" + groupIndex + "] = " + group + " extract begin");
                List<ZipEntryWrapper> entryWrappers = group.getEntryWrappers();
                for (ZipEntryWrapper entryWrapper : entryWrappers){
                    boolean success = extractDex(entryWrapper);
                    if(flag && success){
                        continue;
                    }else {
                        flag = false;
                        break;
                    }
                }
                if(flag){
                    Log.i(TAG, "group[" + groupIndex + "] = " + group + " extract success, need " + (SystemClock.uptimeMillis() - start) + "ms");
                }else {
                    Log.i(TAG, "group[" + groupIndex + "] = " + group + " extract failed, need " + (SystemClock.uptimeMillis() - start) + "ms");
                }
            }catch (Exception e){

            }
            return flag;
        }

        private boolean extractDex(ZipEntryWrapper entryWrapper){
            try{
                long start = SystemClock.uptimeMillis();
                int secondaryNumber = entryWrapper.getNumber();
                ZipEntry dexFile = entryWrapper.getZipEntry();
                ExtractedDex extractedFile = entryWrapper.getExtractedDex();
                int numAttempts = 0;
                boolean isExtractionSuccessful = false;
                Log.i(TAG, "group " + groupIndex + " extracting, extract dex classs" + secondaryNumber + ".dex");
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
                                Log.i(TAG, "\"" + extractedFile.getAbsolutePath() + "\" crc verify disabled");
                            }else {
                                Log.i(TAG, "\"" + extractedFile.getAbsolutePath() + "\" crc verify enabled");
                                extractedFile.crc = getDexCrc(extractedFile);
                            }

                        }else {
                            if((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_NOT_DEX_CRC)  != 0){
                                Log.i(TAG, "\"" + extractedFile.getAbsolutePath() + "\" crc verify disabled");
                            }else {
                                Log.i(TAG, "\"" + extractedFile.getAbsolutePath() + "\" crc verify enabled");
                                extractedFile.crc = getZipCrc(extractedFile);
                            }
                        }
                        isExtractionSuccessful = true;
                        Log.i(TAG, "extractDex apk Entry(classes" + secondaryNumber+ ".dex) to \"" + extractedFile.getAbsolutePath() + "\" , getCrc need " +  (SystemClock.uptimeMillis() - start3) + "ms");
                    } catch (IOException e) {
                        isExtractionSuccessful = false;
                        Log.w(TAG, "Failed to read crc from " + extractedFile.getAbsolutePath(), e);
                    }

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
                Log.i(TAG, "MultiDexExtractor.ExtractCallable extract need " +  (SystemClock.uptimeMillis() - start) + "ms");
                return true;
            }catch (Exception e){

            }
            return false;
        }
    }
}

package com.gibbon.compactmultidex;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;


/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 */
public abstract class DexElementsExtractor implements IHookMultiDexExtractor {

    protected static final String TAG = MultiDex.TAG;

    /**
     * Extracts application secondary dexes into files in the application data
     * directory.
     *
     * @return a list of files that were created. The list may be empty if there
     * are no secondary dex files. Never return null.
     * @throws IOException if encounters a problem while reading or writing
     *                     secondary dex files
     */
    public final List<? extends File> load(Context context, ApplicationInfo applicationInfo, File dexDir,
                                           boolean forceReload) throws IOException {
        Log.i(TAG, "DexElementsExtractor.load(" + applicationInfo.sourceDir + ", " + forceReload + ")");
        final File sourceApk = new File(applicationInfo.sourceDir);

        long currentCrc = getZipCrc(sourceApk);

        // Validity check and extraction must be done only while the lock file has been taken.
        File lockFile = new File(dexDir, LOCK_FILENAME);
        RandomAccessFile lockRaf = new RandomAccessFile(lockFile, "rw");
        FileChannel lockChannel = null;
        FileLock cacheLock = null;
        List<ExtractedDex> files;
        IOException releaseLockException = null;
        try {
            lockChannel = lockRaf.getChannel();
            Log.i(TAG, "Blocking on lock " + lockFile.getPath());
            cacheLock = lockChannel.lock();
            Log.i(TAG, lockFile.getPath() + " locked");

            if (!forceReload && !isModified(context, sourceApk, currentCrc)) {
                try {
                    Log.i(TAG, "Detected that no need to perform extraction.");
                    files = loadExistingExtractions(context, sourceApk, dexDir);
                } catch (IOException ioe) {
                    Log.w(TAG, "Failed to reload existing extracted secondary dex files,"
                            + " falling back to fresh extraction", ioe);
                    files = performExtractions(sourceApk, dexDir);
                    putStoredApkInfo(context, getTimeStamp(sourceApk), currentCrc, files);
                }
            } else {
                Log.i(TAG, "Detected that extraction must be performed.");
                files = performExtractions(sourceApk, dexDir);
                putStoredApkInfo(context, getTimeStamp(sourceApk), currentCrc, files);
            }
        } finally {
            if (cacheLock != null) {
                try {
                    cacheLock.release();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to release lock on " + lockFile.getPath());
                    // Exception while releasing the lock is bad, we want to report it, but not at
                    // the price of overriding any already pending exception.
                    releaseLockException = e;
                }
            }
            if (lockChannel != null) {
                closeQuietly(lockChannel);
            }
            closeQuietly(lockRaf);
        }

        if (releaseLockException != null) {
            throw releaseLockException;
        }

        Log.i(TAG, "load found " + files.size() + " secondary dex files");
        return files;
    }

    /**
     * 解压操作
     *
     * @param sourceApk
     * @param dexDir
     * @return
     * @throws IOException
     */
    abstract protected List<ExtractedDex> performExtractions(File sourceApk, File dexDir) throws IOException;

    /**
     * This removes old files.
     */
    protected static void prepareDexDir(File dexDir, final String extractedFilePrefix) {
        FileFilter filter = new FileFilter() {

            @Override
            public boolean accept(File pathname) {
                String name = pathname.getName();
                return !(name.startsWith(extractedFilePrefix)
                        || name.equals(LOCK_FILENAME));
            }
        };
        File[] files = dexDir.listFiles(filter);
        if (files == null) {
            Log.w(TAG, "Failed to list secondary dex dir content (" + dexDir.getPath() + ").");
            return;
        }
        for (File oldFile : files) {
            Log.i(TAG, "Trying to delete old file " + oldFile.getPath() + " of size " +
                    oldFile.length());
            if (oldFile.isFile()) {
                if (!oldFile.delete()) {
                    Log.w(TAG, "Failed to delete old file " + oldFile.getPath());
                } else {
                    Log.i(TAG, "Deleted old file " + oldFile.getPath());
                }
            }
        }
    }

    protected void extract(ZipFile apk, ZipEntry dexFile, File extractTo,
                           String extractedFilePrefix) throws IOException, FileNotFoundException {

        InputStream in = apk.getInputStream(dexFile);
        ZipOutputStream out = null;
        // Temp files must not start with extractedFilePrefix to get cleaned up in prepareDexDir()
        File tmp = File.createTempFile("tmp-" + extractedFilePrefix, EXTRACTED_SUFFIX,
                extractTo.getParentFile());
        Log.i(TAG, "Extracting " + tmp.getPath());
        try {
            out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            try {
                ZipEntry classesDex = new ZipEntry("classes.dex");
                // keep zip entry time since it is the criteria used by Dalvik
                classesDex.setTime(dexFile.getTime());
                out.putNextEntry(classesDex);

                byte[] buffer = new byte[BUFFER_SIZE];
                int length = in.read(buffer);
                while (length != -1) {
                    out.write(buffer, 0, length);
                    length = in.read(buffer);
                }
                out.closeEntry();
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
    }

    /**
     * Closes the given {@code Closeable}. Suppresses any IO exceptions.
     */
    protected static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close resource", e);
        }
    }

    /**
     * Load previously extracted secondary dex files. Should be called only while owning the lock on
     * {@link #LOCK_FILENAME}.
     */
    private final List<ExtractedDex> loadExistingExtractions(
            Context context, File sourceApk, File dexDir)
            throws IOException {
        DexElementsLoader dexElementsLoader = null;
        if ((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_EXTRACT_TO_RAW_DEX_CONCURRENT_CRC) != 0) {
            dexElementsLoader = new ConcurrentDexElementsLoader();
        } else {
            dexElementsLoader = new SerialDexElementsLoader();
        }
        return dexElementsLoader.loadExistingExtractions(context, sourceApk, dexDir);
    }

    private interface IDexElementsLoader {
        public List<ExtractedDex> loadExistingExtractions(
                Context context, File sourceApk, File dexDir) throws IOException;
    }

    private abstract class DexElementsLoader implements IDexElementsLoader {

    }

    private class ConcurrentDexElementsLoader extends DexElementsLoader {
        @Override
        public List<ExtractedDex> loadExistingExtractions(Context context, File sourceApk, File dexDir) throws IOException {
            Log.i(TAG, "loading existing secondary dex files");
            long begin = SystemClock.uptimeMillis();
            final String extractedFilePrefix = sourceApk.getName() + EXTRACTED_NAME_EXT;
            SharedPreferences multiDexPreferences = getMultiDexPreferences(context);
            // totalDexNumber 为包含主dex的数量
            int totalDexNumber = multiDexPreferences.getInt(KEY_DEX_NUMBER, 1);
            final List<ExtractedDex> files = new ArrayList<>(totalDexNumber - 1);
            List<DexOrZipFile> dexOrZipFiles = new ArrayList<>();

            String suffix;
            if ((MultiDex.FLAG_EXTRACT_RAW_DEX_NOT_ZIP & MultiDex.DEFAULT_FLAG) != 0) {
                suffix = DEX_SUFFIX;
            } else {
                suffix = EXTRACTED_SUFFIX;
            }
            for (int secondaryNumber = 2; secondaryNumber <= totalDexNumber; secondaryNumber++) {

                String fileName = extractedFilePrefix + secondaryNumber + suffix;
                ExtractedDex extractedFile = new ExtractedDex(dexDir, fileName);
                if (extractedFile.exists() && extractedFile.isFile()) {
                    DexOrZipFile dexOrZipFile = new DexOrZipFile(multiDexPreferences, dexDir, extractedFilePrefix, secondaryNumber, suffix);
                    dexOrZipFiles.add(dexOrZipFile);
                } else {
                    throw new IOException("Missing extracted secondary dex file '" +
                            extractedFile.getPath() + "'");
                }
            }

            int threadMaxSizeLimit = 0;
            if (MultiDex.CPU_COUNT == 1) {
                threadMaxSizeLimit = MultiDex.CPU_COUNT * 2 + 1;
            } else if (MultiDex.CPU_COUNT == 2) {
                threadMaxSizeLimit = MultiDex.CPU_COUNT * 2;
            } else {
                threadMaxSizeLimit = MultiDex.CPU_COUNT + 1;
            }
            int extraDexCount = dexOrZipFiles.size();
            if (extraDexCount <= 0) {
                return files;
            }
            int threadSize = 0;
            if (extraDexCount > threadMaxSizeLimit) {
                threadSize = threadMaxSizeLimit;
            } else {
                threadSize = extraDexCount - 1;
            }
            Log.i(TAG, "cpu count: " + MultiDex.CPU_COUNT + ", threadMaxSizeLimit: " + threadMaxSizeLimit + ", extraDexCount: " + extraDexCount + ", final thread size: " + threadSize);

            List<DexOrZipFileGroup> groups = makeGroupList(threadSize + 1, dexOrZipFiles);
            int size = groups.size();
            Log.i(TAG, "group size: " + size);
            FutureTask<List<ExtractedDex>>[] futureTasks = new FutureTask[size];
            int secondaryNumber = 2;
            for (int i = 0; i < size; i++) {
                DexOrZipFileGroup group = groups.get(i);
                futureTasks[i] = new FutureTask<List<ExtractedDex>>(new LoadAndCrcVerifyCallable(group, i));
            }
            for (int i = 1; i < size; i++) {
                new Thread(futureTasks[i]).start();
                ;
            }
            if (futureTasks.length > 0) {
                futureTasks[0].run();
            }
            try {
                for (int i = 0; i < size; i++) {
                    List<ExtractedDex> extractedDexList = futureTasks[i].get();
                    if (extractedDexList != null) {
                        files.addAll(extractedDexList);
                    } else {
                        throw new IOException("Invalid extracted dex");
                    }
                }
            } catch (Exception e) {
                Log.i(TAG, "loading existing secondary dex files failed, need " + (SystemClock.uptimeMillis() - begin) + "ms");
                throw new IOException("loading existing secondary dex files failed");
            }

            Log.i(TAG, "loading existing secondary dex files success, need " + (SystemClock.uptimeMillis() - begin) + "ms");
            return files;
        }
    }

    private List<DexOrZipFileGroup> makeGroupList(int groupSize, List<DexOrZipFile> dexOrZipFiles) {
        List<DexOrZipFileGroup> groups = averageGroup(groupSize, dexOrZipFiles);
        Collections.sort(groups);
        return groups;
    }

    private List<DexOrZipFileGroup> averageGroup(int groupSize, List<DexOrZipFile> input) {
        ArrayList<DexOrZipFileGroup> resultList = new ArrayList<>(groupSize);
        if (groupSize > input.size() || groupSize <= 0) {
            return resultList;
        } else if (groupSize == input.size()) {
            for (DexOrZipFile dexOrZipFile : input) {
                DexOrZipFileGroup group = new DexOrZipFileGroup();
                group.add(dexOrZipFile);
                resultList.add(group);
            }
            return resultList;
        }

        for (int i = 0; i < groupSize; i++) {
            resultList.add(new DexOrZipFileGroup());
        }

        List<DexOrZipFile> sortedInput = input;
        int inputLen = input.size();
        Collections.sort(sortedInput);

        // 从最大的开始填充到结果中
        for (int i = 0; i < groupSize; i++) {
            resultList.get(i).add(sortedInput.get(inputLen - 1 - i));
        }
        // 从大到小遍历剩下的数字
        for (int i = inputLen - 1 - groupSize; i >= 0; i--) {
            ArrayList<Long> tempSum = new ArrayList<>(groupSize);
            for (DexOrZipFileGroup group : resultList) {
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
    private static long getSum(DexOrZipFileGroup group) {
        return group.getSize();
    }

    private class DexOrZipFileGroup implements Comparable<DexOrZipFileGroup> {
        private final List<DexOrZipFile> dexOrZipFiles;

        public DexOrZipFileGroup() {
            this.dexOrZipFiles = new ArrayList<>();
        }

        public boolean add(DexOrZipFile dexOrZipFile) {
            return dexOrZipFiles.add(dexOrZipFile);
        }

        @Override
        public int compareTo(DexOrZipFileGroup dexOrZipFileGroup) {
            // 降序排列
            return getSize() - dexOrZipFileGroup.getSize() > 0 ? -1 : 1;
        }

        public long getSize() {
            long size = 0;
            for (DexOrZipFile dexOrZipFile : dexOrZipFiles) {
                size += dexOrZipFile.getSize();
            }
            return size;
        }

        public List<DexOrZipFile> getDexOrZipFiles() {
            return dexOrZipFiles;
        }

        @Override
        public String toString() {
            StringBuffer stringBuffer = new StringBuffer();
            stringBuffer.append("{size:");
            stringBuffer.append(getSize());
            stringBuffer.append(",");
            stringBuffer.append("dexOrZips:");
            stringBuffer.append("{");
            int size = dexOrZipFiles.size();
            for (int i = 0; i < size; i++) {
                DexOrZipFile dexOrZipFile = dexOrZipFiles.get(i);
                stringBuffer.append("{");
                stringBuffer.append("size:");
                stringBuffer.append(dexOrZipFile.getSize());
                stringBuffer.append(",");
                stringBuffer.append("dexOrZip:");
                stringBuffer.append("classes" + dexOrZipFile.getNumber() + dexOrZipFile.getSuffix());
                stringBuffer.append("}");
                if (i != size - 1) {
                    stringBuffer.append(",");
                }
            }
            stringBuffer.append("}");
            stringBuffer.append("}");
            return stringBuffer.toString();
        }
    }

    private class DexOrZipFile implements Comparable<DexOrZipFile> {
        private final SharedPreferences multiDexPreferences;
        private final File dexDir;
        private final ExtractedDex extractedDex;
        private final String extractedFilePrefix;
        private final int number;
        private final String suffix;
        private final long size;

        public DexOrZipFile(SharedPreferences multiDexPreferences, File dexDir, String extractedFilePrefix, int number, String suffix) {
            this.multiDexPreferences = multiDexPreferences;
            this.dexDir = dexDir;
            String fileName = extractedFilePrefix + number + suffix;
            this.extractedDex = new ExtractedDex(dexDir, fileName);
            this.extractedFilePrefix = extractedFilePrefix;
            this.number = number;
            this.suffix = suffix;
            this.size = extractedDex.length();
        }

        public int getNumber() {
            return number;
        }

        public ExtractedDex getExtractedDex() {
            return extractedDex;
        }

        public long getSize() {
            return size;
        }

        public SharedPreferences getMultiDexPreferences() {
            return multiDexPreferences;
        }

        public File getDexDir() {
            return dexDir;
        }

        public String getExtractedFilePrefix() {
            return extractedFilePrefix;
        }

        public String getSuffix() {
            return suffix;
        }

        @Override
        public int compareTo(DexOrZipFile dexOrZipFile) {
            return size - dexOrZipFile.getSize() > 0 ? 1 : -1;
        }
    }

    private class LoadAndCrcVerifyCallable implements Callable<List<ExtractedDex>> {

        private final DexOrZipFileGroup group;
        private final int groupIndex;

        public LoadAndCrcVerifyCallable(DexOrZipFileGroup group, int groupIndex) {
            this.group = group;
            this.groupIndex = groupIndex;
        }

        @Override
        public List<ExtractedDex> call() throws Exception {
            try {
                long start = SystemClock.uptimeMillis();
                Log.i(TAG, "group[" + groupIndex + "] = " + group + " load and crc verify begin");
                List<ExtractedDex> extractedDexList = new ArrayList<>();
                List<DexOrZipFile> dexOrZipFiles = group.getDexOrZipFiles();
                for (DexOrZipFile dexOrZipFile : dexOrZipFiles) {
                    ExtractedDex extractedDex = loadDexAndCrcVerify(dexOrZipFile);
                    if (extractedDex != null) {
                        extractedDexList.add(extractedDex);
                    } else {
                        throw new IOException("");
                    }

                }
                boolean success = false;
                if (dexOrZipFiles.size() == extractedDexList.size()) {
                    success = true;
                }
                if (success) {
                    Log.i(TAG, "group[" + groupIndex + "] = " + group + " load and crc verify success, need " + (SystemClock.uptimeMillis() - start) + "ms");
                } else {
                    Log.i(TAG, "group[" + groupIndex + "] = " + group + " load and crc verify failed, need " + (SystemClock.uptimeMillis() - start) + "ms");
                }
                return extractedDexList;
            } catch (Exception e) {

            }
            return null;
        }

        private ExtractedDex loadDexAndCrcVerify(DexOrZipFile dexOrZipFile) {
            try {
                SharedPreferences multiDexPreferences = dexOrZipFile.getMultiDexPreferences();
                int secondaryNumber = dexOrZipFile.getNumber();
                String suffix = dexOrZipFile.getSuffix();
                ExtractedDex extractedFile = dexOrZipFile.getExtractedDex();
                if (extractedFile.isFile()) {
                    Log.i(TAG, "load and crc verify " + suffix.substring(1) + " \"" + extractedFile.getAbsolutePath() + "\"");
                    if ((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_NOT_DEX_CRC) != 0) {
                        Log.i(TAG, suffix.substring(1) + " \"" + extractedFile.getAbsolutePath() + "\" crc verify disabled");
                    } else {
                        Log.i(TAG, suffix.substring(1) + " \"" + extractedFile.getAbsolutePath() + "\" crc verify enabled");
                        long start = SystemClock.uptimeMillis();
                        if ((MultiDex.FLAG_EXTRACT_RAW_DEX_NOT_ZIP & MultiDex.DEFAULT_FLAG) != 0) {
                            long start1 = SystemClock.uptimeMillis();
                            extractedFile.crc = getDexCrc(extractedFile);
                            Log.i(TAG, "getDexCrc need " + (SystemClock.uptimeMillis() - start1) + "ms");
                        } else {
                            long start2 = SystemClock.uptimeMillis();
                            extractedFile.crc = getZipCrc(extractedFile);
                            Log.i(TAG, "getZipCrc need " + (SystemClock.uptimeMillis() - start2) + "ms");
                        }
                        long expectedCrc =
                                multiDexPreferences.getLong(KEY_DEX_CRC + secondaryNumber, NO_VALUE);
                        long expectedModTime =
                                multiDexPreferences.getLong(KEY_DEX_TIME + secondaryNumber, NO_VALUE);
                        long lastModified = extractedFile.lastModified();

                        Log.i(TAG, "load and crc verify " + suffix.substring(1) + " \"" + extractedFile.getAbsolutePath() + "\" need " + (SystemClock.uptimeMillis() - start) + "ms");
                        if ((expectedModTime != lastModified)
                                || (expectedCrc != extractedFile.crc)) {
                            Log.i(TAG, "Invalid extracted dex: " + extractedFile + ", need re-extracting again");
                            throw new IOException("Invalid extracted dex: " + extractedFile +
                                    ", expected modification time: "
                                    + expectedModTime + ", modification time: "
                                    + lastModified + ", expected crc: "
                                    + expectedCrc + ", file crc: " + extractedFile.crc);
                        }
                    }
                    return extractedFile;
                } else {
                    throw new IOException("Missing extracted secondary dex file '" +
                            extractedFile.getPath() + "'");
                }
            } catch (Exception e) {

            }
            return null;
        }
    }

    private class SerialDexElementsLoader extends DexElementsLoader {
        @Override
        public List<ExtractedDex> loadExistingExtractions(Context context, File sourceApk, File dexDir) throws IOException {
            Log.i(TAG, "loading existing secondary dex files");
            long begin = SystemClock.uptimeMillis();
            final String extractedFilePrefix = sourceApk.getName() + EXTRACTED_NAME_EXT;
            SharedPreferences multiDexPreferences = getMultiDexPreferences(context);
            int totalDexNumber = multiDexPreferences.getInt(KEY_DEX_NUMBER, 1);
            final List<ExtractedDex> files = new ArrayList<ExtractedDex>(totalDexNumber - 1);

            for (int secondaryNumber = 2; secondaryNumber <= totalDexNumber; secondaryNumber++) {
                String suffix;
                if ((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_EXTRACT_RAW_DEX_NOT_ZIP) != 0) {
                    suffix = DEX_SUFFIX;
                } else {
                    suffix = EXTRACTED_SUFFIX;
                }
                String fileName = extractedFilePrefix + secondaryNumber + suffix;
                ExtractedDex extractedFile = new ExtractedDex(dexDir, fileName);
                if (extractedFile.isFile()) {
                    Log.i(TAG, "load " + suffix.substring(1) + " \"" + extractedFile.getAbsolutePath() + "\"");
                    if ((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_NOT_DEX_CRC) != 0) {
                        Log.i(TAG, suffix.substring(1) + " \"" + extractedFile.getAbsolutePath() + "\" crc verify disabled");
                    } else {
                        Log.i(TAG, suffix.substring(1) + " \"" + extractedFile.getAbsolutePath() + "\" crc verify enabled");
                        long start = SystemClock.uptimeMillis();
                        if ((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_EXTRACT_RAW_DEX_NOT_ZIP) != 0) {
                            long start1 = SystemClock.uptimeMillis();
                            extractedFile.crc = getDexCrc(extractedFile);
                            Log.i(TAG, "getDexCrc need " + (SystemClock.uptimeMillis() - start1) + "ms");
                        } else {
                            long start2 = SystemClock.uptimeMillis();
                            extractedFile.crc = getZipCrc(extractedFile);
                            Log.i(TAG, "getZipCrc need " + (SystemClock.uptimeMillis() - start2) + "ms");
                        }
                        long expectedCrc =
                                multiDexPreferences.getLong(KEY_DEX_CRC + secondaryNumber, NO_VALUE);
                        long expectedModTime =
                                multiDexPreferences.getLong(KEY_DEX_TIME + secondaryNumber, NO_VALUE);
                        long lastModified = extractedFile.lastModified();

                        Log.i(TAG, "load " + suffix.substring(1) + " \"" + extractedFile.getAbsolutePath() + "\" need " + (SystemClock.uptimeMillis() - start) + "ms");
                        if ((expectedModTime != lastModified)
                                || (expectedCrc != extractedFile.crc)) {
                            Log.i(TAG, "Invalid extracted dex: " + extractedFile + ", need re-extracting again");
                            throw new IOException("Invalid extracted dex: " + extractedFile +
                                    ", expected modification time: "
                                    + expectedModTime + ", modification time: "
                                    + lastModified + ", expected crc: "
                                    + expectedCrc + ", file crc: " + extractedFile.crc);
                        }
                    }

                    files.add(extractedFile);
                } else {
                    throw new IOException("Missing extracted secondary dex file '" +
                            extractedFile.getPath() + "'");
                }
            }
            Log.i(TAG, "loading existing secondary dex files success, need " + (SystemClock.uptimeMillis() - begin) + "ms");
            return files;
        }
    }

    /**
     * Compare current archive and crc with values stored in {@link SharedPreferences}. Should be
     * called only while owning the lock on {@link #LOCK_FILENAME}.
     */
    protected static boolean isModified(Context context, File archive, long currentCrc) {
        SharedPreferences prefs = getMultiDexPreferences(context);
        return (prefs.getLong(KEY_TIME_STAMP, NO_VALUE) != getTimeStamp(archive))
                || (prefs.getLong(KEY_CRC, NO_VALUE) != currentCrc);
    }

    protected static long getTimeStamp(File archive) {
        long timeStamp = archive.lastModified();
        if (timeStamp == NO_VALUE) {
            // never return NO_VALUE
            timeStamp--;
        }
        return timeStamp;
    }


    protected static long getZipCrc(File archive) throws IOException {
        long computedValue = ZipUtil.getZipCrc(archive);
        if (computedValue == NO_VALUE) {
            // never return NO_VALUE
            computedValue--;
        }
        return computedValue;
    }

    protected static long getDexCrc(File dex) throws IOException {
        long computedValue = ZipUtil.getDexCrc(dex);
        if (computedValue == NO_VALUE) {
            // never return NO_VALUE
            computedValue--;
        }
        return computedValue;
    }

    /**
     * Save {@link SharedPreferences}. Should be called only while owning the lock on
     * {@link #LOCK_FILENAME}.
     */
    protected static void putStoredApkInfo(Context context, long timeStamp, long crc,
                                           List<ExtractedDex> extractedDexes) {
        SharedPreferences prefs = getMultiDexPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putLong(KEY_TIME_STAMP, timeStamp);
        edit.putLong(KEY_CRC, crc);
        edit.putInt(KEY_DEX_NUMBER, extractedDexes.size() + 1);

        int extractedDexId = 2;
        for (ExtractedDex dex : extractedDexes) {
            edit.putLong(KEY_DEX_CRC + extractedDexId, dex.crc);
            edit.putLong(KEY_DEX_TIME + extractedDexId, dex.lastModified());
            extractedDexId++;
        }
        /* Use commit() and not apply() as advised by the doc because we need synchronous writing of
         * the editor content and apply is doing an "asynchronous commit to disk".
         */
        edit.commit();
    }

    /**
     * Get the MuliDex {@link SharedPreferences} for the current application. Should be called only
     * while owning the lock on {@link #LOCK_FILENAME}.
     */
    protected static SharedPreferences getMultiDexPreferences(Context context) {
        return context.getSharedPreferences(PREFS_FILE,
                Build.VERSION.SDK_INT < 11 /* Build.VERSION_CODES.HONEYCOMB */
                        ? Context.MODE_PRIVATE
                        : Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS /* Context.MODE_MULTI_PROCESS */);
    }
}

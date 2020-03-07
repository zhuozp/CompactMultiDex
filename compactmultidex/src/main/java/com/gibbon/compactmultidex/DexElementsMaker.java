package com.gibbon.compactmultidex;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 */
public class DexElementsMaker implements IDexElementsMaker {

    final ArrayList<File> files;
    final IHookDexElementsMethodInvoker invoker;

    public DexElementsMaker(ArrayList<File> files, IHookDexElementsMethodInvoker invoker) {
        this.files = files;
        this.invoker = invoker;
    }

    @Override
    public Object[] make() throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

        if (files.size() <= 1) {
            return invoker.invoke(files);
        }

        //通过算法，将文件分解成大小相似的文件集，使得每个文件集在加载的时候时间相似
        ArrayList<ArrayList<File>> filesList = makeFileList();

        int size = filesList.size();
        Log.i(MultiDex.TAG, "group size: " + size);
        FutureTask<Object[]>[] futureTasks = new FutureTask[size];
        for (int i = 0; i < size; i++) {
            futureTasks[i] = new FutureTask<Object[]>(new DexElementsCallable(i, filesList.get(i), invoker));
        }
        long start = SystemClock.uptimeMillis();
        //其他任务在子线程里完成，加速加载
        for (int i = 1; i < size; i++) {
            new Thread(futureTasks[i]).start();
        }
        //一个任务在主线程完成，充分利用主线程资源
        futureTasks[0].run();

        ArrayList<Object[]> objectsList = new ArrayList<Object[]>();
        int objectsTotalLength = 0;

        try {
            for (int i = 0; i < size; i++) {
                Object[] objects = futureTasks[i].get();
                if (objects == null) throw new RuntimeException("Illegal Action");

                objectsTotalLength += objects.length;
                objectsList.add(objects);
            }

            Object[] objects = new Object[objectsTotalLength];

            int offset = 0;
            for (Object[] subObjects : objectsList) {
                if (subObjects != null) {
                    System.arraycopy(subObjects, 0, objects, offset, subObjects.length);
                    offset += subObjects.length;
                }
            }
            Log.i(MultiDex.TAG, "load dex success, need " + (SystemClock.uptimeMillis() - start) + "ms");
            return objects;
        } catch (Exception e) {

        }

        return invoker.invoke(files);
    }

    private ArrayList<ArrayList<File>> makeFileList() {
        ArrayList<ArrayList<File>> fileList = new ArrayList<>();

        long maxFileLength = getMaxFileLength();
        long subTotalLength = 0;
        ArrayList<File> subFiles = new ArrayList<File>();
        fileList.add(subFiles);

        for (File file : files) {
            if (file != null) {
                long subLength = file.length();
                if (subLength + subTotalLength > maxFileLength) {
                    subTotalLength = 0;
                    subFiles = new ArrayList<File>();
                    fileList.add(subFiles);
                }

                subFiles.add(file);
                subTotalLength += subLength;
            }
        }

        return fileList;
    }

    private long getMaxFileLength() {
        long max = 0;
        for (File file : files) {
            if (file != null) {
                long length = file.length();
                max = length > max ? length : max;
            }
        }
        return max;
    }

    static class DexElementsCallable implements Callable<Object[]> {

        final int id;
        final ArrayList<File> files;
        final IHookDexElementsMethodInvoker invoker;


        public DexElementsCallable(int id, ArrayList<File> files, IHookDexElementsMethodInvoker invoker) {
            this.id = id;
            this.files = files;
            this.invoker = invoker;
        }

        @Override
        public Object[] call() throws Exception {
            try {
                long startTime = SystemClock.uptimeMillis();
                Object[] objects = invoker.invoke(files);
                long endTime = SystemClock.uptimeMillis();
                Log.i(MultiDex.TAG, "group[" + id + "] cost time:" + (endTime - startTime) + "ms");
                return objects;
            } catch (Exception e) {

            }
            return null;
        }
    }
}

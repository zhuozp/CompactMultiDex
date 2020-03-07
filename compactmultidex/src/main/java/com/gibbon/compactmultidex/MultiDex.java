package com.gibbon.compactmultidex;

/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import dalvik.system.DexFile;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.zip.ZipFile;

/**
 * MultiDex patches {@link Context#getClassLoader() the application context class
 * loader} in order to load classes from more than one dex file. The primary
 * {@code classes.dex} must contain the classes necessary for calling this
 * class methods. Secondary dex files named classes2.dex, classes3.dex... found
 * in the application apk will be added to the classloader after first call to
 * {@link #install(Context)}.
 *
 * <p/>
 * This library provides compatibility for platforms with API level 4 through 20. This library does
 * nothing on newer versions of the platform which provide built-in support for secondary dex files.
 */

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 */
@SuppressWarnings("JavadocReference")
public class MultiDex {

    /**
     * 不进行优化，同原来的
     * */
    public static final int FLAG_NOT_COMPACT = 0 ;

    /**
     * 并发抽取dex
     * */
    public static final int FLAG_EXTRACT_CONCURRENT = 1;

    /**
     * 并发加载opt dex以及dexopt
     * */
    public static final int FLAG_LOAD_OPT_AND_DEX_CONCURRENT = 1 << 1;

    /**
     * 解压抽取的class.dex不进行zip压缩，直接解压生成dex
     * */
    public static final int FLAG_EXTRACT_RAW_DEX_NOT_ZIP = 1 << 2;

    /**
     * 并发校验本地抽取出的dex的crc验证
     * */
    public static final int FLAG_EXTRACT_TO_RAW_DEX_CONCURRENT_CRC = 1 << 3;

    /**
     * 本地dex不做crc验证、只做apk验证
     * */
    public static final int FLAG_NOT_DEX_CRC = 1 << 4;

    /**
     * 所有优化点一起进行
     * */
    public static final int FLAG_ALL_OPT = FLAG_EXTRACT_CONCURRENT | FLAG_LOAD_OPT_AND_DEX_CONCURRENT | FLAG_EXTRACT_RAW_DEX_NOT_ZIP |
            FLAG_EXTRACT_TO_RAW_DEX_CONCURRENT_CRC | FLAG_NOT_DEX_CRC;

    public static int DEFAULT_FLAG = FLAG_ALL_OPT;

    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    static final String TAG = "MultiDex";

    private static final String OLD_SECONDARY_FOLDER_NAME = "secondary-dexes";

    private static final String CODE_CACHE_NAME = "code_cache";

    private static final String CODE_CACHE_SECONDARY_FOLDER_NAME = "secondary-dexes";

    private static final int MAX_SUPPORTED_SDK_VERSION = 20;

    private static final int MIN_SDK_VERSION = 4;

    private static final int VM_WITH_MULTIDEX_VERSION_MAJOR = 2;

    private static final int VM_WITH_MULTIDEX_VERSION_MINOR = 1;

    private static final String NO_KEY_PREFIX = "";

    private static final Set<String> installedApk = new HashSet<>();

    private static final boolean IS_VM_MULTIDEX_CAPABLE =
            isVMMultidexCapable(System.getProperty("java.vm.version"));

    private MultiDex() {}

    /**
     * Patches the application context class loader by appending extra dex files
     * loaded from the application apk. This method should be called in the
     * attachBaseContext of your {@link Application}, see
     * {@link MultiDexApplication} for more explanation and an example.
     *
     * @param context application context.
     * @throws RuntimeException if an error occurred preventing the classloader
     *         extension.
     */
    public static void install(Context context) {
        install(context, DEFAULT_FLAG);
    }

    public static void install(Context context, int flag) {
        Log.i(TAG, "Installing application");
        Log.i(TAG, "CPU count: " + CPU_COUNT);
        long startTime = SystemClock.uptimeMillis();
        if (IS_VM_MULTIDEX_CAPABLE) {
            Log.i(TAG, "VM has multidex support, MultiDex support library is disabled.");
            return;
        }

        if (Build.VERSION.SDK_INT < MIN_SDK_VERSION) {
            throw new RuntimeException("MultiDex installation failed. SDK " + Build.VERSION.SDK_INT
                    + " is unsupported. Min SDK version is " + MIN_SDK_VERSION + ".");
        }

        try {
            ApplicationInfo applicationInfo = getApplicationInfo(context);
            if (applicationInfo == null) {
                Log.i(TAG, "No ApplicationInfo available, i.e. running on a test Context:"
                        + " MultiDex support library is disabled.");
                return;
            }

            synchronized (installedApk) {
                String apkPath = applicationInfo.sourceDir;
                if (installedApk.contains(apkPath)) {
                    return;
                }

                installedApk.add(apkPath);

                if (Build.VERSION.SDK_INT > MAX_SUPPORTED_SDK_VERSION) {
                    Log.w(TAG, "MultiDex is not guaranteed to work in SDK version "
                            + Build.VERSION.SDK_INT + ": SDK version higher than "
                            + MAX_SUPPORTED_SDK_VERSION + " should be backed by "
                            + "runtime with built-in multidex capabilty but it's not the "
                            + "case here: java.vm.version=\""
                            + System.getProperty("java.vm.version") + "\"");
                }

                /* The patched class loader is expected to be a ClassLoader capable of loading DEX
                 * bytecode. We modify its pathList field to append additional DEX file entries.
                 */
                ClassLoader loader = getDexClassloader(context);
                if (loader == null) {
                    return;
                }

                try {
                    clearOldDexDir(context);
                } catch (Throwable t) {
                    Log.w(TAG, "Something went wrong when trying to clear old MultiDex extraction, "
                            + "continuing without cleaning.", t);
                }

                File dexDir = getDexDir(context, applicationInfo);
                // MultiDexExtractor is taking the file lock and keeping it until it is closed.
                // Keep it open during installSecondaryDexes and through forced extraction to ensure no
                // extraction or optimizing dexopt is running in parallel.
                List<? extends File> files = MultiDexExtractor.load(context, applicationInfo, dexDir, false);

                if ((DEFAULT_FLAG & FLAG_EXTRACT_RAW_DEX_NOT_ZIP) != 0) {
                    installSecondaryDexes(loader, getOptDexDir(dexDir), files);
                } else {
                    installSecondaryDexes(loader, dexDir, files);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "MultiDex installation failure", e);
            throw new RuntimeException("MultiDex installation failed (" + e.getMessage() + ").");
        }
        Log.i(TAG, "install done, all time is: " + (SystemClock.uptimeMillis() - startTime));
    }

    public static File getOptDexDir(File rawDexDir) throws IOException {
        File optDexDir = new File(rawDexDir, "dexopt");
        mkdirChecked(optDexDir);
        return optDexDir;
    }

    /**
     * Returns a {@link Classloader} from the {@link Context} that is capable of reading dex
     * bytecode or null if the Classloader is not dex-capable e.g: when running on a JVM testing
     * environment such as Robolectric.
     */
    private static ClassLoader getDexClassloader(Context context) {
        ClassLoader loader;
        try {
            loader = context.getClassLoader();
        } catch (RuntimeException e) {
            /* Ignore those exceptions so that we don't break tests relying on Context like
             * a android.test.mock.MockContext or a android.content.ContextWrapper with a
             * null base Context.
             */
            Log.w(TAG, "Failure while trying to obtain Context class loader. "
                    + "Must be running in test mode. Skip patching.", e);
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            if (loader instanceof dalvik.system.BaseDexClassLoader) {
                return loader;
            }
        } else if (loader instanceof dalvik.system.DexClassLoader
                || loader instanceof dalvik.system.PathClassLoader) {
            return loader;
        }
        Log.e(TAG, "Context class loader is null or not dex-capable. "
                + "Must be running in test mode. Skip patching.");
        return null;
    }

    private static ApplicationInfo getApplicationInfo(Context context) {
        try {
            /* Due to package install races it is possible for a process to be started from an old
             * apk even though that apk has been replaced. Querying for ApplicationInfo by package
             * name may return information for the new apk, leading to a runtime with the old main
             * dex file and new secondary dex files. This leads to various problems like
             * ClassNotFoundExceptions. Using context.getApplicationInfo() should result in the
             * process having a consistent view of the world (even if it is of the old world). The
             * package install races are eventually resolved and old processes are killed.
             */
            return context.getApplicationInfo();
        } catch (RuntimeException e) {
            /* Ignore those exceptions so that we don't break tests relying on Context like
             * a android.test.mock.MockContext or a android.content.ContextWrapper with a null
             * base Context.
             */
            Log.w(TAG, "Failure while trying to obtain ApplicationInfo from Context. " +
                    "Must be running in test mode. Skip patching.", e);
            return null;
        }
    }

    /**
     * Identifies if the current VM has a native support for multidex, meaning there is no need for
     * additional installation by this library.
     * @return true if the VM handles multidex
     */
    /* package visible for test */
    static boolean isVMMultidexCapable(String versionString) {
        boolean isMultidexCapable = false;
        if (versionString != null) {
            StringTokenizer tokenizer = new StringTokenizer(versionString, ".");
            String majorToken = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
            String minorToken = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
            if (majorToken != null && minorToken != null) {
                try {
                    int major = Integer.parseInt(majorToken);
                    int minor = Integer.parseInt(minorToken);
                    isMultidexCapable = (major > VM_WITH_MULTIDEX_VERSION_MAJOR)
                            || ((major == VM_WITH_MULTIDEX_VERSION_MAJOR)
                            && (minor >= VM_WITH_MULTIDEX_VERSION_MINOR));
                } catch (NumberFormatException e) {
                    // let isMultidexCapable be false
                }
            }
        }
        Log.i(TAG, "VM with version " + versionString +
                (isMultidexCapable ?
                        " has multidex support" :
                        " does not have multidex support"));
        return isMultidexCapable;
    }

    private static void installSecondaryDexes(ClassLoader loader, File dexDir,
                                              List<? extends File> files)
            throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException,
            InvocationTargetException, NoSuchMethodException, IOException, SecurityException,
            ClassNotFoundException, InstantiationException {
        if (!files.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 19) {
                V19.install(loader, files, dexDir);
            } else if (Build.VERSION.SDK_INT >= 14) {
                V14.install(loader, files, dexDir);
            } else {
                V4.install(loader, files);
            }
        }
    }

    /**
     * Locates a given field anywhere in the class inheritance hierarchy.
     *
     * @param instance an object to search the field into.
     * @param name field name
     * @return a field object
     * @throws NoSuchFieldException if the field cannot be located
     */
    private static Field findField(Object instance, String name) throws NoSuchFieldException {
        for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);


                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }

                return field;
            } catch (NoSuchFieldException e) {
                // ignore and search next
            }
        }

        throw new NoSuchFieldException("Field " + name + " not found in " + instance.getClass());
    }

    /**
     * Locates a given method anywhere in the class inheritance hierarchy.
     *
     * @param instance an object to search the method into.
     * @param name method name
     * @param parameterTypes method parameter types
     * @return a method object
     * @throws NoSuchMethodException if the method cannot be located
     */
    private static Method findMethod(Object instance, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Method method = clazz.getDeclaredMethod(name, parameterTypes);


                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }

                return method;
            } catch (NoSuchMethodException e) {
                // ignore and search next
            }
        }

        throw new NoSuchMethodException("Method " + name + " with parameters " +
                Arrays.asList(parameterTypes) + " not found in " + instance.getClass());
    }

    /**
     * Replace the value of a field containing a non null array, by a new array containing the
     * elements of the original array plus the elements of extraElements.
     * @param instance the instance whose field is to be modified.
     * @param fieldName the field to modify.
     * @param extraElements elements to append at the end of the array.
     */
    private static void expandFieldArray(Object instance, String fieldName,
                                         Object[] extraElements) throws NoSuchFieldException, IllegalArgumentException,
            IllegalAccessException {
        Field jlrField = findField(instance, fieldName);
        Object[] original = (Object[]) jlrField.get(instance);
        Object[] combined = (Object[]) Array.newInstance(
                original.getClass().getComponentType(), original.length + extraElements.length);
        System.arraycopy(original, 0, combined, 0, original.length);
        System.arraycopy(extraElements, 0, combined, original.length, extraElements.length);
        jlrField.set(instance, combined);
    }

    private static void clearOldDexDir(Context context) throws Exception {
        File dexDir = new File(context.getFilesDir(), OLD_SECONDARY_FOLDER_NAME);
        if (dexDir.isDirectory()) {
            Log.i(TAG, "Clearing old secondary dex dir (" + dexDir.getPath() + ").");
            File[] files = dexDir.listFiles();
            if (files == null) {
                Log.w(TAG, "Failed to list secondary dex dir content (" + dexDir.getPath() + ").");
                return;
            }
            for (File oldFile : files) {
                Log.i(TAG, "Trying to delete old file " + oldFile.getPath() + " of size "
                        + oldFile.length());
                if (!oldFile.delete()) {
                    Log.w(TAG, "Failed to delete old file " + oldFile.getPath());
                } else {
                    Log.i(TAG, "Deleted old file " + oldFile.getPath());
                }
            }
            if (!dexDir.delete()) {
                Log.w(TAG, "Failed to delete secondary dex dir " + dexDir.getPath());
            } else {
                Log.i(TAG, "Deleted old secondary dex dir " + dexDir.getPath());
            }
        }
    }

    private static File getDexDir(Context context, ApplicationInfo applicationInfo)
            throws IOException {
        File cache = new File(applicationInfo.dataDir, CODE_CACHE_NAME);
        try {
            mkdirChecked(cache);
        } catch (IOException e) {
            /* If we can't emulate code_cache, then store to filesDir. This means abandoning useless
             * files on disk if the device ever updates to android 5+. But since this seems to
             * happen only on some devices running android 2, this should cause no pollution.
             */
            cache = new File(context.getFilesDir(), CODE_CACHE_NAME);
            mkdirChecked(cache);
        }
        File dexDir = new File(cache, CODE_CACHE_SECONDARY_FOLDER_NAME);
        mkdirChecked(dexDir);
        return dexDir;
    }

    private static void mkdirChecked(File dir) throws IOException {
        dir.mkdir();
        if (!dir.isDirectory()) {
            File parent = dir.getParentFile();
            if (parent == null) {
                Log.e(TAG, "Failed to create dir " + dir.getPath() + ". Parent file is null.");
            } else {
                Log.e(TAG, "Failed to create dir " + dir.getPath() +
                        ". parent file is a dir " + parent.isDirectory() +
                        ", a file " + parent.isFile() +
                        ", exists " + parent.exists() +
                        ", readable " + parent.canRead() +
                        ", writable " + parent.canWrite());
            }
            throw new IOException("Failed to create directory " + dir.getPath());
        }
    }

    /**
     * Installer for platform versions 19.
     */
    private static final class V19 {

        static void install(ClassLoader loader,
                            List<? extends File> additionalClassPathEntries,
                            File optimizedDirectory)
                throws IllegalArgumentException, IllegalAccessException,
                NoSuchFieldException, InvocationTargetException, NoSuchMethodException,
                IOException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.BaseDexClassLoader. We modify its
             * dalvik.system.DexPathList pathList field to append additional DEX
             * file entries.
             */
            Field pathListField = findField(loader, "pathList");
            Object dexPathList = pathListField.get(loader);
            ArrayList<IOException> suppressedExceptions = new ArrayList<>();
            expandFieldArray(dexPathList, "dexElements", makeDexElements(dexPathList,
                    new ArrayList<File>(additionalClassPathEntries), optimizedDirectory,
                    suppressedExceptions));
            if (suppressedExceptions.size() > 0) {
                for (IOException e : suppressedExceptions) {
                    Log.w(TAG, "Exception in makeDexElement", e);
                }
                Field suppressedExceptionsField =
                        findField(dexPathList, "dexElementsSuppressedExceptions");
                IOException[] dexElementsSuppressedExceptions =
                        (IOException[]) suppressedExceptionsField.get(dexPathList);

                if (dexElementsSuppressedExceptions == null) {
                    dexElementsSuppressedExceptions =
                            suppressedExceptions.toArray(
                                    new IOException[suppressedExceptions.size()]);
                } else {
                    IOException[] combined =
                            new IOException[suppressedExceptions.size() +
                                    dexElementsSuppressedExceptions.length];
                    suppressedExceptions.toArray(combined);
                    System.arraycopy(dexElementsSuppressedExceptions, 0, combined,
                            suppressedExceptions.size(), dexElementsSuppressedExceptions.length);
                    dexElementsSuppressedExceptions = combined;
                }

                suppressedExceptionsField.set(dexPathList, dexElementsSuppressedExceptions);

                IOException exception = new IOException("I/O exception during makeDexElement");
                exception.initCause(suppressedExceptions.get(0));
                throw exception;
            }
        }

        /**
         * A wrapper around
         * {@code private static final dalvik.system.DexPathList#makeDexElements}.
         */
        private static Object[] makeDexElements(
                Object dexPathList, ArrayList<File> files, File optimizedDirectory,
                ArrayList<IOException> suppressedExceptions)
                throws IllegalAccessException, InvocationTargetException,
                NoSuchMethodException {
            Method makeDexElements =
                    findMethod(dexPathList, "makeDexElements", ArrayList.class, File.class,
                            ArrayList.class);

            if ((DEFAULT_FLAG & FLAG_LOAD_OPT_AND_DEX_CONCURRENT) != 0) {
                IHookDexElementsMethodInvoker invoker = new HookDexElementsMethodInvokerV19(dexPathList,
                        optimizedDirectory,makeDexElements, suppressedExceptions);

                IDexElementsMaker maker = new DexElementsMaker(files, invoker);

                return maker.make();
            }

            return (Object[]) makeDexElements.invoke(dexPathList, files, optimizedDirectory,
                    suppressedExceptions);
        }
    }

    /**
     * Installer for platform versions 14, 15, 16, 17 and 18.
     */
    private static final class V14 {

        private interface ElementConstructor {
            Object newInstance(File file, DexFile dex)
                    throws IllegalArgumentException, InstantiationException,
                    IllegalAccessException, InvocationTargetException, IOException;
        }

        /**
         * Applies for ICS and early JB (initial release and MR1).
         */
        private static class ICSElementConstructor implements ElementConstructor {
            private final Constructor<?> elementConstructor;

            ICSElementConstructor(Class<?> elementClass)
                    throws SecurityException, NoSuchMethodException {
                elementConstructor =
                        elementClass.getConstructor(File.class, ZipFile.class, DexFile.class);
                elementConstructor.setAccessible(true);
            }

            @Override
            public Object newInstance(File file, DexFile dex)
                    throws IllegalArgumentException, InstantiationException,
                    IllegalAccessException, InvocationTargetException, IOException {
                return elementConstructor.newInstance(file, new ZipFile(file), dex);
            }
        }

        /**
         * Applies for some intermediate JB (MR1.1).
         *
         * See Change-Id: I1a5b5d03572601707e1fb1fd4424c1ae2fd2217d
         */
        private static class JBMR11ElementConstructor implements ElementConstructor {
            private final Constructor<?> elementConstructor;

            JBMR11ElementConstructor(Class<?> elementClass)
                    throws SecurityException, NoSuchMethodException {
                elementConstructor = elementClass
                        .getConstructor(File.class, File.class, DexFile.class);
                elementConstructor.setAccessible(true);
            }

            @Override
            public Object newInstance(File file, DexFile dex)
                    throws IllegalArgumentException, InstantiationException,
                    IllegalAccessException, InvocationTargetException {
                return elementConstructor.newInstance(file, file, dex);
            }
        }

        /**
         * Applies for latest JB (MR2).
         *
         * See Change-Id: Iec4dca2244db9c9c793ac157e258fd61557a7a5d
         */
        private static class JBMR2ElementConstructor implements ElementConstructor {
            private final Constructor<?> elementConstructor;

            JBMR2ElementConstructor(Class<?> elementClass)
                    throws SecurityException, NoSuchMethodException {
                elementConstructor = elementClass
                        .getConstructor(File.class, Boolean.TYPE, File.class, DexFile.class);
                elementConstructor.setAccessible(true);
            }

            @Override
            public Object newInstance(File file, DexFile dex)
                    throws IllegalArgumentException, InstantiationException,
                    IllegalAccessException, InvocationTargetException {
                return elementConstructor.newInstance(file, Boolean.FALSE, file, dex);
            }
        }

        private static final int EXTRACTED_SUFFIX_LENGTH =
                IHookMultiDexExtractor.EXTRACTED_SUFFIX.length();

        private final ElementConstructor elementConstructor;

        static void install(ClassLoader loader,
                            List<? extends File> additionalClassPathEntries,
                            File optimizedDirectory)
                throws  IOException, SecurityException, IllegalArgumentException,
                ClassNotFoundException, NoSuchMethodException, InstantiationException,
                IllegalAccessException, InvocationTargetException, NoSuchFieldException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.BaseDexClassLoader. We modify its
             * dalvik.system.DexPathList pathList field to append additional DEX
             * file entries.
             */
            Field pathListField = findField(loader, "pathList");
            Object dexPathList = pathListField.get(loader);
            Object[] elements = new V14().makeDexElements(dexPathList, new ArrayList<File>(additionalClassPathEntries), optimizedDirectory);
            try {
                expandFieldArray(dexPathList, "dexElements", elements);
            } catch (NoSuchFieldException e) {
                // dexElements was renamed pathElements for a short period during JB development,
                // eventually it was renamed back shortly after.
                Log.w(TAG, "Failed find field 'dexElements' attempting 'pathElements'", e);
                expandFieldArray(dexPathList, "pathElements", elements);
            }
        }

        private  V14() throws ClassNotFoundException, SecurityException, NoSuchMethodException {
            ElementConstructor constructor;
            Class<?> elementClass = Class.forName("dalvik.system.DexPathList$Element");
            try {
                constructor = new ICSElementConstructor(elementClass);
            } catch (NoSuchMethodException e1) {
                try {
                    constructor = new JBMR11ElementConstructor(elementClass);
                } catch (NoSuchMethodException e2) {
                    constructor = new JBMR2ElementConstructor(elementClass);
                }
            }
            this.elementConstructor = constructor;
        }

        /**
         * An emulation of {@code private static final dalvik.system.DexPathList#makeDexElements}
         * accepting only extracted secondary dex files.
         * OS version is catching IOException and just logging some of them, this version is letting
         * them through.
         */
        private Object[] makeDexElements(Object dexPathList, ArrayList<File> files, File optimizedDirectory)
                throws  SecurityException, IllegalArgumentException,
                IllegalAccessException, InvocationTargetException, NoSuchMethodException {

            Method makeElements = findMethod(dexPathList, "makeDexElements", ArrayList.class, File.class);

            if ((DEFAULT_FLAG & FLAG_LOAD_OPT_AND_DEX_CONCURRENT) != 0) {
                IHookDexElementsMethodInvoker invoker = new HookDexElementsMethodInvokerV14(dexPathList, optimizedDirectory, makeElements);
                IDexElementsMaker maker = new DexElementsMaker(files, invoker);

                return maker.make();
            }

            return (Object[]) makeElements.invoke(dexPathList, files, optimizedDirectory);
        }

        /**
         * Converts a zip file path of an extracted secondary dex to an output file path for an
         * associated optimized dex file.
         */
        private static String optimizedPathFor(File path) {
            // Any reproducible name ending with ".dex" should do but lets keep the same name
            // as DexPathList.optimizedPathFor

            File optimizedDirectory = path.getParentFile();
            String fileName = path.getName();
            String optimizedFileName =
                    fileName.substring(0, fileName.length() - EXTRACTED_SUFFIX_LENGTH)
                            + IHookMultiDexExtractor.DEX_SUFFIX;
            File result = new File(optimizedDirectory, optimizedFileName);
            return result.getPath();
        }
    }

    /**
     * Installer for platform versions 4 to 13.
     */
    private static final class V4 {
        static void install(ClassLoader loader,
                            List<? extends File> additionalClassPathEntries)
                throws IllegalArgumentException, IllegalAccessException,
                NoSuchFieldException, IOException {
            /* The patched class loader is expected to be a descendant of
             * dalvik.system.DexClassLoader. We modify its
             * fields mPaths, mFiles, mZips and mDexs to append additional DEX
             * file entries.
             */
            int extraSize = additionalClassPathEntries.size();

            Field pathField = findField(loader, "path");

            StringBuilder path = new StringBuilder((String) pathField.get(loader));
            String[] extraPaths = new String[extraSize];
            File[] extraFiles = new File[extraSize];
            ZipFile[] extraZips = new ZipFile[extraSize];
            DexFile[] extraDexs = new DexFile[extraSize];
            for (ListIterator<? extends File> iterator = additionalClassPathEntries.listIterator();
                 iterator.hasNext();) {
                File additionalEntry = iterator.next();
                String entryPath = additionalEntry.getAbsolutePath();
                path.append(':').append(entryPath);
                int index = iterator.previousIndex();
                extraPaths[index] = entryPath;
                extraFiles[index] = additionalEntry;
                extraZips[index] = new ZipFile(additionalEntry);
                extraDexs[index] = DexFile.loadDex(entryPath, entryPath + ".dex", 0);
            }

            pathField.set(loader, path.toString());
            expandFieldArray(loader, "mPaths", extraPaths);
            expandFieldArray(loader, "mFiles", extraFiles);
            expandFieldArray(loader, "mZips", extraZips);
            expandFieldArray(loader, "mDexs", extraDexs);
        }
    }
}
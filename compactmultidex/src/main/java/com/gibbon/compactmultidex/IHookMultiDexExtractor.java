package com.gibbon.compactmultidex;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 */
public interface IHookMultiDexExtractor {

    /**
     * Zip file containing one secondary dex file.
     */
    class ExtractedDex extends File {
        public long crc = NO_VALUE;

        public ExtractedDex(File dexDir, String fileName) {
            super(dexDir, fileName);
        }
    }

    /**
     * We look for additional dex files named {@code classes2.dex},
     * {@code classes3.dex}, etc.
     */
    String DEX_PREFIX = "classes";
    String DEX_SUFFIX = ".dex";

    String EXTRACTED_NAME_EXT = ".classes";
    String EXTRACTED_SUFFIX = ".zip";
    int MAX_EXTRACT_ATTEMPTS = 3;

    String PREFS_FILE = "multidex.version";
    String KEY_TIME_STAMP = "timestamp";
    String KEY_CRC = "crc";
    String KEY_DEX_NUMBER = "dex.number";
    String KEY_DEX_CRC = "dex.crc.";
    String KEY_DEX_TIME = "dex.time.";

    /**
     * Size of reading buffers.
     */
    int BUFFER_SIZE = 0x4000;
    /* Keep value away from 0 because it is a too probable time stamp value */
    long NO_VALUE = -1L;

    String LOCK_FILENAME = "MultiDex.lock";

    List<? extends File> load(Context context, ApplicationInfo applicationInfo, File dexDir, boolean forceReload) throws IOException;
}

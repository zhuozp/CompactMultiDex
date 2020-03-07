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
public class MultiDexExtractor {

    /**
     * Extracts application secondary dexes into files in the application data
     * directory.
     *
     * @return a list of files that were created. The list may be empty if there
     *         are no secondary dex files. Never return null.
     * @throws IOException if encounters a problem while reading or writing
     *         secondary dex files
     */
    public static List<? extends File> load(Context context, ApplicationInfo applicationInfo, File dexDir, boolean forceReload)
            throws IOException {
        IHookMultiDexExtractor extractor;
        if ((MultiDex.DEFAULT_FLAG & MultiDex.FLAG_EXTRACT_CONCURRENT) != 0) {
            extractor = new ConcurrentMultiDexExtractor();
        } else {
            extractor = new SerialMultiDexExtractor();
        }
        return extractor.load(context, applicationInfo, dexDir, forceReload);
    }
}

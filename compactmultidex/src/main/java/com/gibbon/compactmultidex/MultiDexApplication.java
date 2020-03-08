package com.gibbon.compactmultidex;

import android.app.Application;
import android.content.Context;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 */
public class MultiDexApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
}

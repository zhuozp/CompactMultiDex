package com.gibbon.compactmultidex.demo;

import android.app.Application;
import android.content.Context;

import com.gibbon.compactmultidex.MultiDex;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-08
 */
public class DemoApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
}

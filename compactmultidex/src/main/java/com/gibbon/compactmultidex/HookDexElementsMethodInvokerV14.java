package com.gibbon.compactmultidex;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 */
public class HookDexElementsMethodInvokerV14 implements IHookDexElementsMethodInvoker {

    final Object dexPathList;
    final File optmizedDirectory;
    final Method makeDexElements;

    public HookDexElementsMethodInvokerV14(Object dexPathList, File optmizedDirectory, Method makeDexElements) {
        this.dexPathList = dexPathList;
        this.optmizedDirectory = optmizedDirectory;
        this.makeDexElements = makeDexElements;
    }

    @Override
    public Object[] invoke(ArrayList<File> files) throws InvocationTargetException, IllegalAccessException {
        return (Object[]) makeDexElements.invoke(dexPathList, files, optmizedDirectory);
    }
}

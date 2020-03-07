package com.gibbon.compactmultidex;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 */
public class HookDexElementsMethodInvokerV19 implements IHookDexElementsMethodInvoker {

    final Object dexPathList;
    final File optmizedDirectory;
    final Method makeDexElements;
    final ArrayList<IOException> exceptions;

    public HookDexElementsMethodInvokerV19(Object dexPathList, File optmizedDirectory, Method makeDexElements, ArrayList<IOException> exceptions) {
        this.dexPathList = dexPathList;
        this.optmizedDirectory = optmizedDirectory;
        this.makeDexElements = makeDexElements;
        this.exceptions = exceptions;
    }

    @Override
    public Object[] invoke(ArrayList<File> files) throws InvocationTargetException, IllegalAccessException {
        return (Object[]) makeDexElements.invoke(dexPathList, files, optmizedDirectory, exceptions);
    }
}

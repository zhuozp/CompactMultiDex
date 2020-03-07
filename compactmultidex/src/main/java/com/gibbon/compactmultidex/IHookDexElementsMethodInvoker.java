package com.gibbon.compactmultidex;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 */
public interface IHookDexElementsMethodInvoker {
    public Object[] invoke(ArrayList<File> files) throws InvocationTargetException, IllegalAccessException;
}

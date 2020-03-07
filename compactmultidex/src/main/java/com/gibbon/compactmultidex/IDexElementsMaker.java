package com.gibbon.compactmultidex;

import java.lang.reflect.InvocationTargetException;

/**
 * @author zhipeng.zhuo
 * @date 2020-03-07
 */
public interface IDexElementsMaker {
    Object[] make() throws IllegalAccessException, InvocationTargetException, NoSuchMethodException;
}

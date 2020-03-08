# ComPactMultiDex
针对MultiDex的补充优化、支持多线程加载多dex包、支持多进程dexopt、支持解压抽取dex时可以不进行zip压缩等优化选项，针对4.4及以下在首次启动的时候要进行除主dex的dex提取、dex提取压缩，crc以及dexopt等一系列耗时操作的优化补充库。

### 接入步骤

1. build.gradle配置，不再需要引用官方的MultiDex库
```
allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}

dependencies {
	        implementation 'com.github.zhuozp:ComPactMultiDex:v1.0.0'
	}
 
 defaultConfig {
        // 加入multiDexEnabled允许多dex打包
        multiDexEnabled true
        multiDexKeepFile file('maindexlist.txt')
    }
```

2. 创建mianDexList.txt文件（同工程build.gradle同目录），查看路径下buid/intermediates/legacy_multidex_main_dex_list/release/mainDexList.txt, 若没有ComPactMultiDex相关的类，则加入下面的类到创建的mianDexList.txt文件中，主要是为了确保主dex有包含到

```
com/gibbon/compactmultidex/ConcurrentMultiDexExtractor$ExtractCallable.class
com/gibbon/compactmultidex/ConcurrentMultiDexExtractor$ZipEntryGroup.class
com/gibbon/compactmultidex/ConcurrentMultiDexExtractor$ZipEntryWrapper.class
com/gibbon/compactmultidex/ConcurrentMultiDexExtractor.class
com/gibbon/compactmultidex/DexElementsExtractor$1.class
com/gibbon/compactmultidex/DexElementsExtractor$ConcurrentDexElementsLoader.class
com/gibbon/compactmultidex/DexElementsExtractor$DexElementsLoader.class
com/gibbon/compactmultidex/DexElementsExtractor$DexOrZipFile.class
com/gibbon/compactmultidex/DexElementsExtractor$DexOrZipFileGroup.class
com/gibbon/compactmultidex/DexElementsExtractor$IDexElementsLoader.class
com/gibbon/compactmultidex/DexElementsExtractor$LoadAndCrcVerifyCallable.class
com/gibbon/compactmultidex/DexElementsExtractor$SerialDexElementsLoader.class
com/gibbon/compactmultidex/DexElementsExtractor.class
com/gibbon/compactmultidex/DexElementsMaker$DexElementsCallable.class
com/gibbon/compactmultidex/DexElementsMaker.class
com/gibbon/compactmultidex/HookDexElementsMethodInvokerV14.class
com/gibbon/compactmultidex/HookDexElementsMethodInvokerV19.class
com/gibbon/compactmultidex/IDexElementsMaker.class
com/gibbon/compactmultidex/IHookDexElementsMethodInvoker.class
com/gibbon/compactmultidex/IHookMultiDexExtractor$ExtractedDex.class
com/gibbon/compactmultidex/IHookMultiDexExtractor.class
com/gibbon/compactmultidex/MultiDex$V14$ElementConstructor.class
com/gibbon/compactmultidex/MultiDex$V14$ICSElementConstructor.class
com/gibbon/compactmultidex/MultiDex$V14$JBMR11ElementConstructor.class
com/gibbon/compactmultidex/MultiDex$V14$JBMR2ElementConstructor.class
com/gibbon/compactmultidex/MultiDex$V14.class
com/gibbon/compactmultidex/MultiDex$V19.class
com/gibbon/compactmultidex/MultiDex$V4.class
com/gibbon/compactmultidex/MultiDex.class
com/gibbon/compactmultidex/MultiDexApplication.class
com/gibbon/compactmultidex/MultiDexExtractor.class
com/gibbon/compactmultidex/SerialMultiDexExtractor.class
com/gibbon/compactmultidex/ZipUtil$CentralDirectory.class
com/gibbon/compactmultidex/ZipUtil.class
```

3. 添加application，或者直接使用MultiDexApplication

```
public class DemoApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // install方法可以加入需要的优化选项
        MultiDex.install(this，？);
    }
}
```
或AndroidManifest.xml文件中修改application如下
```
<application
        android:name=".MultiDexApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">
        
    </application>
```

#### 参考文献
1. https://cloud.tencent.com/developer/article/1143820
2. http://www.freesion.com/article/4649179258/

package com.miui.gallery.ocrbridge;

/**
 * 常量集中处。所有 Hook 点按字符串反射查找，尽量不绑定具体相册 / FV 版本。
 *
 * 反编译小米相册 4.3.1.13 确认的本地 OCR 链路：
 *   com.xiaomi.ocr.sdk_ocr.OCREngine
 *     .getInstance()                      静态，内部 loadLibrary(miocr / miocr_wrapper)
 *     .init(String cacheDir, String libDir) -> boolean   相册自己传 getCacheDir().getPath(), null
 *     .doOCR(Bitmap) -> OCRData$OCRResult                吃内存 Bitmap，不需要图片文件
 *     OCRData$OCRResult.total_text (public String)      识别出的全文
 *
 * 反编译 FV 悬浮球 1.6.4 确认的本地 OCR 链路：
 *   z1/e                                  抽象引擎基类（createOCREngine / getPreferEngine）
 *     .recognize(Bitmap) -> String        统一识别入口，返回合并后的纯文本
 *   com.baidu.paddle.lite.demo.ocr.BaiduOCREngine   具体实现（百度 PaddleLite，需下载语言包）
 *   z1/c                                语言包未下载时的空壳（直接返回 null）
 *
 * 两个 App 是不同进程，小米相册的 libmiocr.so 与 OCREngine 类都是相册私有，
 * FV 无法直接加载。所以 v1.2.0 用「一个模块两个作用域」：
 *   - 相册进程（服务端）：ProviderCallHook 把 OCR 暴露成 content call
 *   - FV 进程（客户端）：FvOcrHook 把已截好的 Bitmap 走内存管道送给相册，取回文本
 */
final class Config {
    static final String TAG = "GalleryOCR";

    /** 服务端：小米相册。 */
    static final String TARGET_PKG = "com.miui.gallery";
    /** 客户端：FV 悬浮球。 */
    static final String FV_PKG = "com.fooview.android.fooview";

    static final String ENGINE_CLASS = "com.xiaomi.ocr.sdk_ocr.OCREngine";
    static final String RESULT_FIELD = "total_text";

    /** content call 的 method 名（同步返回文本，推荐）。 */
    static final String METHOD_SCREEN = "extract_screen_text";
    static final String METHOD_IMAGE = "extract_image_text";
    static final String METHOD_PING = "ping";
    /**
     * 内部 method：App 把 Bitmap 直接放进 Bundle 交给相册 OCR（供 FV 悬浮球桥接用）。
     * 走 Binder 的 Parcelable Bitmap + ashmem，不受 1MB 事务限制；
     * shell 无法传 Bitmap，所以这条不写进对外命令清单。
     */
    static final String METHOD_BITMAP = "extract_bitmap";

    /** am broadcast 的 action（异步，结果靠回调广播 / 写文件 / 剪贴板）。 */
    static final String ACTION_SCREEN = "com.miui.gallery.ocr.action.EXTRACT_SCREEN";
    static final String ACTION_IMAGE = "com.miui.gallery.ocr.action.EXTRACT_IMAGE";

    static final String EXTRA_PATH = "path";
    static final String EXTRA_CALLBACK = "callback";
    static final String EXTRA_OUT = "out";
    static final String EXTRA_CLIP = "clip";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_BITMAP = "bitmap";
    static final String EXTRA_SUCCESS = "success";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_SOURCE = "source";

    /** 无权限、exported 的相册 Provider。content call 会顺带把相册进程拉起来。 */
    static final String CALL_URI = "content://com.miui.gallery.widget";
    /** 备用：同样 exported 且无权限。 */
    static final String CALL_URI_BACKUP = "content://com.miui.gallery.mediaeditor.authority";

    /**
     * 内存管道入口：把 PNG 写进相册进程内存，随后 extract_screen_text 直接消费。
     * 两种写法等价：
     *   shell：screencap -p | content write --uri &lt;INPUT_URI&gt;
     *   App：  getContentResolver().openOutputStream(Uri.parse(INPUT_URI), "w")
     * 全程零临时文件。
     */
    static final String INPUT_PATH = "ocr_input";
    static final String INPUT_URI = "content://com.miui.gallery.widget/ocr_input";
    static final long INPUT_WAIT_MS = 4000L;

    /** FV 侧一次跨进程识别的整体超时；超时回退到 FV 自带本地 OCR，功能不坏。 */
    static final long BRIDGE_TIMEOUT_MS = 12000L;

    /** FV 悬浮球里真正实现 recognize(Bitmap) 的具体引擎类。找不到就跳过，不影响相册侧。 */
    static final String[] FV_ENGINE_CLASSES = {
            "com.baidu.paddle.lite.demo.ocr.BaiduOCREngine",
            "z1.c",
    };

    /** 覆写了 call() 的相册 Provider，一并 hook 以覆盖备用 URI。 */
    static final String[] PROVIDER_CLASSES = {
            "com.miui.gallery.provider.GalleryWidgetOpenProvider",
            "com.miui.gallery.provider.GalleryOpenProvider",
            "com.miui.mediaeditor.api.GalleryProviderForMediaEditor",
    };

    static boolean isOurMethod(String s) {
        return METHOD_SCREEN.equals(s) || METHOD_IMAGE.equals(s)
                || METHOD_PING.equals(s) || METHOD_BITMAP.equals(s);
    }

    private Config() {
    }
}

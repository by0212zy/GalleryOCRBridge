package com.miui.gallery.ocrbridge;

/**
 * 常量集中处。所有 Hook 点按字符串反射查找，尽量不绑定具体相册版本。
 *
 * 反编译小米相册 4.3.1.13 确认的本地 OCR 链路：
 *   com.xiaomi.ocr.sdk_ocr.OCREngine
 *     .getInstance()                      静态，内部 loadLibrary(miocr / miocr_wrapper)
 *     .init(String cacheDir, String libDir) -> boolean   相册自己传 getCacheDir().getPath(), null
 *     .doOCR(Bitmap) -> OCRData$OCRResult                吃内存 Bitmap，不需要图片文件
 *     OCRData$OCRResult.total_text (public String)      识别出的全文
 */
final class Config {
    static final String TAG = "GalleryOCR";
    static final String TARGET_PKG = "com.miui.gallery";

    static final String ENGINE_CLASS = "com.xiaomi.ocr.sdk_ocr.OCREngine";
    static final String RESULT_FIELD = "total_text";

    /** content call 的 method 名（同步返回文本，推荐）。 */
    static final String METHOD_SCREEN = "extract_screen_text";
    static final String METHOD_IMAGE = "extract_image_text";
    static final String METHOD_PING = "ping";

    /** am broadcast 的 action（异步，结果靠回调广播 / 写文件 / 剪贴板）。 */
    static final String ACTION_SCREEN = "com.miui.gallery.ocr.action.EXTRACT_SCREEN";
    static final String ACTION_IMAGE = "com.miui.gallery.ocr.action.EXTRACT_IMAGE";

    static final String EXTRA_PATH = "path";
    static final String EXTRA_CALLBACK = "callback";
    static final String EXTRA_OUT = "out";
    static final String EXTRA_CLIP = "clip";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_SUCCESS = "success";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_SOURCE = "source";

    /** 无权限、exported 的相册 Provider。content call 会顺带把相册进程拉起来。 */
    static final String CALL_URI = "content://com.miui.gallery.widget";
    /** 备用：同样 exported 且无权限。 */
    static final String CALL_URI_BACKUP = "content://com.miui.gallery.mediaeditor.authority";

    /**
     * 内存管道入口：shell 端 {@code screencap -p | content write --uri <INPUT_URI>}
     * 把 PNG 写进相册进程内存，随后 extract_screen_text 直接消费，全程零临时文件。
     * 这样截屏由有权限的 shell 进程完成，不依赖相册 app 进程的截屏权限 / su 提权。
     */
    static final String INPUT_PATH = "ocr_input";
    static final String INPUT_URI = "content://com.miui.gallery.widget/ocr_input";
    static final long INPUT_WAIT_MS = 4000L;

    /** 覆写了 call() 的相册 Provider，一并 hook 以覆盖备用 URI。 */
    static final String[] PROVIDER_CLASSES = {
            "com.miui.gallery.provider.GalleryWidgetOpenProvider",
            "com.miui.gallery.provider.GalleryOpenProvider",
            "com.miui.mediaeditor.api.GalleryProviderForMediaEditor",
    };

    static boolean isOurMethod(String s) {
        return METHOD_SCREEN.equals(s) || METHOD_IMAGE.equals(s) || METHOD_PING.equals(s);
    }

    private Config() {
    }
}

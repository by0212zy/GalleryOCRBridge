package com.miui.gallery.ocrbridge;

/**
 * 常量集中处。Hook 点按签名反射查找，不绑定具体相册 / FV 版本。
 *
 * 小米相册 4.3.1.13 本地 OCR 链路：
 *   com.xiaomi.ocr.sdk_ocr.OCREngine
 *     .getInstance()                               内部 loadLibrary(miocr / miocr_wrapper)
 *     .init(String cacheDir, String libDir)        相册自己传 getCacheDir().getPath(), null
 *     .doOCR(Bitmap) -> OCRData$OCRResult          吃内存 Bitmap，不需要图片文件
 *     OCRData$OCRResult.total_text (String)        识别出的全文
 *
 * FV 悬浮球 1.6.4 本地 OCR 链路：
 *   z1/e.recognize(Bitmap) -> String              统一入口，返回合并后的纯文本
 *   BaiduOCREngine / z1.c                        具体实现与语言包未下载时的空壳
 *
 * 两个 App 不同进程，相册的 libmiocr.so 与 OCREngine 是私有的，FV 无法直接加载。
 * 所以：相册进程做服务端（content call），FV 进程做客户端（跨进程送 Bitmap）。
 *
 * 正式版只保留真机日志确认命中的链路（v2.0.1 仅调整界面入口，链路不变）：
 *   extract_bitmap（FV 桥接，主链路）/ extract_screen_text + 内存管道（shell 主命令）
 *   / extract_image_text（兜底）/ ping（引擎状态）/ 广播（对外异步入口）
 */
final class Config {
    static final String TAG = "GalleryOCR";

    /** 服务端：小米相册。 */
    static final String TARGET_PKG = "com.miui.gallery";
    /** 客户端：FV 悬浮球。 */
    static final String FV_PKG = "com.fooview.android.fooview";

    static final String ENGINE_CLASS = "com.xiaomi.ocr.sdk_ocr.OCREngine";
    static final String RESULT_FIELD = "total_text";

    /** content call 的 method 名（同步返回文本）。 */
    static final String METHOD_SCREEN = "extract_screen_text";
    static final String METHOD_IMAGE = "extract_image_text";
    static final String METHOD_PING = "ping";
    /** 内部 method：App 把 Bitmap 放进 Bundle 交给相册 OCR（FV 桥接专用）。 */
    static final String METHOD_BITMAP = "extract_bitmap";

    /** am broadcast 的 action（异步）。 */
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

    /** 内存管道：screencap -p | content write --uri &lt;INPUT_URI&gt;，全程零临时文件。 */
    static final String INPUT_PATH = "ocr_input";
    static final String INPUT_URI = "content://com.miui.gallery.widget/ocr_input";
    static final long INPUT_WAIT_MS = 4000L;

    /** FV 侧一次跨进程识别的整体超时；超时回退到 FV 自带本地 OCR，功能不坏。 */
    static final long BRIDGE_TIMEOUT_MS = 8000L;

    /** FV 悬浮球里真正实现 recognize(Bitmap) 的引擎类。 */
    static final String[] FV_ENGINE_CLASSES = {
            "com.baidu.paddle.lite.demo.ocr.BaiduOCREngine",
            "z1.c",
    };

    static boolean isOurMethod(String s) {
        return METHOD_SCREEN.equals(s) || METHOD_IMAGE.equals(s)
                || METHOD_PING.equals(s) || METHOD_BITMAP.equals(s);
    }

    private Config() {
    }
}

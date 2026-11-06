# GalleryOCRBridge

Hook 小米相册的**本地 OCR 引擎**，做两件事：

1. 把「提取当前屏幕文字」做成一条 shell 命令，供其它 App 调用；
2. **v1.2.0 新增**：让 FV 悬浮球的「自动文字识别」无感改走小米相册 OCR，不用下载它自带的百度语言包、不用给它截屏权限、零临时文件。

基于 [libxposed 现代 API 102](https://github.com/libxposed/api)，LSPosed 模块，无界面、无启动图标。不修改系统属性、不改 Settings 数据库。**作用域勾选两个**：`com.miui.gallery`（系统相册）+ `com.fooview.android.fooview`（FV 悬浮球）。

> **v1.1.0 重要修复**：v1.0.0 让相册 app 进程自己截屏，但普通 app 进程没有 `READ_FRAME_BUFFER`，截屏被 SurfaceFlinger 拒绝；`su` 从 app 进程提权又会卡在 Magisk 授权，命令会挂 20 秒后无输出。现在改为**由 shell 进程截屏**（shell uid 天然有截屏权限），PNG 通过内存管道直接送进相册进程，仍然零临时文件、不需要 root 提权。

---

## v1.2.0：FV 悬浮球「自动文字识别」改用小米相册 OCR

### 先回答：能直接调用小米相册的识别库吗？

**不能直接调用，但能无感用上，效果一样。**

反编译 FV 悬浮球 1.6.4 与小米相册 4.3.1.13 后确认，两边是**两个不同 App、两套不同进程、两个私有引擎**：

| | FV 悬浮球 | 小米相册 |
| --- | --- | --- |
| 引擎 | 百度 PaddleLite（`BaiduOCREngine`） | 小米自研 `libmiocr.so`（`OCREngine`） |
| 数据 | `ch_ppocr_mobile_v2.0_*.nb` 模型 + **要下载语言包** | so / 模型随相册内置，**开箱即用** |
| 入口 | `z1/e.recognize(Bitmap) → String` | `OCREngine.doOCR(Bitmap) → total_text` |
| 进程 | `com.fooview.android.fooview`（识别在 `:circle`） | `com.miui.gallery` |

小米相册的 `libmiocr.so` 和 `OCREngine` 类是**相册私有**的，装在相册的 dex / nativeLibraryDir 里，FV 进程既加载不到类、也 `loadLibrary` 不到 so。所以"直接调库"做不到。

但两个引擎的**接口形状完全一致**：都是吃一个内存 `Bitmap`、吐一段纯文本。于是本模块在中间架了一座桥：

```
FV 自动文字识别（用户已授权的 MediaProjection 截图，现成 Bitmap）
        │  Hook BaiduOCREngine.recognize(Bitmap) / z1-c.recognize(Bitmap)
        ▼  把 Bitmap 跨进程送进相册（Binder ashmem，一次调用）
小米相册 OCREngine.doOCR(Bitmap) → total_text
        ▼  文本作为 recognize() 的返回值交回 FV
FV 照常显示 / 复制 / 搜索 / 分享 / 翻译，全程无感
```

**好处**：FV 不再需要下载百度语言包（连"语言包未下载时空壳返回 null"的 `z1/c` 也一起接管）；不用 `screencap` 权限、不用 root、**不产生任何临时图片文件**——因为 FV 自己已经把屏幕截成 Bitmap 了，我们只是把它转交给相册。

**三级保险，识别功能永不失效**：通道 A（Bundle 直传 Bitmap）→ 通道 B（`ocr_input` 内存管道）→ 都失败就 `chain.proceed()` 回退到 FV 自带 PaddleOCR。也就是相册没勾作用域 / 引擎没就绪 / 跨进程超时，FV 最多退回原来的行为，不会变坏。

### 怎么用

1. LSPosed 里给本模块**勾选两个作用域**：系统相册 `com.miui.gallery`、FV 悬浮球 `com.fooview.android.fooview`。
2. 启用模块，**重启手机**（或至少强停相册 + FV 悬浮球）。
3. 建议先在相册里成功用过一次「提取文字」，让本地 OCR so 就绪。
4. 之后直接用 FV 原来的「自动文字识别 / 获取屏幕文字 / 识别图片中的文字」，出来的就是小米相册的识别结果，**不需要输入任何命令**。

日志筛 `GalleryOCR`，看到 `FV 接管成功，小米相册 OCR 文本长度=NN` 即表示走的相册引擎；看到 `FV 桥接...回退本地 OCR` 说明桥接没成功（多半是两个作用域没勾全），此时 FV 仍用自带引擎，功能不受影响。

**只想用 shell、不想 Hook FV？** FV 自带「执行 Shell 命令，输出命令返回的信息」动作，新建一个动作把下面「主命令」填进去即可，零开发、不用升级。注意 FV 的 shell 是 app uid，需要 root 并用 `su -c 'screencap -p'`，详见下方主命令。而 **v1.2.0 的 Hook 方式不需要这些**——它用 FV 自己截好的 Bitmap。

---

## 先回答：要不要临时截图？

**不需要临时截图文件。** 但截屏这个动作本身省不掉——OCR 要拿到屏幕像素。

反编译小米相册 4.3.1.13 后确认，它的「提取文字」用自研本地 OCR：

```
com.xiaomi.ocr.sdk_ocr.OCREngine
  .getInstance()                                 内部 loadLibrary(libmiocr / libmiocr_wrapper)
  .init(getCacheDir().getPath(), null) -> boolean
  .doOCR(android.graphics.Bitmap) -> OCRData$OCRResult
  OCRData$OCRResult.total_text  (public String)  ← 识别出的全文
```

关键点是 **`doOCR` 吃的是内存里的 `Bitmap`，不是图片文件**。于是整条链路可以完全在内存里流转：

```
shell: screencap -p  →  PNG 字节  →  content write (pipe)  →  相册进程内存 byte[]  →  Bitmap  →  native OCR  →  文本
```

| | 说明 |
| --- | --- |
| 图片文件 | ❌ 不需要 |
| 临时截图文件 | ❌ 不需要，PNG 走管道 |
| 截屏动作 | ✅ 需要，但由 **shell 进程**完成（有权限），相册进程只负责 OCR |
| root 提权 | ❌ 不需要（adb / 系统 shell 下） |
| 生命周期 | Bitmap 用完立即 `recycle()`，全程零落盘 |

---

## Shell 命令（可直接复制）

### 主命令 · 提取当前屏幕文字，同步返回

**在设备上（adb shell / 系统 shell）：**

```sh
screencap -p | content write --uri content://com.miui.gallery.widget/ocr_input && content call --uri content://com.miui.gallery.widget --method extract_screen_text
```

**从电脑一条命令：**

```sh
adb shell 'screencap -p | content write --uri content://com.miui.gallery.widget/ocr_input && content call --uri content://com.miui.gallery.widget --method extract_screen_text'
```

输出示例（`content call` 同步打印 Provider 返回的 Bundle）：

```
Bundle[{text=设置  搜索设置  网络和连接  WLAN  已连接 ... , success=true, source=screen:pipe}]
```

> **Termux 用户**：Termux 的 shell 是 app uid，`screencap` 会被拒。有 root 时把 `screencap -p` 换成 `su -c 'screencap -p'` 即可：
> ```sh
> su -c 'screencap -p' | content write --uri content://com.miui.gallery.widget/ocr_input && content call --uri content://com.miui.gallery.widget --method extract_screen_text
> ```

### 兜底命令 · 临时截图即删（最稳，任何环境都可用）

`content write` 在极个别 ROM 上不可用时，用这条。截图写到 `/sdcard/Pictures` 后立刻读取、用完 `rm` 删除，不残留：

```sh
screencap -p /sdcard/Pictures/.ocr_tmp.png && content call --uri content://com.miui.gallery.widget --method extract_image_text --extra path:s:/sdcard/Pictures/.ocr_tmp.png && rm /sdcard/Pictures/.ocr_tmp.png
```

这条同时也是**验证 OCR 引擎是否正常**的最快方式。

### 对已有图片提取文字

```sh
content call --uri content://com.miui.gallery.widget --method extract_image_text --extra path:s:/sdcard/Pictures/a.png
```

### 自检（不截屏，只看 OCR 引擎是否就绪）

```sh
content call --uri content://com.miui.gallery.widget --method ping
```

```
Bundle[{text=engine=OK, ocrSoLoaded=true, sdk=35, abi=arm64-v8a, success=true, source=ping}]
```

`ocrSoLoaded=false` 说明本地 OCR 的 so 没加载成功，见「前提与排查」。

### 备用 URI（主 URI 不通时把两处 `com.miui.gallery.widget` 一起换掉）

```
content://com.miui.gallery.mediaeditor.authority
```

### 返回字段

| key | 类型 | 含义 |
| --- | --- | --- |
| `success` | boolean | 是否成功 |
| `text` | String | 识别出的全文（失败时为空串） |
| `error` | String | 失败原因（仅失败时） |
| `source` | String | `screen:pipe` / `screen:app` / `image:<路径>` / `bitmap:app` / `ping` |

`source=screen:pipe` 表示用的是主通道；`screen:app` 表示退回了相册进程内截屏（可能被权限拒绝）；`bitmap:app` 表示 Bitmap 由其它 App（如 FV 悬浮球）直接传入。

---

## 广播方式（异步，适合 Tasker / MacroDroid / 其它 App）

`am broadcast` 无法同步回传文本，结果通过参数指定去向，**三个参数可叠加**：

```sh
# 结果作为广播发回（其它 App 注册接收 com.example.OCR_RESULT 的 Receiver）
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN --es callback com.example.OCR_RESULT

# 结果写入文件
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN --es out /sdcard/Download/ocr.txt

# 结果写入剪贴板（后台应用写剪贴板在部分 ROM 受限）
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN --ez clip true

# 对已有图片
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_IMAGE --es path /sdcard/Pictures/a.png
```

什么都不给也会把文本打到 logcat（`tag: GalleryOCR`）。广播路径若没有先走 `content write`，会回退到相册进程内截屏，成功率低于主通道，建议配合管道或直接用 `content call`。

---

## 其它 App 怎么调用

### 方式一：ContentResolver（推荐，同步）

先写截图到内存管道，再 `call` 拿文本。App 自己有截图能力（MediaProjection / 自己的 View）时最好用：

```java
// 1) 把截图 PNG 写入内存管道（不落盘）
try (OutputStream os = getContentResolver().openOutputStream(
        Uri.parse("content://com.miui.gallery.widget/ocr_input"), "w")) {
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
}

// 2) 同步取文字
Bundle reply = getContentResolver().call(
        Uri.parse("content://com.miui.gallery.widget"),
        "extract_screen_text", null, new Bundle());
String text = reply == null ? "" : reply.getString("text", "");
```

已有图片时更简单：

```java
Bundle args = new Bundle();
args.putString("path", "/sdcard/Pictures/a.png");
Bundle reply = getContentResolver().call(
        Uri.parse("content://com.miui.gallery.widget"),
        "extract_image_text", null, args);
```

### 方式二：sendBroadcast（异步，结果走回调广播）

```java
Intent i = new Intent("com.miui.gallery.ocr.action.EXTRACT_SCREEN");
i.putExtra("callback", "com.example.OCR_RESULT");
sendBroadcast(i);
```

### 方式三：Termux / Tasker / MacroDroid

直接复制上面的主命令，用 `%stdout` 接住输出。只取文本：

```sh
content call --uri content://com.miui.gallery.widget --method extract_screen_text \
  | sed -n 's/.*text=\(.*\), success=true, source=.*/\1/p'
```

---

## 前提与排查

1. **LSPosed 作用域勾选「系统相册 / 小米相册」`com.miui.gallery`**；要用 FV 自动识别接管（v1.2.0）再勾上 **FV 悬浮球 `com.fooview.android.fooview`**。启用模块后**重启手机**（或至少强停相册 / FV 悬浮球）。只用 shell 命令的话勾相册一个就够。
2. **建议先在相册里成功用过一次「提取文字」**，确保本地 OCR 的 so / 模型就绪。`ping` 显示 `ocrSoLoaded=true` 即可。
3. **`screencap` 权限**：adb shell / 系统 shell 下直接可用；Termux 需 root 并改用 `su -c 'screencap -p'`。
4. **v1.1.0 起全链路都有日志**，LSPosed 日志筛 `GalleryOCR` 就能看到每一步：
   ```
   ★ content call 命中 method=extract_screen_text
   截图管道已建立，等待 shell 写入
   管道收到截图 386214 字节，解码成功
   取用管道截图 1200x2670
   送入 OCR，Bitmap=1200x2670 config=ARGB_8888
   找到引擎类 ... / getInstance() 成功 / init(...) 返回 true / doOCR 返回结果类 ...
   OCR 完成，文本长度=87
   ```
   哪一行断了就是哪一步的问题。也可以用 `adb logcat -s GalleryOCR`。
5. 返回 `success=false` 时看 `error` 字段：
   - 「进程内截屏失败」→ 说明没走管道，检查主命令是否完整、`content write` 是否报错；
   - 「OCREngine.init 失败」→ 见第 2 条；
   - 「无法读取图片」→ 路径或权限问题，换 `/sdcard/Pictures/` 下的路径试试。

---

## 技术原理

- `META-INF/xposed/java_init.list` 声明入口 `com.miui.gallery.ocrbridge.ModuleMain`，`scope.list` 写 `com.miui.gallery` + `com.fooview.android.fooview`，`staticScope=false`。`onPackageReady` 按包名分流，两个进程互不干扰。
- **相册进程（服务端）**：Hook `android.content.ContentProvider` 的：
  - `call()` 两个重载 → 命中 `extract_screen_text` / `extract_image_text` / `extract_bitmap` / `ping` 时同步返回结果 Bundle；
  - `openFile()` / `openAssetFile()` → URI 含 `ocr_input` 时返回 `ParcelFileDescriptor.createPipe()` 的写端，读端由后台线程解码成 Bitmap 存内存。
  其余一律 `chain.proceed()`，不影响相册自身逻辑。
- **FV 进程（客户端）**：Hook FV 里所有「`(Bitmap) → String`、非抽象」的 `recognize` 方法（含百度 `BaiduOCREngine` 与语言包未下载时的空壳 `z1/c`），把入参 Bitmap 跨进程交给相册 OCR，`total_text` 作为返回值。三级回退保证 FV 原功能不坏。
- OCR 完全靠反射调用相册自带 `OCREngine`，不打包任何第三方 OCR / so / 模型，APK 仅 30 KB 左右。因为运行在相册进程，宿主的 `libmiocr.so` 天然可被 `System.loadLibrary` 找到。
- 跨进程 Bitmap 走 Binder `Parcelable` + ashmem（不受 1MB 事务限制）；`HARDWARE` / mutable Bitmap 会先转成 `ARGB_8888` 只读副本再送进 native 层，避免像素不可读。
- 广播接收器是动态注册的 exported Receiver（Android 13+ 用 `RECEIVER_EXPORTED`），延后到应用就绪之后注册，避免早期触发宿主 `<clinit>`。

## 构建

```sh
./gradlew assembleRelease
```

产物 `app/build/outputs/apk/release/app-release.apk`。GitHub Actions 在 push / tag 时自动构建 release APK 并上传 artifact。

## 许可与说明

仅用于本机自动化、无障碍辅助等个人用途。识别结果来自小米相册自带的本地 OCR，本模块不联网、不上传任何内容。

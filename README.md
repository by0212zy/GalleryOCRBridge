# GalleryOCRBridge · 相册文字提取桥

Hook 小米相册的**本地 OCR 引擎**，做两件事：

1. 把「提取当前屏幕文字」做成一条 shell 命令，供其它 App / Tasker / Termux / adb 调用；
2. 让 **FV 悬浮球**的「自动文字识别」无感改走小米相册 OCR，不用下载它自带的百度语言包、不用给它截屏权限、零临时文件。

基于 [libxposed 现代 API 102](https://github.com/libxposed/api) 的 LSPosed 模块。**内置说明界面**（桌面图标「相册文字提取桥」），不修改系统属性、不改 Settings 数据库、不联网、不打包任何第三方 OCR / so / 模型。作用域勾选两个：`com.miui.gallery`（系统相册）+ `com.fooview.android.fooview`（FV 悬浮球）。

---

## v2.0.0 正式版更新

### 新增：说明界面

打开桌面图标即可看到完整的模块说明：

- 模块功能、使用前提、返回字段、FV 接管说明、排查方式；
- **全部 shell 命令一键复制**（也支持长按选中）；
- **「运行自检」按钮**：直接向相册发起一次 `ping`，确认模块是否已在 LSPosed 生效、本地 OCR 引擎是否就绪；
- 深浅色自适应，纯 framework API，界面只运行在模块自身进程，**与相册 / FV 进程里的 Hook 完全隔离**。

命令同时写进了 LSPosed 模块描述（模块列表里可直接看到）和本 README。

### 精简：删除从未命中的 Hook 与死代码

依据真机 LSPosed 日志逐条核对后，只保留真正生效的链路：

| 删除项 | 依据 |
| --- | --- |
| `ScreenCapture.java` 整个类（进程内截屏三级回退，175 行） | 日志中从未命中；且 v1.0.0 正是它因 `READ_FRAME_BUFFER` 被拒让命令挂 20 秒 |
| 对 3 个相册 Provider 子类的额外 `call` / `openFile` Hook | 反编译确认 `GalleryWidgetOpenProvider` 未覆写 `call()`，全部命中都发生在基类 `ContentProvider` |
| 备用 URI `com.miui.gallery.mediaeditor.authority` 及 FV 侧重试分支 | 主 URI `extract_bitmap` 60 次全部成功，备用从未使用 |
| FV 桥接的「通道 B：`ocr_input` 管道」 | `extract_bitmap` 通道 27/27 成功，通道 B 一次都没走到 |
| `Host` / `Diag` 中未被调用的工具方法、非目标包的调试日志 | 死代码 / 日志噪音 |
| 给无关进程（`com.miuix.editor`、`com.miui.contentcatcher`、gms、webview 等）装 Hook | 分流改为严格按两个目标包判断，其它包直接跳过 |

**保留的生效链路**（日志命中次数）：`extract_bitmap` 60 次、`extract_screen_text` 14 次（全部走内存管道）、`extract_image_text` 4 次，以及 `ping` 与广播两个对外入口。净减约 200 行，功能不变。

---

## 先回答：要不要临时截图？

**不需要临时截图文件，但截屏动作本身省不掉**——OCR 需要拿到屏幕像素。

反编译小米相册 4.3.1.13 确认，它的「提取文字」用自研本地 OCR：

```
com.xiaomi.ocr.sdk_ocr.OCREngine
  .getInstance()                                 内部 loadLibrary(libmiocr / libmiocr_wrapper)
  .init(getCacheDir().getPath(), null) -> boolean
  .doOCR(android.graphics.Bitmap) -> OCRData$OCRResult
  OCRData$OCRResult.total_text  (public String)  ← 识别出的全文
```

关键是 **`doOCR` 吃的是内存里的 `Bitmap`，不是图片文件**，所以整条链路可以完全在内存里流转：

```
shell: screencap -p → PNG 字节 → content write (pipe) → 相册进程内存 byte[] → Bitmap → native OCR → 文本
```

| | 说明 |
| --- | --- |
| 图片文件 / 临时截图 | ❌ 不需要，PNG 走内存管道 |
| 截屏动作 | ✅ 需要，但由 **shell 进程**完成（shell uid 天然有权限） |
| root 提权 | ❌ 不需要（adb / 系统 shell 下）；Termux 是 app uid，需 root |
| 生命周期 | Bitmap 用完立即 `recycle()`，全程零落盘 |

---

## Shell 命令（可直接复制；App 内也可一键复制）

### ① 主命令 · 提取当前屏幕文字，同步返回

```sh
screencap -p | content write --uri content://com.miui.gallery.widget/ocr_input && content call --uri content://com.miui.gallery.widget --method extract_screen_text
```

从电脑一条命令：

```sh
adb shell 'screencap -p | content write --uri content://com.miui.gallery.widget/ocr_input && content call --uri content://com.miui.gallery.widget --method extract_screen_text'
```

输出示例（`content call` 同步打印 Provider 返回的 Bundle）：

```
Bundle[{text=设置  搜索设置  网络和连接  WLAN  已连接 ... , success=true, source=screen:pipe}]
```

> **Termux 用户**：Termux 的 shell 是 app uid，`screencap` 会被拒。有 root 时改用：
> ```sh
> su -c 'screencap -p' | content write --uri content://com.miui.gallery.widget/ocr_input && content call --uri content://com.miui.gallery.widget --method extract_screen_text
> ```

### ② 兜底命令 · 临时截图即删（最稳，任何环境可用）

`content write` 在极个别 ROM 不可用时用这条。截图写到 `/sdcard/Pictures` 后立刻读取、用完 `rm` 删除，不残留；这也是**验证 OCR 引擎是否正常**的最快方式。

```sh
screencap -p /sdcard/Pictures/.ocr_tmp.png && content call --uri content://com.miui.gallery.widget --method extract_image_text --extra path:s:/sdcard/Pictures/.ocr_tmp.png && rm /sdcard/Pictures/.ocr_tmp.png
```

### ③ 对已有图片提取文字

```sh
content call --uri content://com.miui.gallery.widget --method extract_image_text --extra path:s:/sdcard/Pictures/a.png
```

### ④ 自检（不截屏，只看 OCR 引擎是否就绪）

```sh
content call --uri content://com.miui.gallery.widget --method ping
```

```
Bundle[{text=engine=OK, ocrSoLoaded=true, sdk=35, abi=arm64-v8a, success=true, source=ping}]
```

`ocrSoLoaded=false` 说明本地 OCR 的 so 没加载成功，见「前提与排查」。App 内的「运行自检」按钮等价于这条命令。

### ⑤ 广播 · 异步（Tasker / MacroDroid / 其它 App）

`am broadcast` 无法同步回传文本，结果通过参数指定去向，**三个参数可叠加**：

```sh
# 结果作为广播发回（其它 App 注册接收 com.example.OCR_RESULT）
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN --es callback com.example.OCR_RESULT

# 结果写入文件
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN --es out /sdcard/Download/ocr.txt

# 结果写入剪贴板（后台应用写剪贴板在部分 ROM 受限）
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN --ez clip true

# 对已有图片
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_IMAGE --es path /sdcard/Pictures/a.png
```

什么都不给也会把文本打到 logcat（`tag: GalleryOCR`）。广播路径需要先走 `content write` 把截图送进内存管道，否则会返回「没有收到截图」；同步场景建议直接用 `content call`。

### 返回字段

| key | 类型 | 含义 |
| --- | --- | --- |
| `success` | boolean | 是否成功 |
| `text` | String | 识别出的全文（失败时为空串） |
| `error` | String | 失败原因（仅失败时） |
| `source` | String | `screen:pipe`（内存管道）/ `image:<路径>` / `bitmap:app`（其它 App 传入）/ `ping` |

---

## FV 悬浮球：自动文字识别改用小米相册 OCR

### 能直接调用小米相册的识别库吗？

**不能直接调库，但能无感用上，效果一样。** 反编译 FV 悬浮球 1.6.4 与小米相册 4.3.1.13 后确认，两边是**两个 App、两套私有引擎**：

| | FV 悬浮球 | 小米相册 |
| --- | --- | --- |
| 引擎 | 百度 PaddleLite（`BaiduOCREngine`） | 小米自研 `libmiocr.so`（`OCREngine`） |
| 数据 | `ch_ppocr_mobile_v2.0_*.nb` 模型 + **要下载语言包** | so / 模型随相册内置，**开箱即用** |
| 入口 | `z1/e.recognize(Bitmap) → String` | `OCREngine.doOCR(Bitmap) → total_text` |
| 进程 | `com.fooview.android.fooview`（识别在 `:circle`） | `com.miui.gallery` |

小米相册的 `libmiocr.so` 和 `OCREngine` 是相册私有的，FV 进程加载不到。但两个引擎的**接口形状完全一致**（都吃内存 Bitmap、吐纯文本），于是模块在中间架了一座桥：

```
FV 自动文字识别（自己已截好的 Bitmap）
        │  Hook BaiduOCREngine / z1-c 的 recognize(Bitmap)
        ▼  Bundle 直传 Bitmap，一次 Binder（ashmem）
小米相册 OCREngine.doOCR(Bitmap) → total_text
        ▼  文本作为 recognize() 的返回值交回 FV
FV 照常显示 / 复制 / 搜索 / 分享，全程无感
```

**好处**：不用下载 FV 的百度语言包（连「语言包未下载时返回 null」的空壳 `z1/c` 也一起接管）、不用 `screencap` 权限、不用 root、**零临时图片文件**。

**两级保险**：主通道 `extract_bitmap` 成功就用相册结果；相册没勾作用域 / 引擎未就绪 / 跨进程超时，则 `chain.proceed()` 回退到 FV 自带 PaddleOCR，识别功能不会变坏。

### 怎么用

1. LSPosed 里给本模块**勾选两个作用域**：系统相册 `com.miui.gallery`、FV 悬浮球 `com.fooview.android.fooview`。
2. 启用模块，**重启手机**（或至少强停相册 + FV 悬浮球）。
3. 建议先在相册里成功用过一次「提取文字」，让本地 OCR so 就绪。
4. 之后直接用 FV 原来的「自动文字识别」，出来的就是小米相册的识别结果，**不需要输入任何命令**。

日志筛 `GalleryOCR`，看到 `FV 接管成功，小米相册 OCR 文本长度=NN` 即表示走的是相册引擎。

**只想用 shell、不想 Hook FV？** FV 自带「执行 Shell 命令，输出命令返回的信息」动作，新建一个动作把上面「主命令」填进去即可（需 root 并用 `su -c 'screencap -p'`）。

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

也可以把 `Bitmap` 直接放进 Bundle，用内部 method `extract_bitmap` 一次调用拿到 `text`（模块给 FV 悬浮球用的就是这条）。

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

1. **LSPosed 作用域勾选「系统相册 / 小米相册」`com.miui.gallery`**；要用 FV 自动识别接管再勾上 **FV 悬浮球 `com.fooview.android.fooview`**。启用模块后**重启手机**（或至少强停相册 / FV 悬浮球）。只用 shell 命令的话勾相册一个就够。
2. **建议先在相册里成功用过一次「提取文字」**，确保本地 OCR 的 so / 模型就绪。`ping` 显示 `ocrSoLoaded=true`，或 App 内「运行自检」显示「模块已生效」即可。
3. **`screencap` 权限**：adb shell / 系统 shell 下直接可用；Termux 需 root 并改用 `su -c 'screencap -p'`。
4. **全链路都有日志**，LSPosed 日志筛 `GalleryOCR` 或 `adb logcat -s GalleryOCR`：
   ```
   ★ content call 命中 method=extract_screen_text
   截图管道已建立，等待 shell 写入
   管道收到截图 386214 字节，解码成功
   取用管道截图 1200x2670
   送入 OCR，Bitmap=1200x2670 config=ARGB_8888
   找到引擎类 ... / getInstance() 成功 / init(...) 返回 true / doOCR 返回结果类 ...
   OCR 完成，文本长度=87
   ```
   哪一行断了就是哪一步的问题。
5. 返回 `success=false` 时看 `error` 字段：
   - 「没有收到截图」→ 主命令的 `content write` 那段没成功，检查命令是否完整，或改用兜底命令；
   - 「OCREngine.init 失败」→ 见第 2 条；
   - 「无法读取图片」→ 路径或权限问题，换 `/sdcard/Pictures/` 下的路径试试。

---

## 技术原理

- `META-INF/xposed/java_init.list` 声明入口 `com.miui.gallery.ocrbridge.ModuleMain`，`scope.list` 写 `com.miui.gallery` + `com.fooview.android.fooview`，`staticScope=false`。`onPackageReady` **严格按包名分流**，其它包直接跳过。
- **相册进程（服务端）**：只 Hook 基类 `android.content.ContentProvider` 的 `call()` 重载（命中 `extract_screen_text` / `extract_image_text` / `extract_bitmap` / `ping` 时同步返回结果 Bundle）与 `openFile()` / `openAssetFile()`（URI 含 `ocr_input` 时返回 `ParcelFileDescriptor.createPipe()` 的写端，读端由后台线程解码成 Bitmap 存内存）。其余一律 `chain.proceed()`，不影响相册自身逻辑。
- **FV 进程（客户端）**：Hook FV 里所有「`(Bitmap) → String`、非抽象」的 `recognize` 方法（含百度 `BaiduOCREngine` 与语言包空壳 `z1/c`），用 `extract_bitmap` 把 Bitmap 跨进程交给相册 OCR，`total_text` 作为返回值；失败回退 FV 自带引擎。
- OCR 完全靠反射调用相册自带 `OCREngine`，不打包任何第三方 OCR / so / 模型。因为运行在相册进程，宿主的 `libmiocr.so` 天然可被 `System.loadLibrary` 找到。
- 跨进程 Bitmap 走 Binder `Parcelable` + ashmem（不受 1MB 事务限制）；`HARDWARE` / mutable Bitmap 会先转成 `ARGB_8888` 只读副本再送进 native 层。
- 广播接收器是动态注册的 exported Receiver（Android 13+ 用 `RECEIVER_EXPORTED`），延后到应用就绪后注册，避免早期触发宿主 `<clinit>`。
- 说明界面 `MainActivity` 是普通 Activity（无第三方 UI 依赖），只在模块自身进程运行，不参与 Hook。

## 构建

```sh
./gradlew assembleRelease
```

产物 `app/build/outputs/apk/release/app-release.apk`。GitHub Actions 在 push / tag 时自动构建 release APK 并上传 artifact；Release 附件在发版时手动上传带版本号的 APK 与源码包。

## 许可与说明

仅用于本机自动化、无障碍辅助等个人用途。识别结果来自小米相册自带的本地 OCR，本模块不联网、不上传任何内容。

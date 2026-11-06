# GalleryOCRBridge

Hook 小米相册的**本地 OCR 引擎**，把「提取当前屏幕文字」做成一条 shell 命令，供其它 App 调用。

基于 [libxposed 现代 API 102](https://github.com/libxposed/api)，LSPosed 模块，无界面、无启动图标。作用域只勾选 `com.miui.gallery`，不修改系统属性、不改 Settings 数据库、不 Hook 其它应用。

---

## 先回答：要不要临时截图？

**不需要临时截图文件。** 但截屏这个动作本身省不掉——OCR 要拿到屏幕像素。

反编译小米相册 4.3.1.13 后确认，它的「提取文字」用的是自研本地 OCR：

```
com.xiaomi.ocr.sdk_ocr.OCREngine
  .getInstance()                                 内部 loadLibrary(libmiocr / libmiocr_wrapper)
  .init(getCacheDir().getPath(), null) -> boolean
  .doOCR(android.graphics.Bitmap) -> OCRData$OCRResult
  OCRData$OCRResult.total_text  (public String)  ← 识别出的全文
```

关键点是 **`doOCR` 吃的是内存里的 `Bitmap`，不是图片文件**。所以：

| | 说明 |
| --- | --- |
| 图片文件 | ❌ 不需要。相册自己也是先把图片解码成 Bitmap 再送进引擎 |
| 临时截图文件 | ❌ 不需要。本模块把截屏结果直接留在内存：`SurfaceControl` 隐藏 API 拿 Bitmap，或 `screencap -p` 的 stdout 管道读进 `byte[]` 解码 |
| 截屏动作 | ✅ 需要。要把「当前屏幕」变成像素交给 OCR |
| 生命周期 | Bitmap 用完立即 `recycle()`，全程零落盘 |

也就是说，你担心的「必须先截个图存成文件再让相册识别」这一步被完全去掉了；命令执行完，手机里不会多出任何截图。

---

## Shell 命令（可直接复制）

### 主命令：提取当前屏幕文字，同步返回

```sh
content call --uri content://com.miui.gallery.widget --method extract_screen_text
```

输出示例（`content call` 会同步打印 Provider 返回的 Bundle）：

```
Bundle[{text=设置  搜索设置  网络和连接  WLAN  已连接 ... , success=true, source=screen}]
```

从电脑 adb 触发：

```sh
adb shell content call --uri content://com.miui.gallery.widget --method extract_screen_text
```

**为什么用 `content call`**：`com.miui.gallery.widget` 是相册里一个 `exported=true` 且无权限保护的 ContentProvider。这条命令会自动拉起相册进程（模块随之注入），而 `call()` 的返回值会同步打印到终端——正好用来回传文字。不需要常驻服务，也不需要 root 才能发命令。

### 备用 URI（主 URI 不通时换这个）

```sh
content call --uri content://com.miui.gallery.mediaeditor.authority --method extract_screen_text
```

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

`ocrSoLoaded=false` 说明本地 OCR 的 so 没加载成功，见下方「前提」。

### 返回字段

| key | 类型 | 含义 |
| --- | --- | --- |
| `success` | boolean | 是否成功 |
| `text` | String | 识别出的全文（失败时为空串） |
| `error` | String | 失败原因（仅失败时） |
| `source` | String | `screen` / `image:<路径>` / `ping` |

---

## 广播方式（异步，适合 Tasker / MacroDroid / 其它 App）

`am broadcast` 无法同步回传文本，所以结果通过参数指定去向。**以下三个参数可叠加使用**：

```sh
# 结果作为广播发回（其它 App 注册一个接收 com.example.OCR_RESULT 的 Receiver 即可）
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN \
  --es callback com.example.OCR_RESULT

# 结果写入文件
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN \
  --es out /sdcard/Download/ocr.txt

# 结果写入剪贴板（后台应用写剪贴板在部分 ROM 受限，失败只记日志）
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN --ez clip true
```

什么都不给也会把文本打到 logcat（`tag: GalleryOCR`）：

```sh
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN
```

对已有图片：

```sh
am broadcast -a com.miui.gallery.ocr.action.EXTRACT_IMAGE \
  --es path /sdcard/Pictures/a.png --es callback com.example.OCR_RESULT
```

回调广播里带的 extras 与上面「返回字段」一致（`text` / `success` / `error` / `source`）。

---

## 其它 App 怎么调用

### 方式一：ContentResolver（推荐，同步、无需 shell、无需 root）

任何 App 都能直接调，`try/catch` 一下即可：

```java
Bundle reply = getContentResolver().call(
        Uri.parse("content://com.miui.gallery.widget"),
        "extract_screen_text", null, new Bundle());
String text = reply == null ? "" : reply.getString("text", "");
```

指定图片：

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

### 方式三：Termux / Tasker / MacroDroid / shell 脚本

直接复制上面的 `content call` 命令。Termux 里想只取文本：

```sh
content call --uri content://com.miui.gallery.widget --method extract_screen_text \
  | sed -n 's/.*text=\(.*\), success=true, source=screen}.*/\1/p'
```

Tasker「运行 Shell」、MacroDroid「Shell 脚本」动作里填 `content call ...` 一行即可，用 `%stdout` 接住输出。

---

## 前提与排查

1. **LSPosed 作用域只勾选「系统相册 / 小米相册」(`com.miui.gallery`)**，启用模块后**重启手机**（或至少强停相册）。
2. **建议先在相册里成功用过一次「提取文字」**，确保本地 OCR 的 so / 模型环境就绪。如果 `ping` 显示 `ocrSoLoaded=false`，多半是这个原因。
3. **截屏权限**：模块按以下顺序尝试，全部只在内存里完成，不落盘：
   1. 反射 `SurfaceControl.captureDisplay / screenshot` 隐藏 API（LSPosed 注入进程一般已绕过 hidden API 限制）；
   2. `su -c "screencap -p"` 读 stdout（LSPosed 环境通常有 root，成功率最高）；
   3. 直接 `screencap -p` 读 stdout。
   
   如果三级都失败（极少数无 root 且严格限制截屏的 ROM），命令会返回明确错误。此时可用 `extract_image_text` 传已有图片，或在 shell 端自行截图后传路径：
   ```sh
   screencap -p /sdcard/Download/s.png && \
   content call --uri content://com.miui.gallery.widget --method extract_image_text \
     --extra path:s:/sdcard/Download/s.png && \
   rm /sdcard/Download/s.png
   ```
   这是唯一需要临时文件的场景，且用完即删。
4. **日志排查**：
   ```sh
   adb logcat -s GalleryOCR
   adb logcat | grep GalleryOCR
   ```
   LSPosed 里筛 `GalleryOCR` 也能看到模块载入、Hook 安装数量、每次 `content call` 命中记录。
5. 命令返回 `success=false` 时看 `error` 字段；常见是「Application 未就绪」（相册刚被拉起，稍等重试）或「OCR so 未加载」（见第 2 条）。

---

## 技术原理

- 模块通过 `META-INF/xposed/java_init.list` 声明入口 `com.miui.gallery.ocrbridge.ModuleMain`，`scope.list` 只写 `com.miui.gallery`，`staticScope=false`。
- `onPackageReady` 时 Hook `android.content.ContentProvider.call()` 的两个重载（并补 Hook 覆写了 `call` 的具体 Provider）。收到 `extract_screen_text` / `extract_image_text` / `ping` 就提前返回结果 Bundle，其余一律放行，不影响相册自身逻辑。
- OCR 完全靠反射调用相册自己的 `OCREngine`，不打包任何第三方 OCR / so / 模型，模块 APK 只有几十 KB。因为运行在相册进程，宿主的 `libmiocr.so` 天然可被 `System.loadLibrary` 找到。
- `HARDWARE` Bitmap 会先转成 `ARGB_8888` 再送进 native 层，避免像素不可读。
- 广播接收器是动态注册的 exported Receiver（Android 13+ 用 `RECEIVER_EXPORTED`），且刻意延后到应用就绪之后注册，避免早期触发宿主 `<clinit>`。

## 构建

```sh
./gradlew assembleRelease
```

产物 `app/build/outputs/apk/release/app-release.apk`。GitHub Actions 会在 push / tag 时自动构建 release APK 并上传 artifact。

## 许可与说明

仅用于本机自动化、无障碍辅助等个人用途。识别结果来自小米相册自带的本地 OCR，本模块不联网、不上传任何内容。

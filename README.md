# SRT 画质测试（com.fz.srttest）

按竞品 silu 的推流链路重写的**独立测试版**，用来和正式版（WebRTC 链路）对比 PC 端画质。
与正式版包名不同，可同时安装。背景见 `yql-android/docs/竞品画质调研-silu.md`。

## 链路

```
Camera2 录像模板（固定帧率 / 连续视频对焦 / 关闭电子+光学防抖）
  → 预览 TextureView + 硬件 H.264 编码器输入 Surface（零拷贝）
  → H.264：默认 High@4.2 / VBR / 8000kbps / QP≤30 / 1 秒关键帧
  → MPEG-TS（自写封装：PAT/PMT/PES+PCR+AUD，单视频流）
  → SRT（live 模式，延迟 50ms）→ MediaMTX → 浏览器 / VLC
```

编码器不认某些参数时会逐级降级（界面「降级档」显示最终生效的是哪一档）：

1. 全部参数 + 高通私有 QP 键
2. 全部参数（标准 QP 键 `video-qp-*-max`，Android 12+ 才被系统识别）
3. 去掉 QP 上限
4. 去掉 Profile/Level
5. 最简配置

## 使用

1. 填服务器 IP、SRT 端口（默认 8890）、流名、推流账号密码（服务器没设账号可留空）。
2. 编码参数默认就是 silu 的配置，可改分辨率 / 帧率 / 码率 / QP 上限 / 码率模式 / Profile / 关键帧间隔 / 色彩范围 / 是否关防抖 / SRT 延迟做 A/B。
3. 点「开始推流」。左上角每秒刷新：编码器名、降级档、实际 Profile、实际输出码率与帧率、平均 QP（Android 13+）、SRT 发送码率 / RTT / 丢包 / 重传 / 丢弃。
4. PC 观看（推流几秒后）：
   - 浏览器：`http://服务器IP:8889/流名`
   - VLC / ffplay：`srt://服务器IP:8890?streamid=read:流名`
   - 例：`ffplay -fflags nobuffer "srt://服务器IP:8890?streamid=read:test1"`

横屏使用（Activity 锁定横屏，画面方向按横持手机采集）。

## 服务器

MediaMTX 默认端口：SRT `8890/udp`，WebRTC 播放页 `8889/tcp` + `8189/udp`。云服务器安全组需放行。
推流鉴权在 MediaMTX 的 `authInternalUsers` 里配，**账号密码不要写进本仓库**（本仓库公开）。

## 构建

与正式版相同环境：AGP 8.9.1 / Kotlin 2.2.10 / compileSdk 36 / minSdk 24 / JDK 17。

```
gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

## 本地自检（TS 封装）

```
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -t 4 -c:v libx264 -profile:v high -bf 0 -g 30 -f h264 app/build/tstest/in.h264
gradlew testDebugUnitTest    # 生成 app/build/tstest/out.ts
ffmpeg -v warning -err_detect explode -i app/build/tstest/out.ts -f null -   # 无输出 = 封装无误
```

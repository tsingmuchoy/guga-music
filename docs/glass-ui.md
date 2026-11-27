# 液态玻璃 UI 与验证

本轮优化覆盖共用玻璃引擎、所有玻璃主题、主题选择页和构建检查。

- 折射量从 5dp 收至 4dp，并按控件短边限制斜面宽度与折射量，避免小芯片整体扭曲。
- 色散从 0.5 收至 0.16，模糊从 6dp 收至 4dp，高光强度从 1 收至 0.72。
- 边缘高光按屏幕密度计算；轻微表面反射和 12% 主题介质色提供暗部层次。
- API 26–32 保留主题渐变回退，减少白色蒙层与硬描边；Material You 保持纯色表面。
- 保留芯片容器透明、选中图标和文字变亮的现有交互。
- 主题页增加静态矢量材质色板、材质说明和无障碍选中描述；glass 显示名改为「液态玻璃」，存储 ID 不变。

## 稳定性

`BackdropCapture` 只临时隐藏可见视图，避免把 GONE/INVISIBLE 控件恢复为 VISIBLE；异常路径恢复画布与采样深度。
采样期间阻止其他层级的透镜再次录制，避免跨容器递归。
`GlassLensDelegate` 在换底衬、分离视图、软件绘制和渲染失败时保留/恢复原背景、轮廓和高光覆盖层可见性。
染色只在效果变脏后重建时提交，删除每帧无效的 uniform 更新。

## 检查

GitHub Actions 的 `Android build and glass checks` 工作流执行资源编译、Java 编译、DEX 和 APK 打包，并在 Android 8.0 / Android 13 模拟器运行无第三方依赖的 instrumentation：

- 验证隐藏控件不会被采样显现，异常后采样状态正常恢复。
- Android 13 上实际编译 AGSL，验证换底衬、释放与软件绘制回退。
- 打开真实主题页，保存「液态玻璃」和 Material You 截图供视觉检查。

本地具备 JDK 17、SDK 34 与已启动的模拟器后可运行：

```bash
export ANDROID_SDK=/path/to/android-sdk
export BT_VER=34.0.0 PLAT_VER=34
bash build.sh 1 1.53.0-dev
bash tools/test-android.sh
```

检查产物位于 Actions 的 `android-26-checks` / `android-33-checks` 附件。
开发包使用临时调试签名，不能覆盖已发布的正式签名安装包；本轮源码更新不发布 APK Release。
实际手机的 GPU、字体缩放、长列表滚动和播放时表现仍需真机确认，模拟器检查不代表真机帧率结论。

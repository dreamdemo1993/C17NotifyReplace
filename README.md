# C17NotifyReplace（C17 通知替换）

本仓库为自用改动线，用于个人设备。

## 命名

| 项目 | 值 |
| --- | --- |
| 项目 / 英文名 | `C17NotifyReplace` |
| 桌面显示名 | `C17 通知替换` |
| 应用 ID / namespace | `com.c17.notify.replace` |
| Xposed 入口（注解声明） | `com.c17.notify.replace.hook.ModuleMain` |
| Xposed 入口（KSP 生成） | `com.c17.notify.replace.hook.ModuleMain_YukiHookXposedInit` |
| 日志 TAG / 线程名 | `C17NotifyReplace` |
| CI 产物名 | `C17NotifyReplace-<version>.apk` |

## 上游来源

- [fankes/ColorOSNotifyIcon](https://github.com/fankes/ColorOSNotifyIcon)：原项目，版权归 Fankes Studio 所有
- [Mangi-11/Glyph](https://github.com/Mangi-11/Glyph)：基于原项目，用 YukiHook（libxposed API 102 底座）与 KavaRef 重写 Hook 层、用 Miuix 重写 UI 的分支，本仓库的直接上游

## 技术栈

- Hook 框架：YukiHook 1.5.0（`yukihook-runtime-libxposed`，libxposed API 102 底座）
- 反射：KavaRef 1.1.1
- 模块入口与 `module.prop` / `scope.list` / `java_init.list` 由 KSP 在编译期生成，不再手写 `META-INF/xposed` 资源
- UI：Jetpack Compose + Miuix 0.9.4
- [robojamu/Glyph](https://github.com/robojamu/Glyph)：基于该项目，把状态栏图标改为规则图标、下拉栏使用主题图标，并加入锁屏与锁屏岛图标替换；本仓库以该分支最新 release `3.4.5-cn` 为源码基线。
- [Android Notification Icon Project](https://github.com/BetterAndroid/android-notification-icon-project)：通知图标规则库，规则由该项目维护

## 说明

- 本仓库与上述项目无隶属关系，非官方发布。
- 上游仓库不负责本仓库的改动；相关问题与改动记录留在本仓库。
- 构建使用仓库内附带的公共开发证书（非生产密钥），任何人可用同一证书重新签名，不作官方发布使用。
- 本仓库以 `robojamu/Glyph` 的最新 release `3.4.5-cn` 为源码基线重建（该版本自身基于 `Mangi-11/Glyph`）；改动明细与日期见 git 提交记录。
- 应用 ID / namespace 为 `com.c17.notify.replace`，与原项目 `ColorOSNotifyIcon` 的 `com.fankes.coloros.notify` 完全分离：两者可同时安装、互不覆盖，在 LSPosed 管理器与系统应用列表里也不会再被认成同一个模块。

## 规则来源

沿用 [Android Notification Icon Project (ANIP)](https://github.com/BetterAndroid/android-notification-icon-project) 规则仓库：

- [Android Notification Icon Project](https://github.com/BetterAndroid/android-notification-icon-project)
- [ColorOS 规则清单](https://raw.githubusercontent.com/BetterAndroid/android-notification-icon-project/main/icons/system/coloros/manifest.json)
- [游戏规则清单](https://raw.githubusercontent.com/BetterAndroid/android-notification-icon-project/main/icons/game/manifest.json)
- [APP 规则清单](https://raw.githubusercontent.com/BetterAndroid/android-notification-icon-project/main/icons/app/manifest.json)

同步时按 ColorOS → 游戏 → APP 合并。同一包名出现多次的，以后者为准。

## 更新

App 内「关于 → 检查更新」读取本仓库的 Releases。

规则图标单独同步：首页「更新规则」按钮拉取ANIP清单，无后台自动同步；补充规则应向 ANIP 提交。

## 发版

推一个版本号 tag（形如 `re.5`）即触发 `.github/workflows/self_release.yml`：Actions 用仓库内
`.secret/universal.p12` 签名编译，产出单个 `C17NotifyReplace.apk` 与同名 `.sha256` 发布到 Releases。
该证书与设备上已装的包一致，`adb install -r` 可直接覆盖升级，规则包与配置都不会丢。

日常提交走 `.github/workflows/commit_ci.yml`，只产出一个 release APK（另附 ProGuard mapping），
签名与正式包相同但版本不带 tag，用于装到手机上看效果。

改动的是 hook 代码时，装完需要重启一次 SystemUI 才会生效。

## 注意事项

1. 本软件免费、兴趣驱动，仅供学习交流；付费获取的版本均非官方渠道。
2. 本软件采用 **AGPL 3.0** 许可证。分发或修改时必须遵守条款并提供源代码。
3. 保留原始版权声明与许可证信息，不得冒用原作者名义。
4. 本仓库为自用改动线，与上游作者无隶属关系；相关问题与改动记录留在本仓库，无需提交到上游仓库。

## 隐私政策

- [PRIVACY](PRIVACY.md)

## 许可证

- [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html)

代码可自由查看、修改、分发，条件是保持同样的自由：分发修改版时保留版权声明、标明改动并提供源码。

原始项目版权归 Fankes Studio(qzmmcn@163.com) 所有；上游 Glyph 分支由 [Mangi-11](https://github.com/Mangi-11/Glyph) 维护；
本仓库的改动同样按 AGPL-3.0 发布。

## 致谢

感谢原作者 [fankes](https://github.com/fankes) 的开源基础，以及各位图标规则维护者的持续付出。

感谢 [Mangi-11](https://github.com/Mangi-11/Glyph) 用 libxposed API 102 重写 Hook 层、用 Miuix 重写 UI，Glyph 分支的形态来自这里。

特别感谢 [robojamu](https://github.com/robojamu/Glyph)：本仓库直接以该分支的最新 release `3.4.5-cn` 为源码基线，锁屏与锁屏岛的图标替换、个人头像角标、以及「状态栏规则图标 / 下拉栏主题图标」这三项行为的原始实现与调优都来自该分支，本仓库在此之上继续修正时序与配色问题。

通用占位符采用 Google Material Icons 的 [`circle_notifications`](https://github.com/google/material-design-icons)，按 [Apache-2.0](third_party/material-design-icons/LICENSE) 许可证使用。

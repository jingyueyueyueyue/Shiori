# Shiori · 栞

**Shiori · 栞** 是一款面向 **Android 手机和平板** 的视觉小说统一启动器，同时内置 **Majiro** 与 **CatSystem2** 两套开源引擎。

你只需要安装这一个应用，无需额外下载插件或分别安装多个模拟器，就能用一个统一、美观的书架界面管理并直接游玩两类引擎的视觉小说。**本应用不内置任何游戏，游戏本体需自行准备。**

[下载测试安装包（Releases）](https://github.com/jingyueyueyueyue/Shiori/releases)

> 当前发布的 `v0.1.3` 为 Debug 签名的 `arm64-v8a` 测试版本，仅供体验与测试。

## 为什么选择 Shiori？

- **内置双引擎，互不干扰**：直接打包了完整的 [Majiro](https://github.com/jingyueyueyueyue/majiro-emulator) 与 [CatSystem2](https://github.com/jingyueyueyueyue/CatSystem2-emulator) 运行环境，分别运行在独立进程（`:game` 与 `:cs2`）中。游戏存档目录各自独立保存，老玩家原有的存档不会丢失或冲突。
- **自动识别与双模式扫描**：
  - 支持「扫描总目录（含子文件夹）」批量导入，也支持「单独添加游戏文件夹」。
  - 自动识别游戏属于 Majiro 还是 CatSystem2，支持按引擎筛选与按游戏名/目录搜索；识别有偏差时也可长按手动切换引擎。
- **精美海报书架与联网封面**：
  - 采用 Jetpack Compose（Material 3）界面，自动适配手机竖屏/横屏与平板大屏布局，顶部可展示「最近游玩」续玩卡片。
  - 自动读取游戏目录中的 `.exe` 图标、`.ico` 图标与 `startup.xml` 标题，并支持通过 **Bangumi、VNDB、DLsite** 自动或手动匹配高清竖版封面（默认开启敏感封面模糊保护）。
- **细致的单游戏管理与安全移除**：
  - 长按任意游戏卡片即可单独设置显示名称、更换封面、切换文本/语种模式、开启触屏快捷键或性能监控浮窗。
  - 支持「移出游戏库（不删除游戏文件）」，只会清理启动器列表和封面缓存，**绝不会删除设备上的游戏文件夹和存档**。
- **丰富外观与实用工具**：
  - 支持浅色 / 深色 / 跟随系统模式，内置 **12 套主题配色**（经典蓝、靛青、紫罗兰、樱花粉、森林绿等），可切换 3:4 竖版海报或紧凑图标卡片。
  - 双引擎共用**性能监控浮窗**开关（FPS / GPU / CPU / RAM / PWR），支持 Majiro 在线翻译接口加密配置与私有字体导入，并可一键导出双引擎诊断日志 ZIP。

## 使用方法

1. 安装并打开 Shiori，点击右上角 **`+`** 选择：
   - **扫描游戏目录（含子文件夹）**：适合把多个游戏放在同一个总目录下统一导入；
   - **单独添加游戏文件夹**：适合快速导入单个游戏。
2. 如游戏库中包含 CatSystem2 游戏，请按页面提示授予「所有文件访问」权限（用于以本地路径直接读取游戏资源）。
3. 点击封面即可开始游玩；长按游戏卡片可修改封面、标题或单独覆盖设置。
4. 游玩 CatSystem2 游戏时，按 Android 返回键打开「游戏控制」。选择「沉浸全屏」可隐藏状态栏和导航栏，也可在窗口模式下单独切换「隐藏状态栏」。

> 说明：在线翻译功能目前仅在 **Majiro** 引擎游戏中生效；CatSystem2 游戏选择在线翻译时会自动回退为日语原文。

## 已测试游戏汇总（共 37 款）

以下汇总了底层两个模拟器目前已经验证测试过的游戏，供参考兼容情况（不同版本、汉化补丁与设备表现可能存在差异）：

### Majiro 引擎已测试游戏（17 款 · Android 实机测试）

1. Amber Quartz
2. 僕がサダメ 君には翼を
3. Happy Princess
4. Idol ☆ Revolution
5. 痴漢貴族
6. まじかるカナン２
7. おーぱんつ
8. Reconquista
9. ルリのかさね ～いもうと物語り～
10. Switch!! ～ボクがナツに想うこと～
11. White ～blanche comme la lune～
12. Narcissu 姫子篇
13. Narcissu アイリス
14. Narcissu 1st & 2nd
15. Narcissu 菫
16. Narcissu Zero
17. そして煌めく乙女と秘密^5

### CatSystem2 引擎已测试游戏（20 款 · PC 端主测，Android 持续适配中）

> 其中前 19 款已在 PC 端跑通至少一条结局并完成存档冷读验证，《祝福のカンパネラ》正在推进后续剧情验证；`ISLAND` 等作品已开展 Android 真机启动与剧情推进测试。

1. ネコぱら vol.3（NEKOPARA Vol.3 Steam 版）
2. Happiness！2 樱花盛典
3. ISLAND
4. Yukikoi Melt
5. うたてめぐり
6. ここから夏のイノセンス！
7. カミカゼ☆エクスプローラー!
8. ツナバン らぶみくす
9. 尚有佳蜜伴恋心（恋ニ、甘味ヲソエテ）
10. 戦国天使ジブリール
11. 未来ラジオと人工鳩
12. 果つることなき未来より
13. 灰色的乐园 v1（グリザイアの楽園）
14. 灰色的果实（グリザイアの果実）
15. 灰色的迷宫（グリザイアの迷宮）
16. 牛顿与苹果树
17. 白昼夢の青写真
18. 祝福のカンパネラ（推进测试中）
19. 突然之间发现我已恋上你
20. 秘密游戏：反叛（汉化版 A 线）

> 另包含 CatSystem2 官方引擎示例 `cs2_full_v401`（用于基础功能与存读档测试）。

## 相关项目

- Majiro 引擎：[jingyueyueyueyue/majiro-emulator](https://github.com/jingyueyueyueyue/majiro-emulator)
- CatSystem2 引擎：[jingyueyueyueyue/CatSystem2-emulator](https://github.com/jingyueyueyueyue/CatSystem2-emulator)

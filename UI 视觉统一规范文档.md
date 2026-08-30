# MyMovieStore App UI 视觉统一规范文档

## 一、设计原则

- **一致性**：所有页面、弹框、控件采用统一的颜色、字体、间距、圆角。
- **可读性**：确保文字与背景对比度符合无障碍标准（WCAG 2.1 AA）。
- **沉浸感**：深色背景为主，辅以高亮色突出可操作元素，适合视频播放环境。
- **灵活性**：支持未来扩展主题（如浅色模式），所有设计值以变量形式定义。

---

## 二、颜色系统（Design Tokens）

### 2.1 主色与背景

| 用途             | 色值（Hex / ARGB） | 说明           |
| -------------- | -------------- | ------------ |
| 主背景（全局）        | `#0D0D0D`      | 最底层背景        |
| 二级背景（卡片/面板）    | `#1A1A1A`      | 用于列表项、弹框、输入框 |
| 三级背景（hover/选中） | `#2A2A2A`      | 高亮或选中状态      |
| 分割线            | `#333333`      | 分割线、边框       |

### 2.2 文字颜色

| 用途       | 色值                    | 说明        |
| -------- | --------------------- | --------- |
| 主要文字     | `#FFFFFF` / `#F5F5F5` | 标题、正文     |
| 次要文字     | `#B3B3B3`             | 描述、辅助信息   |
| 占位符/禁用文字 | `#666666`             | 输入框占位、禁用项 |
| 链接/高亮文字  | `#FFB340`（或主色调）       | 可点击文字、强调  |

### 2.3 交互与状态

| 用途      | 色值                             | 说明        |
| ------- | ------------------------------ | --------- |
| 主按钮/强调色 | `#FF6B35`（橙色系）或 `#E50914`（红色系） | 决定一个统一的主色 |
| 主按钮按下态  | 主色 + 20% 暗度                    | 点击反馈      |
| 次要按钮/边框 | `#444444`                      | 次要操作      |
| 成功（已完成） | `#4CAF50`                      | 下载完成、历史标记 |
| 警告/进行中  | `#FFA726`                      | 下载中、缓冲    |
| 错误/失败   | `#EF5350`                      | 错误信息、失败状态 |

> **建议主色**：选用 `#FF6B35`（温暖橙色）作为主色调，与视频娱乐氛围契合，同时与深色背景形成良好对比。也可沿用现有主题色。

### 2.4 遮罩与阴影

| 用途     | 色值          | 说明        |
| ------ | ----------- | --------- |
| 弹框遮罩   | `#80000000` | 半透明黑      |
| 阴影（卡片） | `#40000000` | 轻微阴影，提升层次 |
| 投影（按钮） | `#60000000` | 浮起效果      |

---

## 三、字体与排版

### 3.1 字体家族

- 中文字体：`PingFang SC`, `Noto Sans SC`, `系统默认`
- 英文字体：`Roboto`, `系统默认`

### 3.2 字号与行高（单位：sp）

| 层级      | 字号  | 行高  | 使用场景       |
| ------- | --- | --- | ---------- |
| H1（大标题） | 22  | 30  | 详情页标题      |
| H2（中标题） | 18  | 26  | 列表项标题、弹框标题 |
| H3（小标题） | 16  | 22  | 分类标题、卡片标题  |
| 正文      | 14  | 20  | 描述、演员信息    |
| 辅助文字    | 12  | 18  | 时间、标签、提示   |
| 极小文字    | 10  | 14  | 角标、单位      |

### 3.3 字重

- 标题：`Bold` / `SemiBold`
- 正文：`Normal`
- 辅助：`Normal` 或 `Light`

---

## 四、间距与尺寸

### 4.1 基础间距单位（dp）

采用 **8dp 网格** 体系，所有间距为 8 的倍数。

| 名称    | 值   | 使用场景        |
| ----- | --- | ----------- |
| `xs`  | 4   | 图标与文字间距、小分隔 |
| `sm`  | 8   | 控件内边距、列表项间距 |
| `md`  | 12  | 组件间距、卡片内边距  |
| `lg`  | 16  | 页面边距、弹框内边距  |
| `xl`  | 24  | 区块间距、大分隔    |
| `xxl` | 32  | 主标题与内容间距    |

### 4.2 圆角

- 小圆角（按钮、输入框、标签）：`4dp`（标签可用 `12dp` 全圆角）
- 中圆角（卡片、列表项）：`8dp`
- 大圆角（卡片容器、内容区）：`12dp`
- 浮层圆角（居中卡片弹窗、Dialog）：`24dp`
- BottomSheet 顶部圆角：`12dp`
- 圆形（头像、图标）：`50%`

> 说明：居中卡片弹窗统一使用 `24dp` 大圆角，与页面卡片（8dp/12dp）形成层次区分。

### 4.3 边框宽度

- 默认边框：`1dp`
- 强调边框：`2dp`

---

## 五、通用组件规范

### 5.1 按钮

| 类型              | 背景色          | 文字色       | 圆角  | 高度      |
| --------------- | ------------ | --------- | --- | ------- |
| 主按钮（Primary）    | 主色           | `#FFFFFF` | 4dp | 44dp    |
| 主按钮禁用           | 主色 * 0.5     | `#B3B3B3` | 4dp | 44dp    |
| 次要按钮（Secondary） | 透明，边框 `#666` | `#FFFFFF` | 4dp | 44dp    |
| 文本按钮（Text）      | 透明           | 主色        | -   | -       |
| 图标按钮            | 透明           | 次要文字      | 圆形  | 44x44dp |

**交互反馈**：按下时透明度变化（0.7）或阴影加深。

### 5.2 输入框（Search/Spinner等）

| 属性    | 值                   |
| ----- | ------------------- |
| 背景    | `#1A1A1A`           |
| 文字颜色  | `#FFFFFF`           |
| 占位符颜色 | `#666666`           |
| 边框    | `#333333`，聚焦时变为主色   |
| 圆角    | `4dp`               |
| 内边距   | 水平 `12dp`，垂直 `10dp` |

### 5.3 弹框（Dialog / BottomSheet）

#### 5.3.1 居中卡片弹窗（推荐，全项目统一采用）

用于所有信息展示与操作确认类弹窗，基于 `MaterialCardView` 实现：

| 元素     | 规范                                          |
| ------ | ------------------------------------------- |
| 根布局    | `MaterialCardView`，圆角 `24dp`，背景 `#1A1A1A`    |
| 宽度     | 屏宽 `85%`（`window.setLayout` 设置）             |
| 窗口背景   | 透明（仅卡片显示）                                   |
| 内边距    | 左右 `22dp`，上 `22dp`，下 `16~18dp`              |
| 标题     | `19sp` Bold 居中，`#FFFFFF`                    |
| 副标题    | `13sp` 居中，`#B3B3B3`                         |
| 内容容器   | 圆角 `14dp`，背景 `#2A2A2A`                      |
| 正文     | `14sp`，`#F5F5F5`（大段文字避免纯白刺眼）                |
| 辅助说明   | `12sp`，`#B3B3B3`                            |
| 底部提示   | `11~12sp`，`#B3B3B3` 或 `#666666`（弱化）          |
| 分隔线    | `#333333`，`0.5~1dp`                         |
| 强调/点缀  | 主色 `#FF6B35`（徽章文字、编号圆点、主按钮）                  |
| 按钮     | 主操作 主色实心；次操作 `#2A2A2A` 灰底 + `#FFFFFF` 白字；均 `44dp` 高、圆角 `12dp`、等分并排 |
| 长内容    | 内容区包 `ScrollView`，最大高度屏高 `60%~65%`，超出可滚动     |

**已应用**：`dialog_update_tip`（更新提示）、`dialog_about`（关于）、`dialog_help`（帮助）、`dialog_video_source`（视频源管理）、`dialog_clear_cache`（清理缓存）。

#### 5.3.2 通用要求

| 元素     | 规范                |
| ------ | ----------------- |
| 背景     | `#1A1A1A`         |
| 标题     | 居中对齐，`19sp` Bold  |
| 正文     | `14sp`，颜色 `#F5F5F5` |
| 按钮     | 按 5.1 按钮规范，"确定/取消"并排 |
| 遮罩     | `#80000000`       |

> 不再使用系统默认 `AlertDialog` 样式（含 `setMultiChoiceItems` 等原生列表），一律改用自定义卡片布局，保证跨页面视觉一致。

### 5.4 Toast / Snackbar

- 背景：`#333333` 或 `#2A2A2A`
- 文字：`#FFFFFF`
- 圆角：`8dp`
- 显示时长：短（2s）或长（3.5s）

### 5.5 进度条（下载/缓冲）

- 背景：`#333333`
- 进度色：主色
- 高度：`3dp` 或 `4dp`
- 圆角：`2dp`

### 5.6 标签（Tag / Chip）

- 背景：`#2A2A2A`
- 文字：`#B3B3B3`
- 圆角：`12dp`
- 内边距：水平 `8dp`，垂直 `4dp`

### 5.7 分隔线

- 颜色：`#333333`
- 高度：`1dp`
- 左右边距：根据布局，通常 `16dp`

---

## 六、页面通用布局规范

### 6.1 状态栏与导航栏

- 状态栏：透明（沉浸式）或半透明黑
- 顶部导航栏（Toolbar）：背景 `#1A1A1A`，标题白色，返回/菜单图标白色
- 底部导航栏（Bottom Navigation）：背景 `#1A1A1A`，选中图标主色，未选中灰色

### 6.2 列表项（RecyclerView Item）

- 背景：`#1A1A1A`（或 `#0D0D0D` 交替，建议统一）
- 内边距：`12dp` 左右，`8dp` 上下
- 分割线：`1dp` 灰色
- 选中/点击态：背景变 `#2A2A2A`
- 图片：圆角 `8dp`

### 6.3 卡片（Card）

- 背景：`#1A1A1A`
- 圆角：`8dp`
- 阴影：微投影（或使用 elevation）
- 内边距：`12dp`

### 6.4 空状态与加载中

- 空状态：居中图标 + 提示文字（辅助文字大小）
- 加载中：圆形进度条（主色），居中显示

---

## 七、深色模式与主题切换

- 当前App默认为深色主题，无需额外切换。
- 如需支持浅色模式，可定义两套颜色变量，通过 `Theme.AppCompat.DayNight` 或自定义 `Theme` 实现。建议暂不实现，待后续需求。

---

## 八、实施方式与迁移步骤

### 8.1 技术实现建议

1. **定义颜色资源**：在 `res/values/colors.xml` 中统一声明所有颜色值，引用到布局和代码。
   
   ```xml
   <color name="colorPrimary">#FF6B35</color>
   <color name="colorPrimaryDark">#1A1A1A</color>
   <color name="colorBackground">#0D0D0D</color>
   <color name="colorSurface">#1A1A1A</color>
   <color name="colorOnSurface">#FFFFFF</color>
   <color name="colorOnSurfaceSecondary">#B3B3B3</color>
   <!-- 更多... -->
   ```

2. **定义文本样式**：在 `styles.xml` 中定义 `TextAppearance` 系列。
   
   ```xml
   <style name="TextAppearance.MyMovieStore.H1" parent="TextAppearance.MaterialComponents.Headline1">
       <item name="android:textSize">22sp</item>
       <item name="android:textColor">@color/colorOnSurface</item>
       <item name="android:fontFamily">@font/... (可选)</item>
   </style>
   ```

3. **定义主题**：应用全局主题继承自 `Theme.MaterialComponents.DayNight.NoActionBar`，并覆盖颜色属性。
   
   ```xml
   <style name="Theme.MyMovieStore" parent="Theme.MaterialComponents.DayNight.NoActionBar">
       <item name="colorPrimary">@color/colorPrimary</item>
       <item name="colorPrimaryVariant">@color/colorPrimaryDark</item>
       <item name="colorOnPrimary">@android:color/white</item>
       <item name="colorSurface">@color/colorSurface</item>
       <item name="colorOnSurface">@color/colorOnSurface</item>
       <!-- 更多属性 -->
   </style>
   ```

4. **按钮样式**：使用 `MaterialButton` 并设置 `app:backgroundTint` 等属性，或定义自定义样式。

5. **弹框样式**：使用 `AlertDialog.Builder` 时可设置自定义主题，或使用 `MaterialAlertDialogBuilder`。

### 8.2 迁移检查清单（按页面/组件）

- [x] 主界面（MainActivity / 底部导航）
- [x] 首页（HomeFragment）
- [x] 搜索（SearchFragment）
- [x] 历史（HistoryFragment）
- [x] 详情（DetailActivity）
- [x] 播放器（PlayerActivity）
- [x] 下载管理（DownloadManagementFragment）
- [x] 我的（ProfileFragment）
- [x] 所有弹框（Dialog、BottomSheet）—— 已统一为居中卡片弹窗（见 5.3.1）
- [x] 菜单与下拉选择（Spinner、PopupMenu）
- [x] Toast / Snackbar
- [x] 进度条、加载动画
- [x] 空状态、错误状态视图

检查项：背景、文字、按钮、边框、分割线、圆角、间距是否与规范一致。

**已完成（2026-08-30）**：全部弹窗统一为居中卡片风格，硬编码灰阶（`#AAAAAA`/`#999999`/`#888888`/`#CCCCCC`/`#2F2F2F`）全部替换为规范变量（`colorOnSurfaceSecondary`/`colorOnSurfaceSoft`/`colorDivider`）；剩余硬编码仅播放器半透明专用色（`#CCFFFFFF`、加载遮罩 `#CC0D0D0D`），属规范允许范围。

### 8.3 注意事项

- 不要修改现有布局结构，只替换颜色、尺寸、字体引用。
- 对于硬编码颜色（如 `#FFFFFF`），替换为资源引用。
- 测试所有页面在不同屏幕尺寸下的显示效果。
- 确保无障碍对比度（可用工具检查）。

---

## 九、附录：示例颜色变量表

| 变量名                       | 用途      | 值           |
| ------------------------- | ------- | ----------- |
| `colorPrimary`            | 主色调     | `#FF6B35`   |
| `colorPrimaryDark`        | 主色调暗色   | `#E55A20`   |
| `colorAccent`             | 强调色     | `#FF6B35`   |
| `colorBackground`         | 页面背景    | `#0D0D0D`   |
| `colorSurface`            | 卡片/弹框背景 | `#1A1A1A`   |
| `colorSurfaceHighlight`   | 选中/悬浮背景 | `#2A2A2A`   |
| `colorOnSurface`          | 主要文字    | `#FFFFFF`   |
| `colorOnSurfaceSoft`      | 主要文字（柔和） | `#F5F5F5`   |
| `colorOnSurfaceSecondary` | 次要文字    | `#B3B3B3`   |
| `colorOnSurfaceDisabled`  | 禁用文字/弱化提示 | `#666666`   |
| `colorDivider`            | 分割线     | `#333333`   |
| `colorSuccess`            | 成功      | `#4CAF50`   |
| `colorWarning`            | 警告      | `#FFA726`   |
| `colorError`              | 错误      | `#EF5350`   |
| `colorOverlay`            | 遮罩      | `#80000000` |

---

## 十、后续扩展

- 如需加入浅色模式，可定义另一套颜色资源（如 `colors_light.xml`），并通过主题切换。
- 可进一步定义 `Button`、`EditText` 等组件的自定义样式，减少重复代码。
- 建议将所有 `dp` 尺寸也抽取为 `dimens.xml`，方便统一调整。

---

**文档版本**：1.0  
**适用项目**：MyMovieStore Android App  
**更新日期**：2026-06-19

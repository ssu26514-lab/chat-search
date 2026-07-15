# CardTools Windows

Windows 原生角色卡与 SillyTavern 文件整理工具。它直接扫描电脑硬盘文件夹，不需要安卓模拟器，也不需要把文件复制进虚拟手机。

## 第一版功能

### 图形界面 `CardTools.exe`

- 严格查重：文件大小分组后计算完整 SHA-256。
- 有效内容查重：PNG 与 JSON 角色卡可以跨格式比较。
- 酒馆文件分类：角色卡、预设、美化、世界书、正则、插件、素材、混合包、损坏文件。
- 角色卡改名预览：读取内部角色名，重名自动追加 `(1)`、`(2)`。
- 角色卡浏览：查看 PNG/JSON 人设、全部开场白和内容指纹。
- 安全移动：复制、大小校验、SHA-256 校验成功后才删除源文件。
- 安全删除：每次删除重复副本前重新计算哈希或重新解析有效内容。

### 命令行 `CardTools.Cli.exe`

```bat
CardTools.Cli.exe duplicates "D:\角色卡"
CardTools.Cli.exe semantic "D:\角色卡"
CardTools.Cli.exe classify "D:\酒馆资源"
CardTools.Cli.exe rename-preview "D:\角色卡"
CardTools.Cli.exe rename-apply "D:\角色卡" --yes
```

命令行默认只输出报告。只有 `rename-apply` 会修改文件，并且必须显式附加 `--yes`。

## 工程结构

- `src/CardTools.Core`：解析、哈希、查重、分类、改名和安全文件操作。
- `src/CardTools.Win`：Windows Forms 图形界面。
- `src/CardTools.Cli`：CMD 命令行入口。
- `tests/CardTools.Tests`：自动测试。

## 安全原则

- 不根据文件名判断内容相同。
- 严格重复必须完整 SHA-256 相同。
- 有效内容重复必须重新解析后仍一致。
- 分类功能只用于查看和移动，不直接删除。
- 移动失败或校验失败时保留源文件。
- 同名但内容变化的角色卡不提供一键删除。

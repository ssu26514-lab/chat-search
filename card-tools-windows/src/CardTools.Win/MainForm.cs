using CardTools.Core;

namespace CardTools.Win;

public sealed class MainForm : Form
{
    private enum OperationMode
    {
        None,
        StrictDuplicates,
        SemanticDuplicates,
        Classification,
        RenamePreview,
        Browser
    }

    private const int SelectColumn = 0;
    private const int KeepColumn = 1;
    private const int GroupColumn = 2;
    private const int TypeColumn = 3;
    private const int NameColumn = 4;
    private const int DetailsColumn = 5;
    private const int PathColumn = 6;

    private readonly TextBox _folderText = new();
    private readonly DataGridView _grid = new();
    private readonly ProgressBar _progress = new();
    private readonly Label _status = new();
    private readonly Button _strictButton = new();
    private readonly Button _semanticButton = new();
    private readonly Button _classifyButton = new();
    private readonly Button _renameButton = new();
    private readonly Button _browseButton = new();
    private readonly Button _moveButton = new();
    private readonly Button _deleteButton = new();
    private readonly Button _applyRenameButton = new();
    private readonly Button _viewButton = new();
    private readonly Button _cancelButton = new();

    private readonly Dictionary<string, DuplicateGroup> _strictGroups =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, SemanticDuplicateGroup> _semanticGroups =
        new(StringComparer.OrdinalIgnoreCase);

    private CancellationTokenSource? _cancellation;
    private OperationMode _mode;
    private bool _busy;

    public MainForm()
    {
        Text = "CardTools Windows｜角色卡文件工具";
        Width = 1380;
        Height = 860;
        MinimumSize = new Size(1024, 680);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Microsoft YaHei UI", 9F);
        BuildInterface();
        UpdateActions();
    }

    private void BuildInterface()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 6,
            Padding = new Padding(12)
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(root);

        var title = new Label
        {
            AutoSize = true,
            Text = "CardTools Windows",
            Font = new Font(Font.FontFamily, 21F, FontStyle.Bold),
            Margin = new Padding(0, 0, 0, 5)
        };
        root.Controls.Add(title, 0, 0);

        var folderPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 3,
            Margin = new Padding(0, 0, 0, 8)
        };
        folderPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        folderPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        folderPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        folderPanel.Controls.Add(new Label
        {
            AutoSize = true,
            Text = "扫描文件夹：",
            Anchor = AnchorStyles.Left,
            Padding = new Padding(0, 7, 5, 0)
        }, 0, 0);
        _folderText.Dock = DockStyle.Fill;
        _folderText.PlaceholderText = "例如 D:\角色卡 或 E:\酒馆资源";
        folderPanel.Controls.Add(_folderText, 1, 0);
        var chooseButton = CreateButton("选择文件夹", 110);
        chooseButton.Click += (_, _) => ChooseFolder();
        folderPanel.Controls.Add(chooseButton, 2, 0);
        root.Controls.Add(folderPanel, 0, 1);

        var operationPanel = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            WrapContents = true,
            Margin = new Padding(0, 0, 0, 7)
        };
        ConfigureButton(_strictButton, "严格查重", 112);
        ConfigureButton(_semanticButton, "有效内容查重", 135);
        ConfigureButton(_classifyButton, "酒馆文件分类", 135);
        ConfigureButton(_renameButton, "角色卡改名预览", 145);
        ConfigureButton(_browseButton, "浏览角色卡", 125);
        ConfigureButton(_cancelButton, "取消当前任务", 125);
        _strictButton.Click += async (_, _) => await ScanStrictAsync();
        _semanticButton.Click += async (_, _) => await ScanSemanticAsync();
        _classifyButton.Click += async (_, _) => await ScanClassificationAsync();
        _renameButton.Click += async (_, _) => await ScanRenameAsync();
        _browseButton.Click += async (_, _) => await ScanBrowserAsync();
        _cancelButton.Click += (_, _) => _cancellation?.Cancel();
        operationPanel.Controls.AddRange(new Control[]
        {
            _strictButton, _semanticButton, _classifyButton,
            _renameButton, _browseButton, _cancelButton
        });
        root.Controls.Add(operationPanel, 0, 2);

        var statusPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            Margin = new Padding(0, 0, 0, 7)
        };
        _progress.Dock = DockStyle.Top;
        _progress.Style = ProgressBarStyle.Continuous;
        _progress.Minimum = 0;
        _progress.Maximum = 100;
        _progress.Height = 8;
        _status.Dock = DockStyle.Top;
        _status.AutoSize = true;
        _status.Text = "请选择文件夹，然后选择一个功能。所有删除和移动都会重新校验。";
        _status.Padding = new Padding(0, 5, 0, 2);
        statusPanel.Controls.Add(_progress, 0, 0);
        statusPanel.Controls.Add(_status, 0, 1);
        root.Controls.Add(statusPanel, 0, 3);

        ConfigureGrid();
        root.Controls.Add(_grid, 0, 4);

        var actionPanel = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            AutoSize = true,
            WrapContents = true,
            Padding = new Padding(0, 7, 0, 0)
        };
        ConfigureButton(_viewButton, "查看选中内容", 130);
        ConfigureButton(_moveButton, "安全移动选中项", 145);
        ConfigureButton(_deleteButton, "重新校验并删除", 145);
        ConfigureButton(_applyRenameButton, "执行选中改名", 135);
        _viewButton.Click += (_, _) => ViewSelected();
        _moveButton.Click += async (_, _) => await MoveSelectedAsync();
        _deleteButton.Click += async (_, _) => await DeleteSelectedAsync();
        _applyRenameButton.Click += async (_, _) => await ApplyRenamesAsync();
        actionPanel.Controls.AddRange(new Control[]
        {
            _viewButton, _moveButton, _deleteButton, _applyRenameButton
        });
        root.Controls.Add(actionPanel, 0, 5);
    }

    private void ConfigureGrid()
    {
        _grid.Dock = DockStyle.Fill;
        _grid.AllowUserToAddRows = false;
        _grid.AllowUserToDeleteRows = false;
        _grid.AllowUserToOrderColumns = true;
        _grid.MultiSelect = false;
        _grid.RowHeadersVisible = false;
        _grid.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
        _grid.AutoSizeRowsMode = DataGridViewAutoSizeRowsMode.AllCells;
        _grid.BackgroundColor = SystemColors.Window;
        _grid.Columns.Add(new DataGridViewCheckBoxColumn
        {
            HeaderText = "选择",
            Width = 55,
            SortMode = DataGridViewColumnSortMode.NotSortable
        });
        _grid.Columns.Add(new DataGridViewCheckBoxColumn
        {
            HeaderText = "保留",
            Width = 55,
            SortMode = DataGridViewColumnSortMode.NotSortable
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "组",
            Width = 75,
            ReadOnly = true
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "类型",
            Width = 145,
            ReadOnly = true
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "名称",
            Width = 210,
            ReadOnly = true
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "说明 / 差异",
            AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill,
            MinimumWidth = 260,
            ReadOnly = true,
            DefaultCellStyle = new DataGridViewCellStyle { WrapMode = DataGridViewTriState.True }
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "完整路径",
            Width = 430,
            ReadOnly = true
        });
        _grid.CurrentCellDirtyStateChanged += (_, _) =>
        {
            if (_grid.IsCurrentCellDirty)
                _grid.CommitEdit(DataGridViewDataErrorContexts.Commit);
        };
        _grid.CellValueChanged += GridCellValueChanged;
        _grid.CellDoubleClick += (_, eventArgs) =>
        {
            if (eventArgs.RowIndex >= 0) ViewRow(_grid.Rows[eventArgs.RowIndex]);
        };
    }

    private void GridCellValueChanged(object? sender, DataGridViewCellEventArgs eventArgs)
    {
        if (eventArgs.RowIndex < 0 || eventArgs.ColumnIndex != KeepColumn)
            return;
        DataGridViewRow changed = _grid.Rows[eventArgs.RowIndex];
        if (changed.Cells[KeepColumn].ReadOnly
            || changed.Cells[KeepColumn].Value is not true)
            return;
        string group = Convert.ToString(changed.Cells[GroupColumn].Value) ?? string.Empty;
        foreach (DataGridViewRow row in _grid.Rows)
        {
            if (row.Index == changed.Index) continue;
            if (string.Equals(Convert.ToString(row.Cells[GroupColumn].Value), group,
                StringComparison.OrdinalIgnoreCase))
                row.Cells[KeepColumn].Value = false;
        }
        changed.Cells[SelectColumn].Value = false;
    }

    private void ChooseFolder()
    {
        using var dialog = new FolderBrowserDialog
        {
            Description = "选择要原地扫描的角色卡或酒馆资源文件夹",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = true
        };
        if (dialog.ShowDialog(this) == DialogResult.OK)
            _folderText.Text = dialog.SelectedPath;
    }

    private string RequireFolder()
    {
        string path = _folderText.Text.Trim();
        if (path.Length == 0 || !Directory.Exists(path))
            throw new DirectoryNotFoundException("请先选择一个存在的文件夹。 ");
        return Path.GetFullPath(path);
    }

    private async Task ScanStrictAsync()
    {
        await RunBusyAsync(async (progress, token) =>
        {
            string folder = RequireFolder();
            IReadOnlyList<DuplicateGroup> groups = await StrictDuplicateScanner.ScanAsync(
                folder, progress, token);
            _mode = OperationMode.StrictDuplicates;
            _strictGroups.Clear();
            _semanticGroups.Clear();
            _grid.Rows.Clear();
            foreach (DuplicateGroup group in groups)
            {
                _strictGroups[group.GroupId] = group;
                foreach (DuplicateFile file in group.Files)
                {
                    AddRow(false,
                        PathEquals(file.Path, group.RecommendedKeeper),
                        group.GroupId,
                        "字节完全相同",
                        Path.GetFileName(file.Path),
                        $"{file.Size:N0} B · SHA-256 {file.Sha256[..12]}…",
                        file.Path,
                        new RowPayload(group.GroupId, file));
                }
            }
            _status.Text = $"严格查重完成：{groups.Count} 组，"
                + $"可清理副本 {groups.Sum(group => group.Files.Count - 1)} 个。"
                + "请确认每组“保留”项，再勾选要删除的副本。";
        });
    }

    private async Task ScanSemanticAsync()
    {
        await RunBusyAsync(async (progress, token) =>
        {
            string folder = RequireFolder();
            SemanticScanResult result = await SemanticDuplicateScanner.ScanAsync(
                folder, progress, token);
            _mode = OperationMode.SemanticDuplicates;
            _strictGroups.Clear();
            _semanticGroups.Clear();
            _grid.Rows.Clear();

            foreach (SemanticDuplicateGroup group in result.ExactContentGroups)
            {
                _semanticGroups[group.GroupId] = group;
                foreach (ParsedCharacterCard card in group.Cards)
                {
                    AddRow(false,
                        PathEquals(card.SourcePath, group.RecommendedKeeper),
                        group.GroupId,
                        "有效内容相同",
                        card.Name,
                        $"{card.Format} · 开场白 {card.Greetings.Count} 个 · "
                            + $"文件 {new FileInfo(card.SourcePath).Length:N0} B",
                        card.SourcePath,
                        new RowPayload(group.GroupId, card));
                }
            }

            int variantIndex = 1;
            foreach (RelatedVariantGroup variant in result.RelatedVariants)
            {
                string groupId = $"V{variantIndex++:0000}";
                foreach (ParsedCharacterCard card in variant.Cards)
                {
                    int row = AddRow(false, false, groupId,
                        "同名但有变化",
                        card.Name,
                        $"禁止一键删除 · {card.Format} · 开场白 {card.Greetings.Count} 个",
                        card.SourcePath,
                        new RowPayload(groupId, card));
                    _grid.Rows[row].Cells[KeepColumn].ReadOnly = true;
                    _grid.Rows[row].Cells[SelectColumn].ReadOnly = true;
                    _grid.Rows[row].DefaultCellStyle.BackColor = Color.MistyRose;
                }
            }
            _status.Text = $"有效内容完全一致 {result.ExactContentGroups.Count} 组；"
                + $"同名但内容变化 {result.RelatedVariants.Count} 组；"
                + $"无法解析 {result.Failures.Count} 个。红色行只查看，不允许删除。";
        });
    }

    private async Task ScanClassificationAsync()
    {
        await RunBusyAsync(async (progress, token) =>
        {
            string folder = RequireFolder();
            IReadOnlyList<ClassifiedFile> files = await TavernFileClassifier.ScanAsync(
                folder, progress, token);
            _mode = OperationMode.Classification;
            _strictGroups.Clear();
            _semanticGroups.Clear();
            _grid.Rows.Clear();
            foreach (ClassifiedFile file in files)
            {
                AddRow(false, false, CategoryText(file.Category),
                    CategoryText(file.Category), file.FileName,
                    $"{file.Subtype} · 可信度 {file.Confidence}%\n为什么：{file.Reason}\n{file.Details}",
                    file.Path,
                    new RowPayload(CategoryText(file.Category), file));
            }
            string summary = string.Join("；", files.GroupBy(file => file.Category)
                .Select(group => $"{CategoryText(group.Key)} {group.Count()}"));
            _status.Text = $"分类完成：{files.Count} 个文件。{summary}。"
                + "分类页只提供安全移动，不直接删除。";
        });
    }

    private async Task ScanRenameAsync()
    {
        await RunBusyAsync((progress, token) =>
        {
            string folder = RequireFolder();
            IReadOnlyList<RenamePlan> plans = RenameService.Preview(folder, token);
            _mode = OperationMode.RenamePreview;
            _strictGroups.Clear();
            _semanticGroups.Clear();
            _grid.Rows.Clear();
            int index = 1;
            foreach (RenamePlan plan in plans)
            {
                AddRow(plan.NeedsRename, false, $"R{index++:0000}",
                    plan.NeedsRename ? "待改名" : "无需改名 / 跳过",
                    Path.GetFileName(plan.SourcePath),
                    $"角色名：{plan.CharacterName}\n新文件名：{Path.GetFileName(plan.TargetPath)}\n{plan.Reason}",
                    plan.SourcePath,
                    new RowPayload(string.Empty, plan));
            }
            _status.Text = $"改名预览完成：需要改名 {plans.Count(plan => plan.NeedsRename)} 个。"
                + "先检查完整对照，再执行选中改名。";
            progress.Report(new ScanProgress(plans.Count, plans.Count, folder,
                "改名预览完成"));
            return Task.CompletedTask;
        });
    }

    private async Task ScanBrowserAsync()
    {
        await RunBusyAsync(async (progress, token) =>
        {
            string folder = RequireFolder();
            var result = await CharacterCardBrowser.ScanAsync(folder, progress, token);
            _mode = OperationMode.Browser;
            _strictGroups.Clear();
            _semanticGroups.Clear();
            _grid.Rows.Clear();
            int index = 1;
            foreach (ParsedCharacterCard card in result.Cards)
            {
                AddRow(false, false, $"C{index++:0000}",
                    $"{card.Format} 角色卡", card.Name,
                    $"开场白 {card.Greetings.Count} 个\n{Preview(card.Persona, 150)}",
                    card.SourcePath,
                    new RowPayload(string.Empty, card));
            }
            _status.Text = $"浏览索引完成：角色卡 {result.Cards.Count} 张；"
                + $"非角色卡或无法解析 {result.Failures.Count} 个。"
                + "双击任意角色卡可查看人设和全部开场白。";
        });
    }

    private async Task MoveSelectedAsync()
    {
        EndGridEdit();
        if (_mode is not OperationMode.Classification and not OperationMode.Browser)
            return;
        DataGridViewRow[] rows = SelectedRows().ToArray();
        if (rows.Length == 0)
        {
            MessageBox.Show(this, "请先勾选要移动的文件。", "没有选择",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        using var dialog = new FolderBrowserDialog
        {
            Description = "选择移动目标文件夹",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = true
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        if (MessageBox.Show(this,
            $"即将安全移动 {rows.Length} 个文件。\n\n"
                + "流程：复制 → 文件大小校验 → SHA-256 校验 → 删除源文件。\n"
                + "校验失败时源文件不会被删除。",
            "确认安全移动", MessageBoxButtons.OKCancel,
            MessageBoxIcon.Warning) != DialogResult.OK) return;

        await RunBusyAsync(async (progress, token) =>
        {
            int completed = 0;
            int success = 0;
            var messages = new List<string>();
            foreach (DataGridViewRow row in rows)
            {
                string source = Convert.ToString(row.Cells[PathColumn].Value) ?? string.Empty;
                SafeMoveResult result = await SafeFileOperations.MoveVerifiedAsync(
                    source, dialog.SelectedPath, token);
                if (result.Success) success++;
                messages.Add($"{(result.Success ? "成功" : "失败")}：{source}\n{result.Message}");
                completed++;
                progress.Report(new ScanProgress(completed, rows.Length, source,
                    "正在安全移动"));
            }
            _status.Text = $"移动完成：成功 {success}，失败 {rows.Length - success}。"
                + "文件状态已变化，请重新扫描。";
            MessageBox.Show(this, string.Join("\n\n", messages.Take(20)),
                "移动结果", MessageBoxButtons.OK,
                success == rows.Length ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
        });
    }

    private async Task DeleteSelectedAsync()
    {
        EndGridEdit();
        if (_mode is not OperationMode.StrictDuplicates
            and not OperationMode.SemanticDuplicates) return;
        DataGridViewRow[] selected = SelectedRows().ToArray();
        if (selected.Length == 0)
        {
            MessageBox.Show(this, "请先勾选要删除的重复副本。", "没有选择",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        string[] groups = selected.Select(row =>
            Convert.ToString(row.Cells[GroupColumn].Value) ?? string.Empty)
            .Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        foreach (string group in groups)
        {
            DataGridViewRow[] keepers = RowsInGroup(group)
                .Where(row => row.Cells[KeepColumn].Value is true).ToArray();
            if (keepers.Length != 1)
            {
                MessageBox.Show(this, $"组 {group} 必须明确勾选且只能勾选一个“保留”文件。",
                    "保留项不明确", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
        }

        if (MessageBox.Show(this,
            $"你选择了 {selected.Length} 个文件准备永久删除。\n\n"
                + "删除前会重新计算 SHA-256 或重新解析角色卡有效内容。"
                + "只要与保留文件不再一致，就会安全跳过。",
            "第一次确认", MessageBoxButtons.OKCancel,
            MessageBoxIcon.Warning) != DialogResult.OK) return;
        if (MessageBox.Show(this, "删除不可撤销。确认继续吗？",
            "最后确认", MessageBoxButtons.YesNo,
            MessageBoxIcon.Stop) != DialogResult.Yes) return;

        await RunBusyAsync(async (progress, token) =>
        {
            int deleted = 0;
            int processedGroups = 0;
            var logs = new List<string>();
            foreach (string groupId in groups)
            {
                DataGridViewRow keeperRow = RowsInGroup(groupId)
                    .Single(row => row.Cells[KeepColumn].Value is true);
                string keeper = Convert.ToString(keeperRow.Cells[PathColumn].Value)
                    ?? string.Empty;
                string[] selectedPaths = selected
                    .Where(row => string.Equals(
                        Convert.ToString(row.Cells[GroupColumn].Value), groupId,
                        StringComparison.OrdinalIgnoreCase))
                    .Select(row => Convert.ToString(row.Cells[PathColumn].Value)
                        ?? string.Empty)
                    .ToArray();

                IReadOnlyList<DeleteResult> results;
                if (_mode == OperationMode.StrictDuplicates)
                {
                    results = await SafeFileOperations.DeleteExactDuplicatesAsync(
                        _strictGroups[groupId], keeper, selectedPaths, token);
                }
                else
                {
                    results = await SafeFileOperations.DeleteSemanticDuplicatesAsync(
                        _semanticGroups[groupId], keeper, selectedPaths, token);
                }
                deleted += results.Count(result => result.Success);
                logs.AddRange(results.Select(result =>
                    $"{(result.Success ? "已删除" : "已跳过")}：{result.Path}\n{result.Message}"));
                processedGroups++;
                progress.Report(new ScanProgress(processedGroups, groups.Length,
                    keeper, "正在重新验证并删除"));
            }
            _status.Text = $"删除流程完成：成功删除 {deleted} 个。"
                + "文件状态已变化，请重新扫描。";
            MessageBox.Show(this, string.Join("\n\n", logs.Take(30)),
                "删除结果", MessageBoxButtons.OK,
                deleted > 0 ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
        });
    }

    private async Task ApplyRenamesAsync()
    {
        EndGridEdit();
        if (_mode != OperationMode.RenamePreview) return;
        RenamePlan[] plans = SelectedRows()
            .Select(row => (row.Tag as RowPayload)?.Item)
            .OfType<RenamePlan>()
            .Where(plan => plan.NeedsRename)
            .ToArray();
        if (plans.Length == 0)
        {
            MessageBox.Show(this, "没有选择需要改名的文件。", "没有选择",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (MessageBox.Show(this,
            $"确认按预览结果原地修改 {plans.Length} 个文件名吗？\n"
                + "角色卡内部内容不会改变；重名会自动追加编号。",
            "确认批量改名", MessageBoxButtons.OKCancel,
            MessageBoxIcon.Warning) != DialogResult.OK) return;

        await RunBusyAsync((progress, token) =>
        {
            token.ThrowIfCancellationRequested();
            IReadOnlyList<RenamePlan> results = RenameService.Apply(plans);
            int success = results.Count(result => result.Reason == "改名完成");
            progress.Report(new ScanProgress(results.Count, results.Count,
                RequireFolder(), "批量改名完成"));
            _status.Text = $"改名完成：成功 {success}，其他 {results.Count - success}。"
                + "请重新扫描查看最新文件名。";
            MessageBox.Show(this,
                string.Join("\n", results.Select(result =>
                    $"{result.Reason}：{Path.GetFileName(result.SourcePath)} → "
                        + $"{Path.GetFileName(result.TargetPath)}").Take(40)),
                "改名结果", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return Task.CompletedTask;
        });
    }

    private void ViewSelected()
    {
        EndGridEdit();
        DataGridViewRow? row = SelectedRows().FirstOrDefault()
            ?? (_grid.CurrentRow?.Index >= 0 ? _grid.CurrentRow : null);
        if (row is null)
        {
            MessageBox.Show(this, "请先选择一行。", "没有选择",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        ViewRow(row);
    }

    private void ViewRow(DataGridViewRow row)
    {
        object? item = (row.Tag as RowPayload)?.Item;
        if (item is ParsedCharacterCard card)
        {
            using var detail = new CardDetailForm(card);
            detail.ShowDialog(this);
            return;
        }
        if (item is ClassifiedFile classified)
        {
            MessageBox.Show(this,
                $"分类：{CategoryText(classified.Category)}\n"
                    + $"子类型：{classified.Subtype}\n可信度：{classified.Confidence}%\n\n"
                    + $"为什么：{classified.Reason}\n\n详情：{classified.Details}\n\n"
                    + $"路径：{classified.Path}",
                classified.FileName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (item is RenamePlan plan)
        {
            MessageBox.Show(this,
                $"原文件：{plan.SourcePath}\n\n新文件：{plan.TargetPath}\n\n"
                    + $"角色名：{plan.CharacterName}\n说明：{plan.Reason}",
                "改名前后对照", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (item is DuplicateFile duplicate)
        {
            MessageBox.Show(this,
                $"文件：{duplicate.Path}\n大小：{duplicate.Size:N0} B\n"
                    + $"SHA-256：{duplicate.Sha256}",
                "严格重复文件", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    private async Task RunBusyAsync(Func<IProgress<ScanProgress>,
        CancellationToken, Task> action)
    {
        if (_busy) return;
        _busy = true;
        _cancellation = new CancellationTokenSource();
        UpdateActions();
        _progress.Value = 0;
        var progress = new Progress<ScanProgress>(value =>
        {
            int percent = value.Total <= 0 ? 0
                : Math.Clamp((int)Math.Round(value.Completed * 100d / value.Total), 0, 100);
            _progress.Value = percent;
            _status.Text = $"{value.Stage}：{value.Completed}/{value.Total}\n{value.CurrentPath}";
        });
        try
        {
            await action(progress, _cancellation.Token);
            _progress.Value = 100;
        }
        catch (OperationCanceledException)
        {
            _status.Text = "任务已取消。扫描结果已清空，请重新运行。";
            _grid.Rows.Clear();
            _mode = OperationMode.None;
        }
        catch (Exception exception)
        {
            _status.Text = $"执行失败：{exception.Message}";
            MessageBox.Show(this, exception.ToString(), "执行失败",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            _cancellation.Dispose();
            _cancellation = null;
            _busy = false;
            UpdateActions();
        }
    }

    private int AddRow(bool selected, bool keep, string group, string type,
        string name, string details, string path, RowPayload payload)
    {
        int index = _grid.Rows.Add(selected, keep, group, type, name, details, path);
        _grid.Rows[index].Tag = payload;
        return index;
    }

    private IEnumerable<DataGridViewRow> SelectedRows()
    {
        foreach (DataGridViewRow row in _grid.Rows)
        {
            if (!row.Cells[SelectColumn].ReadOnly
                && row.Cells[SelectColumn].Value is true)
                yield return row;
        }
    }

    private IEnumerable<DataGridViewRow> RowsInGroup(string group)
    {
        foreach (DataGridViewRow row in _grid.Rows)
        {
            if (string.Equals(Convert.ToString(row.Cells[GroupColumn].Value), group,
                StringComparison.OrdinalIgnoreCase))
                yield return row;
        }
    }

    private void EndGridEdit()
    {
        _grid.EndEdit();
        Validate();
    }

    private void UpdateActions()
    {
        bool idle = !_busy;
        _folderText.Enabled = idle;
        _strictButton.Enabled = idle;
        _semanticButton.Enabled = idle;
        _classifyButton.Enabled = idle;
        _renameButton.Enabled = idle;
        _browseButton.Enabled = idle;
        _cancelButton.Enabled = _busy;
        _grid.Enabled = idle;
        _deleteButton.Enabled = idle && _mode is OperationMode.StrictDuplicates
            or OperationMode.SemanticDuplicates;
        _moveButton.Enabled = idle && _mode is OperationMode.Classification
            or OperationMode.Browser;
        _applyRenameButton.Enabled = idle && _mode == OperationMode.RenamePreview;
        _viewButton.Enabled = idle && _grid.Rows.Count > 0;
    }

    private static Button CreateButton(string text, int width)
    {
        var button = new Button();
        ConfigureButton(button, text, width);
        return button;
    }

    private static void ConfigureButton(Button button, string text, int width)
    {
        button.Text = text;
        button.Width = width;
        button.Height = 34;
        button.Margin = new Padding(0, 0, 7, 5);
    }

    private static string Preview(string value, int max)
    {
        string singleLine = value.Replace("\r", " ", StringComparison.Ordinal)
            .Replace("\n", " ", StringComparison.Ordinal).Trim();
        return singleLine.Length <= max ? singleLine : singleLine[..max] + "…";
    }

    private static string CategoryText(FileCategory category) => category switch
    {
        FileCategory.CharacterCard => "角色卡",
        FileCategory.Preset => "预设",
        FileCategory.Beauty => "美化 / 主题",
        FileCategory.WorldBook => "世界书",
        FileCategory.RegexScript => "正则脚本",
        FileCategory.ExtensionPlugin => "插件 / 扩展",
        FileCategory.ImageAsset => "普通图片 / 素材",
        FileCategory.MixedPackage => "混合包 / 压缩包",
        FileCategory.Damaged => "损坏 / 无法读取",
        _ => "无法确定"
    };

    private static bool PathEquals(string left, string right) =>
        string.Equals(Path.GetFullPath(left), Path.GetFullPath(right),
            StringComparison.OrdinalIgnoreCase);

    private sealed record RowPayload(string GroupId, object Item);
}

using CardTools.Core;

namespace CardTools.Win;

public sealed class MainForm : Form
{
    private enum Mode
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

    private readonly TextBox _folder = new();
    private readonly DataGridView _grid = new();
    private readonly ProgressBar _progress = new();
    private readonly Label _status = new();
    private readonly Button _strict = new();
    private readonly Button _semantic = new();
    private readonly Button _classify = new();
    private readonly Button _rename = new();
    private readonly Button _browse = new();
    private readonly Button _cancel = new();
    private readonly Button _view = new();
    private readonly Button _selectAll = new();
    private readonly Button _clear = new();
    private readonly Button _move = new();
    private readonly Button _delete = new();
    private readonly Button _applyRename = new();

    private readonly Dictionary<string, DuplicateGroup> _strictGroups =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, SemanticDuplicateGroup> _semanticGroups =
        new(StringComparer.OrdinalIgnoreCase);

    private CancellationTokenSource? _cancellation;
    private Mode _mode;
    private bool _busy;

    public MainForm()
    {
        Text = "CardTools Windows｜角色卡文件工具";
        Width = 1380;
        Height = 860;
        MinimumSize = new Size(1024, 680);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Microsoft YaHei UI", 9F);
        BuildUi();
        UpdateActions();
    }

    private void BuildUi()
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

        root.Controls.Add(new Label
        {
            AutoSize = true,
            Text = "CardTools Windows",
            Font = new Font(Font.FontFamily, 21F, FontStyle.Bold),
            Margin = new Padding(0, 0, 0, 7)
        }, 0, 0);

        var folderRow = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 3,
            Margin = new Padding(0, 0, 0, 8)
        };
        folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        folderRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        folderRow.Controls.Add(new Label
        {
            AutoSize = true,
            Text = "扫描文件夹：",
            Anchor = AnchorStyles.Left,
            Padding = new Padding(0, 7, 6, 0)
        }, 0, 0);
        _folder.Dock = DockStyle.Fill;
        _folder.PlaceholderText = @"例如 D:\角色卡 或 E:\酒馆资源";
        folderRow.Controls.Add(_folder, 1, 0);
        var choose = MakeButton("选择文件夹", 110);
        choose.Click += (_, _) => ChooseFolder();
        folderRow.Controls.Add(choose, 2, 0);
        root.Controls.Add(folderRow, 0, 1);

        var operationRow = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            WrapContents = true,
            Margin = new Padding(0, 0, 0, 7)
        };
        ConfigureButton(_strict, "严格查重", 112);
        ConfigureButton(_semantic, "有效内容查重", 138);
        ConfigureButton(_classify, "酒馆文件分类", 138);
        ConfigureButton(_rename, "角色卡改名预览", 148);
        ConfigureButton(_browse, "浏览角色卡", 125);
        ConfigureButton(_cancel, "取消当前任务", 125);
        _strict.Click += async (_, _) => await ScanStrictAsync();
        _semantic.Click += async (_, _) => await ScanSemanticAsync();
        _classify.Click += async (_, _) => await ScanClassificationAsync();
        _rename.Click += async (_, _) => await ScanRenameAsync();
        _browse.Click += async (_, _) => await ScanBrowserAsync();
        _cancel.Click += (_, _) => _cancellation?.Cancel();
        operationRow.Controls.AddRange(new Control[]
        {
            _strict, _semantic, _classify, _rename, _browse, _cancel
        });
        root.Controls.Add(operationRow, 0, 2);

        var statusPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 1,
            Margin = new Padding(0, 0, 0, 7)
        };
        _progress.Dock = DockStyle.Top;
        _progress.Minimum = 0;
        _progress.Maximum = 100;
        _progress.Height = 8;
        _status.AutoSize = true;
        _status.Dock = DockStyle.Top;
        _status.Padding = new Padding(0, 5, 0, 2);
        _status.Text = "请选择文件夹，然后选择一个功能。删除和移动都会在执行前重新校验。";
        statusPanel.Controls.Add(_progress, 0, 0);
        statusPanel.Controls.Add(_status, 0, 1);
        root.Controls.Add(statusPanel, 0, 3);

        ConfigureGrid();
        root.Controls.Add(_grid, 0, 4);

        var actionRow = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            AutoSize = true,
            WrapContents = true,
            Padding = new Padding(0, 7, 0, 0)
        };
        ConfigureButton(_view, "查看当前行", 115);
        ConfigureButton(_selectAll, "全选可操作项", 125);
        ConfigureButton(_clear, "取消全部选择", 125);
        ConfigureButton(_move, "安全移动选中项", 145);
        ConfigureButton(_delete, "重新校验并删除", 145);
        ConfigureButton(_applyRename, "执行选中改名", 135);
        _view.Click += (_, _) => ViewCurrentRow();
        _selectAll.Click += (_, _) => SelectAllOperable();
        _clear.Click += (_, _) => ClearSelections();
        _move.Click += async (_, _) => await MoveSelectedAsync();
        _delete.Click += async (_, _) => await DeleteSelectedAsync();
        _applyRename.Click += async (_, _) => await ApplyRenamesAsync();
        actionRow.Controls.AddRange(new Control[]
        {
            _view, _selectAll, _clear, _move, _delete, _applyRename
        });
        root.Controls.Add(actionRow, 0, 5);
    }

    private void ConfigureGrid()
    {
        _grid.Dock = DockStyle.Fill;
        _grid.AllowUserToAddRows = false;
        _grid.AllowUserToDeleteRows = false;
        _grid.AllowUserToOrderColumns = true;
        _grid.RowHeadersVisible = false;
        _grid.MultiSelect = false;
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
            Width = 78,
            ReadOnly = true
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "类型",
            Width = 150,
            ReadOnly = true
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "名称",
            Width = 215,
            ReadOnly = true
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "说明 / 差异",
            AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill,
            MinimumWidth = 260,
            ReadOnly = true,
            DefaultCellStyle = new DataGridViewCellStyle
            {
                WrapMode = DataGridViewTriState.True
            }
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
        _grid.CellValueChanged += OnCellValueChanged;
        _grid.CellDoubleClick += (_, e) =>
        {
            if (e.RowIndex >= 0) ViewRow(_grid.Rows[e.RowIndex]);
        };
    }

    private void OnCellValueChanged(object? sender, DataGridViewCellEventArgs e)
    {
        if (e.RowIndex < 0 || e.ColumnIndex != KeepColumn) return;
        DataGridViewRow changed = _grid.Rows[e.RowIndex];
        if (changed.Cells[KeepColumn].ReadOnly
            || changed.Cells[KeepColumn].Value is not true) return;
        string group = CellText(changed, GroupColumn);
        foreach (DataGridViewRow row in _grid.Rows)
        {
            if (row.Index == changed.Index) continue;
            if (string.Equals(CellText(row, GroupColumn), group,
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
            _folder.Text = dialog.SelectedPath;
    }

    private string RequireFolder()
    {
        string path = _folder.Text.Trim();
        if (path.Length == 0 || !Directory.Exists(path))
            throw new DirectoryNotFoundException("请先选择一个存在的文件夹。 ");
        return Path.GetFullPath(path);
    }

    private async Task ScanStrictAsync()
    {
        await ExecuteAsync(async (progress, token) =>
        {
            IReadOnlyList<DuplicateGroup> groups =
                await StrictDuplicateScanner.ScanAsync(RequireFolder(), progress, token);
            PrepareMode(Mode.StrictDuplicates);
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
                        new RowInfo(group.GroupId, file),
                        selectable: true,
                        keepable: true);
                }
            }
            _status.Text = $"严格查重完成：{groups.Count} 组，可清理副本 "
                + $"{groups.Sum(group => group.Files.Count - 1)} 个。"
                + "请确认每组保留项，再选择要删除的副本。";
        });
    }

    private async Task ScanSemanticAsync()
    {
        await ExecuteAsync(async (progress, token) =>
        {
            SemanticScanResult result = await SemanticDuplicateScanner.ScanAsync(
                RequireFolder(), progress, token);
            PrepareMode(Mode.SemanticDuplicates);
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
                            + $"{new FileInfo(card.SourcePath).Length:N0} B",
                        card.SourcePath,
                        new RowInfo(group.GroupId, card),
                        selectable: true,
                        keepable: true);
                }
            }

            int variantNumber = 1;
            foreach (RelatedVariantGroup variant in result.RelatedVariants)
            {
                string groupId = $"V{variantNumber++:0000}";
                foreach (ParsedCharacterCard card in variant.Cards)
                {
                    int rowIndex = AddRow(false, false, groupId,
                        "同名但内容变化",
                        card.Name,
                        $"禁止一键删除 · {card.Format} · "
                            + $"开场白 {card.Greetings.Count} 个",
                        card.SourcePath,
                        new RowInfo(groupId, card),
                        selectable: false,
                        keepable: false);
                    _grid.Rows[rowIndex].DefaultCellStyle.BackColor = Color.MistyRose;
                }
            }
            _status.Text = $"有效内容相同 {result.ExactContentGroups.Count} 组；"
                + $"同名但有变化 {result.RelatedVariants.Count} 组；"
                + $"无法解析 {result.Failures.Count} 个。红色行只允许查看。";
        });
    }

    private async Task ScanClassificationAsync()
    {
        await ExecuteAsync(async (progress, token) =>
        {
            IReadOnlyList<ClassifiedFile> files = await TavernFileClassifier.ScanAsync(
                RequireFolder(), progress, token);
            PrepareMode(Mode.Classification);
            foreach (ClassifiedFile file in files)
            {
                string category = CategoryText(file.Category);
                AddRow(false, false, category, category, file.FileName,
                    $"{file.Subtype} · 可信度 {file.Confidence}%\n"
                        + $"为什么：{file.Reason}\n{file.Details}",
                    file.Path,
                    new RowInfo(category, file),
                    selectable: true,
                    keepable: false);
            }
            string summary = string.Join("；", files.GroupBy(file => file.Category)
                .Select(group => $"{CategoryText(group.Key)} {group.Count()}"));
            _status.Text = $"分类完成：{files.Count} 个文件。{summary}。"
                + "分类结果只能安全移动，不提供直接删除。";
        });
    }

    private async Task ScanRenameAsync()
    {
        await ExecuteAsync(async (progress, token) =>
        {
            string folder = RequireFolder();
            IReadOnlyList<RenamePlan> plans = await Task.Run(
                () => RenameService.Preview(folder, token), token);
            PrepareMode(Mode.RenamePreview);
            int number = 1;
            foreach (RenamePlan plan in plans)
            {
                AddRow(plan.NeedsRename, false, $"R{number++:0000}",
                    plan.NeedsRename ? "待改名" : "无需改名 / 跳过",
                    Path.GetFileName(plan.SourcePath),
                    $"内部角色名：{plan.CharacterName}\n"
                        + $"新文件名：{Path.GetFileName(plan.TargetPath)}\n{plan.Reason}",
                    plan.SourcePath,
                    new RowInfo(string.Empty, plan),
                    selectable: plan.NeedsRename,
                    keepable: false);
            }
            progress.Report(new ScanProgress(plans.Count, plans.Count,
                folder, "改名预览完成"));
            _status.Text = $"改名预览完成：需要改名 "
                + $"{plans.Count(plan => plan.NeedsRename)} 个。"
                + "确认前后对照后再执行。";
        });
    }

    private async Task ScanBrowserAsync()
    {
        await ExecuteAsync(async (progress, token) =>
        {
            var result = await CharacterCardBrowser.ScanAsync(
                RequireFolder(), progress, token);
            PrepareMode(Mode.Browser);
            int number = 1;
            foreach (ParsedCharacterCard card in result.Cards)
            {
                AddRow(false, false, $"C{number++:0000}",
                    $"{card.Format} 角色卡",
                    card.Name,
                    $"开场白 {card.Greetings.Count} 个\n{Preview(card.Persona, 160)}",
                    card.SourcePath,
                    new RowInfo(string.Empty, card),
                    selectable: true,
                    keepable: false);
            }
            _status.Text = $"浏览完成：角色卡 {result.Cards.Count} 张；"
                + $"非角色卡或无法解析 {result.Failures.Count} 个。"
                + "双击角色卡可查看人设和全部开场白。";
        });
    }

    private async Task MoveSelectedAsync()
    {
        EndGridEdit();
        if (_mode is not (Mode.Classification or Mode.Browser)) return;
        DataGridViewRow[] rows = SelectedRows().ToArray();
        if (rows.Length == 0)
        {
            ShowInfo("请先选择要移动的文件。");
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
            $"即将移动 {rows.Length} 个文件。\n\n"
                + "复制后会核对文件大小和 SHA-256，完全一致后才删除源文件。",
            "确认安全移动", MessageBoxButtons.OKCancel,
            MessageBoxIcon.Warning) != DialogResult.OK) return;

        await ExecuteAsync(async (progress, token) =>
        {
            int success = 0;
            var messages = new List<string>();
            for (int index = 0; index < rows.Length; index++)
            {
                string source = CellText(rows[index], PathColumn);
                SafeMoveResult result = await SafeFileOperations.MoveVerifiedAsync(
                    source, dialog.SelectedPath, token);
                if (result.Success) success++;
                messages.Add($"{(result.Success ? "成功" : "失败")}：{source}\n"
                    + result.Message);
                progress.Report(new ScanProgress(index + 1, rows.Length,
                    source, "正在安全移动"));
            }
            _status.Text = $"移动完成：成功 {success}，失败 {rows.Length - success}。"
                + "请重新扫描查看最新状态。";
            MessageBox.Show(this, string.Join("\n\n", messages.Take(30)),
                "移动结果", MessageBoxButtons.OK,
                success == rows.Length ? MessageBoxIcon.Information
                    : MessageBoxIcon.Warning);
        });
    }

    private async Task DeleteSelectedAsync()
    {
        EndGridEdit();
        if (_mode is not (Mode.StrictDuplicates or Mode.SemanticDuplicates)) return;
        DataGridViewRow[] selectedRows = SelectedRows().ToArray();
        if (selectedRows.Length == 0)
        {
            ShowInfo("请先选择要删除的重复副本。");
            return;
        }
        string[] groupIds = selectedRows
            .Select(row => CellText(row, GroupColumn))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
        foreach (string groupId in groupIds)
        {
            int keeperCount = RowsInGroup(groupId)
                .Count(row => row.Cells[KeepColumn].Value is true);
            if (keeperCount != 1)
            {
                MessageBox.Show(this,
                    $"组 {groupId} 必须明确选择且只能选择一个保留文件。",
                    "保留项不明确", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
        }
        if (MessageBox.Show(this,
            $"准备永久删除 {selectedRows.Length} 个副本。\n\n"
                + "删除前会重新计算 SHA-256 或重新解析有效内容；"
                + "只要结果改变就会安全跳过。",
            "第一次确认", MessageBoxButtons.OKCancel,
            MessageBoxIcon.Warning) != DialogResult.OK) return;
        if (MessageBox.Show(this, "删除不可撤销。确认继续吗？",
            "最后确认", MessageBoxButtons.YesNo,
            MessageBoxIcon.Stop) != DialogResult.Yes) return;

        await ExecuteAsync(async (progress, token) =>
        {
            int deleted = 0;
            var logs = new List<string>();
            for (int groupIndex = 0; groupIndex < groupIds.Length; groupIndex++)
            {
                string groupId = groupIds[groupIndex];
                DataGridViewRow keeperRow = RowsInGroup(groupId)
                    .Single(row => row.Cells[KeepColumn].Value is true);
                string keeperPath = CellText(keeperRow, PathColumn);
                string[] paths = selectedRows
                    .Where(row => string.Equals(CellText(row, GroupColumn), groupId,
                        StringComparison.OrdinalIgnoreCase))
                    .Select(row => CellText(row, PathColumn))
                    .ToArray();
                IReadOnlyList<DeleteResult> results = _mode == Mode.StrictDuplicates
                    ? await SafeFileOperations.DeleteExactDuplicatesAsync(
                        _strictGroups[groupId], keeperPath, paths, token)
                    : await SafeFileOperations.DeleteSemanticDuplicatesAsync(
                        _semanticGroups[groupId], keeperPath, paths, token);
                deleted += results.Count(result => result.Success);
                logs.AddRange(results.Select(result =>
                    $"{(result.Success ? "已删除" : "已跳过")}：{result.Path}\n"
                        + result.Message));
                progress.Report(new ScanProgress(groupIndex + 1, groupIds.Length,
                    keeperPath, "正在重新校验并删除"));
            }
            _status.Text = $"删除流程完成：成功删除 {deleted} 个。"
                + "请重新扫描查看最新状态。";
            MessageBox.Show(this, string.Join("\n\n", logs.Take(40)),
                "删除结果", MessageBoxButtons.OK,
                deleted > 0 ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
        });
    }

    private async Task ApplyRenamesAsync()
    {
        EndGridEdit();
        if (_mode != Mode.RenamePreview) return;
        RenamePlan[] plans = SelectedRows()
            .Select(row => (row.Tag as RowInfo)?.Data)
            .OfType<RenamePlan>()
            .Where(plan => plan.NeedsRename)
            .ToArray();
        if (plans.Length == 0)
        {
            ShowInfo("没有选择需要改名的文件。");
            return;
        }
        if (MessageBox.Show(this,
            $"确认按预览结果原地修改 {plans.Length} 个文件名吗？\n"
                + "角色卡内部内容不会改变，重名会自动追加编号。",
            "确认批量改名", MessageBoxButtons.OKCancel,
            MessageBoxIcon.Warning) != DialogResult.OK) return;

        await ExecuteAsync(async (progress, token) =>
        {
            IReadOnlyList<RenamePlan> results = await Task.Run(
                () => RenameService.Apply(plans), token);
            int success = results.Count(result => result.Reason == "改名完成");
            progress.Report(new ScanProgress(results.Count, results.Count,
                RequireFolder(), "批量改名完成"));
            _status.Text = $"改名完成：成功 {success}，其他 {results.Count - success}。"
                + "请重新扫描查看最新文件名。";
            MessageBox.Show(this,
                string.Join("\n", results.Select(result =>
                    $"{result.Reason}：{Path.GetFileName(result.SourcePath)} → "
                        + Path.GetFileName(result.TargetPath)).Take(50)),
                "改名结果", MessageBoxButtons.OK, MessageBoxIcon.Information);
        });
    }

    private void ViewCurrentRow()
    {
        if (_grid.CurrentRow is null)
        {
            ShowInfo("请先选择一行。");
            return;
        }
        ViewRow(_grid.CurrentRow);
    }

    private void ViewRow(DataGridViewRow row)
    {
        object? data = (row.Tag as RowInfo)?.Data;
        switch (data)
        {
            case ParsedCharacterCard card:
                using (var detail = new CardDetailForm(card))
                    detail.ShowDialog(this);
                break;
            case ClassifiedFile file:
                MessageBox.Show(this,
                    $"分类：{CategoryText(file.Category)}\n"
                        + $"子类型：{file.Subtype}\n可信度：{file.Confidence}%\n\n"
                        + $"为什么：{file.Reason}\n\n详情：{file.Details}\n\n"
                        + $"路径：{file.Path}",
                    file.FileName, MessageBoxButtons.OK, MessageBoxIcon.Information);
                break;
            case RenamePlan plan:
                MessageBox.Show(this,
                    $"原文件：{plan.SourcePath}\n\n新文件：{plan.TargetPath}\n\n"
                        + $"内部角色名：{plan.CharacterName}\n说明：{plan.Reason}",
                    "改名前后对照", MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                break;
            case DuplicateFile duplicate:
                MessageBox.Show(this,
                    $"文件：{duplicate.Path}\n大小：{duplicate.Size:N0} B\n"
                        + $"SHA-256：{duplicate.Sha256}",
                    "严格重复文件", MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                break;
        }
    }

    private void SelectAllOperable()
    {
        EndGridEdit();
        foreach (DataGridViewRow row in _grid.Rows)
        {
            if (!row.Cells[SelectColumn].ReadOnly
                && row.Cells[KeepColumn].Value is not true)
                row.Cells[SelectColumn].Value = true;
        }
    }

    private void ClearSelections()
    {
        EndGridEdit();
        foreach (DataGridViewRow row in _grid.Rows)
        {
            if (!row.Cells[SelectColumn].ReadOnly)
                row.Cells[SelectColumn].Value = false;
        }
    }

    private void PrepareMode(Mode mode)
    {
        _mode = mode;
        _strictGroups.Clear();
        _semanticGroups.Clear();
        _grid.Rows.Clear();
    }

    private async Task ExecuteAsync(
        Func<IProgress<ScanProgress>, CancellationToken, Task> operation)
    {
        if (_busy) return;
        _busy = true;
        _cancellation = new CancellationTokenSource();
        _progress.Value = 0;
        UpdateActions();
        var progress = new Progress<ScanProgress>(item =>
        {
            _progress.Value = item.Total <= 0 ? 0 : Math.Clamp(
                (int)Math.Round(item.Completed * 100d / item.Total), 0, 100);
            _status.Text = $"{item.Stage}：{item.Completed}/{item.Total}\n"
                + item.CurrentPath;
        });
        try
        {
            await operation(progress, _cancellation.Token);
            _progress.Value = 100;
        }
        catch (OperationCanceledException)
        {
            _status.Text = "任务已取消。请重新运行扫描后再进行文件操作。";
            PrepareMode(Mode.None);
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
        string name, string details, string path, RowInfo info,
        bool selectable, bool keepable)
    {
        int index = _grid.Rows.Add(selected, keep, group, type, name, details, path);
        DataGridViewRow row = _grid.Rows[index];
        row.Tag = info;
        row.Cells[SelectColumn].ReadOnly = !selectable;
        row.Cells[KeepColumn].ReadOnly = !keepable;
        if (!selectable) row.Cells[SelectColumn].Style.BackColor = Color.Gainsboro;
        if (!keepable) row.Cells[KeepColumn].Style.BackColor = Color.Gainsboro;
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

    private IEnumerable<DataGridViewRow> RowsInGroup(string groupId)
    {
        foreach (DataGridViewRow row in _grid.Rows)
        {
            if (string.Equals(CellText(row, GroupColumn), groupId,
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
        _folder.Enabled = idle;
        _strict.Enabled = idle;
        _semantic.Enabled = idle;
        _classify.Enabled = idle;
        _rename.Enabled = idle;
        _browse.Enabled = idle;
        _cancel.Enabled = _busy;
        _grid.Enabled = idle;
        _view.Enabled = idle && _grid.Rows.Count > 0;
        _selectAll.Enabled = idle && _grid.Rows.Count > 0;
        _clear.Enabled = idle && _grid.Rows.Count > 0;
        _move.Enabled = idle && (_mode is Mode.Classification or Mode.Browser);
        _delete.Enabled = idle
            && (_mode is Mode.StrictDuplicates or Mode.SemanticDuplicates);
        _applyRename.Enabled = idle && _mode == Mode.RenamePreview;
    }

    private void ShowInfo(string message) => MessageBox.Show(this, message,
        "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);

    private static string CellText(DataGridViewRow row, int column) =>
        Convert.ToString(row.Cells[column].Value) ?? string.Empty;

    private static bool PathEquals(string left, string right) =>
        string.Equals(Path.GetFullPath(left), Path.GetFullPath(right),
            StringComparison.OrdinalIgnoreCase);

    private static string Preview(string value, int maxLength)
    {
        string text = value.Replace("\r", " ", StringComparison.Ordinal)
            .Replace("\n", " ", StringComparison.Ordinal).Trim();
        return text.Length <= maxLength ? text : text[..maxLength] + "…";
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

    private static Button MakeButton(string text, int width)
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

    private sealed record RowInfo(string GroupId, object Data);
}

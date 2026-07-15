using CardTools.Core;
using System.Diagnostics;

namespace CardTools.Win;

public sealed class CardDetailForm : Form
{
    private readonly ParsedCharacterCard _card;
    private readonly ListBox _greetingList = new();
    private readonly TextBox _greetingText = new();

    public CardDetailForm(ParsedCharacterCard card)
    {
        _card = card;
        Text = $"{card.Name}｜角色卡详情";
        Width = 1180;
        Height = 800;
        MinimumSize = new Size(850, 600);
        StartPosition = FormStartPosition.CenterParent;
        Font = new Font("Microsoft YaHei UI", 9F);
        BuildInterface();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            foreach (Control control in Controls)
                DisposeImages(control);
        }
        base.Dispose(disposing);
    }

    private void BuildInterface()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(12)
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(root);

        var heading = new Label
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            Text = $"{_card.Name}\n{_card.Format} 角色卡 · 开场白 {_card.Greetings.Count} 个\n{_card.SourcePath}",
            Font = new Font(Font.FontFamily, 16F, FontStyle.Bold),
            Padding = new Padding(0, 0, 0, 8)
        };
        root.Controls.Add(heading, 0, 0);

        var split = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Vertical,
            SplitterDistance = 350,
            Panel1MinSize = 240,
            Panel2MinSize = 450
        };
        root.Controls.Add(split, 0, 1);
        BuildCoverPanel(split.Panel1);
        BuildContentPanel(split.Panel2);

        var actions = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            AutoSize = true,
            FlowDirection = FlowDirection.RightToLeft,
            Padding = new Padding(0, 8, 0, 0)
        };
        var close = new Button { Text = "关闭", Width = 100, Height = 34 };
        close.Click += (_, _) => Close();
        var openFolder = new Button
        {
            Text = "打开所在文件夹",
            Width = 140,
            Height = 34
        };
        openFolder.Click += (_, _) => OpenInExplorer();
        actions.Controls.Add(close);
        actions.Controls.Add(openFolder);
        root.Controls.Add(actions, 0, 2);
    }

    private void BuildCoverPanel(Control parent)
    {
        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            Padding = new Padding(4)
        };
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        parent.Controls.Add(panel);

        var picture = new PictureBox
        {
            Dock = DockStyle.Fill,
            SizeMode = PictureBoxSizeMode.Zoom,
            BackColor = Color.FromArgb(240, 240, 240)
        };
        if (_card.Format.Equals("PNG", StringComparison.OrdinalIgnoreCase)
            && File.Exists(_card.SourcePath))
        {
            try
            {
                using Image original = Image.FromFile(_card.SourcePath);
                picture.Image = new Bitmap(original);
            }
            catch (Exception exception) when (exception is ArgumentException
                or OutOfMemoryException
                or IOException)
            {
                picture.Controls.Add(new Label
                {
                    Dock = DockStyle.Fill,
                    TextAlign = ContentAlignment.MiddleCenter,
                    Text = $"封面无法读取\n{exception.Message}"
                });
            }
        }
        else
        {
            picture.Controls.Add(new Label
            {
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleCenter,
                Font = new Font(Font.FontFamily, 15F, FontStyle.Bold),
                Text = "JSON 角色卡\n没有内嵌封面"
            });
        }
        panel.Controls.Add(picture, 0, 0);

        var format = new Label
        {
            AutoSize = true,
            Dock = DockStyle.Top,
            TextAlign = ContentAlignment.MiddleCenter,
            Text = $"格式：{_card.Format}\n文件大小：{new FileInfo(_card.SourcePath).Length:N0} B",
            Padding = new Padding(0, 8, 0, 0)
        };
        panel.Controls.Add(format, 0, 1);
    }

    private void BuildContentPanel(Control parent)
    {
        var tabs = new TabControl { Dock = DockStyle.Fill };
        parent.Controls.Add(tabs);

        var personaPage = new TabPage("CHAR 人设");
        var personaText = new TextBox
        {
            Dock = DockStyle.Fill,
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Both,
            WordWrap = true,
            Font = new Font("Microsoft YaHei UI", 11F),
            Text = _card.Persona
        };
        personaPage.Controls.Add(personaText);
        tabs.TabPages.Add(personaPage);

        var greetingPage = new TabPage($"开场白（{_card.Greetings.Count}）");
        var greetingSplit = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Horizontal,
            SplitterDistance = 170,
            Panel1MinSize = 100,
            Panel2MinSize = 180
        };
        greetingPage.Controls.Add(greetingSplit);
        _greetingList.Dock = DockStyle.Fill;
        for (int i = 0; i < _card.Greetings.Count; i++)
            _greetingList.Items.Add($"开场白 {i + 1}");
        _greetingList.SelectedIndexChanged += (_, _) => ShowGreeting();
        greetingSplit.Panel1.Controls.Add(_greetingList);

        var greetingPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2
        };
        greetingPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        greetingPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        greetingSplit.Panel2.Controls.Add(greetingPanel);
        _greetingText.Dock = DockStyle.Fill;
        _greetingText.Multiline = true;
        _greetingText.ReadOnly = true;
        _greetingText.ScrollBars = ScrollBars.Both;
        _greetingText.WordWrap = true;
        _greetingText.Font = new Font("Microsoft YaHei UI", 11F);
        greetingPanel.Controls.Add(_greetingText, 0, 0);

        var navigation = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            AutoSize = true,
            FlowDirection = FlowDirection.LeftToRight,
            Padding = new Padding(0, 6, 0, 0)
        };
        var previous = new Button { Text = "← 上一个", Width = 110, Height = 32 };
        var next = new Button { Text = "下一个 →", Width = 110, Height = 32 };
        previous.Click += (_, _) => ChangeGreeting(-1);
        next.Click += (_, _) => ChangeGreeting(1);
        navigation.Controls.Add(previous);
        navigation.Controls.Add(next);
        greetingPanel.Controls.Add(navigation, 0, 1);
        tabs.TabPages.Add(greetingPage);

        var fingerprintPage = new TabPage("内容指纹");
        fingerprintPage.Controls.Add(new TextBox
        {
            Dock = DockStyle.Fill,
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            Font = new Font("Consolas", 10F),
            Text = $"有效内容：{_card.SemanticHash}\r\n\r\n"
                + $"人设：{_card.PersonaHash}\r\n\r\n"
                + $"开场白：{_card.GreetingsHash}\r\n\r\n"
                + $"世界书：{_card.WorldBookHash}\r\n\r\n"
                + $"扩展/正则：{_card.ExtensionsHash}"
        });
        tabs.TabPages.Add(fingerprintPage);

        if (_card.Greetings.Count > 0)
            _greetingList.SelectedIndex = 0;
        else
            _greetingText.Text = "（这张卡没有读取到开场白）";
    }

    private void ChangeGreeting(int offset)
    {
        if (_card.Greetings.Count == 0) return;
        int current = _greetingList.SelectedIndex < 0 ? 0 : _greetingList.SelectedIndex;
        _greetingList.SelectedIndex = (current + offset + _card.Greetings.Count)
            % _card.Greetings.Count;
    }

    private void ShowGreeting()
    {
        int index = _greetingList.SelectedIndex;
        if (index >= 0 && index < _card.Greetings.Count)
            _greetingText.Text = _card.Greetings[index];
    }

    private void OpenInExplorer()
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "explorer.exe",
                Arguments = $"/select,\"{_card.SourcePath}\"",
                UseShellExecute = true
            });
        }
        catch (Exception exception) when (exception is InvalidOperationException
            or System.ComponentModel.Win32Exception)
        {
            MessageBox.Show(this, exception.Message, "无法打开文件夹",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static void DisposeImages(Control control)
    {
        if (control is PictureBox picture && picture.Image is not null)
        {
            picture.Image.Dispose();
            picture.Image = null;
        }
        foreach (Control child in control.Controls)
            DisposeImages(child);
    }
}

package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.io.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MhtExtractorGUI extends JFrame {

    // 模式选择组件
    private JCheckBox chkTextMode; // 纯文本提取模式
    private JCheckBox chkImageMode; // 图片提取模式

    // 主界面组件
    private JTextField txtInputFolder;
    private JTextField txtOutputPath;
    private JButton btnBrowseInput;
    private JButton btnBrowseOutput;
    private JButton btnExecute;
    private JButton btnConfigManage;
    private JCheckBox chkSeparateFiles;
    private JLabel lblOutput;
    private JCheckBox chkOpenFolderAfterComplete;

    private JPanel textExtractPanel;
    private boolean isTextModeSelected = false;
    private boolean isImageModeSelected = false;

    // 控制台组件
    private static final Logger logger = LoggerFactory.getLogger(MhtExtractorGUI.class);
    private JTextArea consoleArea;
    private JScrollPane consoleScrollPane;

    // 内容限定组件
    private JCheckBox chkEnableLimit;
    private JTextField txtStartMarker;
    private JTextField txtEndMarker;
    private JCheckBox chkUseRegex;

    // 自定义模板组件
    private JCheckBox chkEnableCustomTemplate;
    private JTextArea txtStartTemplate;
    private JTextArea txtEndTemplate;
    private JLabel lblTemplateVars;

    // 章节名组件
    private JCheckBox chkAddChapterNumber;

    // 隐藏标题提取组件
    private JCheckBox chkEnableTitleExtract;
    private JTextField txtTitleStartMarker;
    private JTextField txtTitleEndMarker;
    private JCheckBox chkTitleUseRegex;

    // 图片名称提取限定组件
    private JCheckBox chkEnableImageNameExtract;
    private JTextField txtImageNameStartMarker;
    private JTextField txtImageNameEndMarker;

    // 图片区域限定组件
    private JCheckBox chkEnableImageRegionExtract;
    private JTextField txtImageRegionStartMarker;
    private JTextField txtImageRegionEndMarker;

    // 图片后处理组件
    private JCheckBox chkEnableImageZip;
    private JCheckBox chkEnableImageRename;

    // 图片提取面板
    private JPanel imageExtractPanel;

    private ConfigManager configManager;
    private JComboBox<String> importConfigComboBox;


    public MhtExtractorGUI() {
        configManager = new ConfigManager();
        initUI();
    }

    private void initUI() {
        setTitle("MHT 资源提取器");
        setSize(850, 900);
        setLocationRelativeTo(null);
        setResizable(true);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel mainContainer = new JPanel(new BorderLayout(10, 10));
        mainContainer.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // 创建主滚动面板
        JScrollPane mainScrollPane = new JScrollPane();
        mainScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        mainScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        mainScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // 创建主内容面板（使用 GridBagLayout）
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new GridBagLayout());
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // === 第 1 行：模式选择 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        JPanel modePanel = createModeSelectionPanel();
        contentPanel.add(modePanel, gbc);

        // === 第 2 行：输入文件夹 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblInput = new JLabel("输入文件夹目录：");
        lblInput.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        contentPanel.add(lblInput, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtInputFolder = new JTextField("", 30);
        txtInputFolder.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        txtInputFolder.setEnabled(false);
        contentPanel.add(txtInputFolder, gbc);

        gbc.gridx = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        btnBrowseInput = new JButton("浏览...");
        btnBrowseInput.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        btnBrowseInput.setPreferredSize(new Dimension(80, 28));
        btnBrowseInput.setEnabled(false);
        btnBrowseInput.addActionListener(this::browseInputFolder);
        contentPanel.add(btnBrowseInput, gbc);

        // === 第 3 行：输出文件夹目录 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        lblOutput = new JLabel("输出文件夹目录：");
        lblOutput.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        contentPanel.add(lblOutput, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtOutputPath = new JTextField("", 30);
        txtOutputPath.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        txtOutputPath.setEnabled(false);
        contentPanel.add(txtOutputPath, gbc);

        gbc.gridx = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        btnBrowseOutput = new JButton("浏览...");
        btnBrowseOutput.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        btnBrowseOutput.setPreferredSize(new Dimension(80, 28));
        btnBrowseOutput.setEnabled(false);
        btnBrowseOutput.addActionListener(this::browseOutputPath);
        contentPanel.add(btnBrowseOutput, gbc);

        // === 第 4 行：导入配置 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblImportConfig = new JLabel("导入配置：");
        lblImportConfig.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        contentPanel.add(lblImportConfig, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        importConfigComboBox = new JComboBox<>();
        importConfigComboBox.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        contentPanel.add(importConfigComboBox, gbc);

        gbc.gridx = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JButton btnImportConfig = new JButton("导入");
        btnImportConfig.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        btnImportConfig.setPreferredSize(new Dimension(80, 28));
        btnImportConfig.addActionListener(e -> importConfig());
        contentPanel.add(btnImportConfig, gbc);

        // === 第 5 行：单独输出模式 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        chkSeparateFiles = new JCheckBox("单独输出模式 (将每个 MHT 文件提取到单独的文件中)");
        chkSeparateFiles.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        chkSeparateFiles.setEnabled(false);
        chkSeparateFiles.addActionListener(e -> updateUIForMode());
        contentPanel.add(chkSeparateFiles, gbc);

        // === 第 6 行：提取完成后打开输出文件夹 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        chkOpenFolderAfterComplete = new JCheckBox("提取完成后打开输出文件夹");
        chkOpenFolderAfterComplete.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        chkOpenFolderAfterComplete.setEnabled(false);
        contentPanel.add(chkOpenFolderAfterComplete, gbc);

        // === 第 7 行：文本提取配置面板 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        textExtractPanel = createTextExtractPanel();
        textExtractPanel.setVisible(false);
        contentPanel.add(textExtractPanel, gbc);

        // === 第 8 行：图片提取配置面板 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        imageExtractPanel = createImageExtractPanel();
        imageExtractPanel.setVisible(false);
        contentPanel.add(imageExtractPanel, gbc);

        // === 第 9 行：运行日志 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        JPanel consolePanel = createConsolePanel();
        contentPanel.add(consolePanel, gbc);

        // === 按钮面板 ===
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weighty = 0;
        JPanel buttonPanel = createButtonPanel();
        contentPanel.add(buttonPanel, gbc);

        mainScrollPane.setViewportView(contentPanel);
        mainContainer.add(mainScrollPane, BorderLayout.CENTER);

        add(mainContainer);

        // 初始化 UI 状态
        updateAllUIStates();
        refreshConfigComboBox();

        SwingUtilities.invokeLater(() -> {
            consoleArea.append("欢迎使用 MHT 资源提取器！\n");
            consoleArea.append("请选择提取模式，然后选择 MHT 文件所在文件夹，点击“开始执行”。\n");
        });
    }

    /**
     * 创建模式选择面板（第一行）
     */
    private JPanel createModeSelectionPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 200, 200)),
                BorderFactory.createEmptyBorder(5, 5, 10, 5)
        ));

        chkTextMode = new JCheckBox("纯文本提取模式");
        chkTextMode.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        chkTextMode.addActionListener(this::onModeSelectionChanged);

        chkImageMode = new JCheckBox("图片提取模式");
        chkImageMode.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        chkImageMode.addActionListener(this::onModeSelectionChanged);

        panel.add(chkTextMode);
        panel.add(chkImageMode);

        return panel;
    }

    /**
     * 创建文本提取配置面板
     */
    private JPanel createTextExtractPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0, 102, 204), 1),
                "文本提取配置",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                new Font("微软雅黑", Font.BOLD, 13),
                new Color(0, 102, 204)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // --- 章节标题区域 ---
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        JLabel lblTemplateTitle = new JLabel("章节标题设置");
        lblTemplateTitle.setFont(new Font("微软雅黑", Font.BOLD, 13));
        lblTemplateTitle.setForeground(new Color(0, 102, 204));
        panel.add(lblTemplateTitle, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkAddChapterNumber = new JCheckBox("为每个 MHT 文件添加章节名（仅合并模式有效）");
        chkAddChapterNumber.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkAddChapterNumber.setToolTipText("选中后生成“第一章”、“第二章”等序号");
        chkAddChapterNumber.addActionListener(e -> toggleTitleSettings());
        panel.add(chkAddChapterNumber, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkEnableTitleExtract = new JCheckBox("启用隐藏章节标题提取（从源码固定位置提取标题）");
        chkEnableTitleExtract.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkEnableTitleExtract.setToolTipText("可独立使用！若提取到标题，将直接作为章节头；若同时勾选上方选项，则格式为“序号 标题”");
        chkEnableTitleExtract.addActionListener(e -> toggleTitleSettings());
        panel.add(chkEnableTitleExtract, gbc);

        // 标题提取标记输入区
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblTitleStart = new JLabel("标题起始标记：");
        lblTitleStart.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblTitleStart, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtTitleStartMarker = new JTextField("", 40);
        txtTitleStartMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtTitleStartMarker, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblTitleEnd = new JLabel("标题结束标记：");
        lblTitleEnd.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblTitleEnd, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtTitleEndMarker = new JTextField("", 40);
        txtTitleEndMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtTitleEndMarker, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        JPanel titleBottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        titleBottomPanel.setOpaque(false);
        chkTitleUseRegex = new JCheckBox("使用正则表达式匹配标题");
        chkTitleUseRegex.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        titleBottomPanel.add(chkTitleUseRegex);
        panel.add(titleBottomPanel, gbc);

        // 分隔线
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        panel.add(new JSeparator(), gbc);

        // --- 内容限定区域 ---
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        JLabel lblLimitTitle = new JLabel("正文内容提取限定");
        lblLimitTitle.setFont(new Font("微软雅黑", Font.BOLD, 13));
        lblLimitTitle.setForeground(new Color(0, 102, 204));
        panel.add(lblLimitTitle, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkEnableLimit = new JCheckBox("启用内容限定（只提取指定区域的内容）");
        chkEnableLimit.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkEnableLimit.addActionListener(e -> toggleLimitSettings());
        panel.add(chkEnableLimit, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblStartMarker = new JLabel("起始标记：");
        lblStartMarker.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblStartMarker, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtStartMarker = new JTextField("", 40);
        txtStartMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtStartMarker, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblEndMarker = new JLabel("结束标记：");
        lblEndMarker.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblEndMarker, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtEndMarker = new JTextField("", 40);
        txtEndMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtEndMarker, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        JPanel limitBottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        limitBottomPanel.setOpaque(false);
        chkUseRegex = new JCheckBox("使用正则表达式匹配");
        chkUseRegex.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        limitBottomPanel.add(chkUseRegex);
        panel.add(limitBottomPanel, gbc);

        // 分隔线
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        panel.add(new JSeparator(), gbc);

        // --- 自定义模板内容区域 ---
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        JLabel lblRealTemplateTitle = new JLabel("自定义文件开头/结尾模板内容");
        lblRealTemplateTitle.setFont(new Font("微软雅黑", Font.BOLD, 13));
        lblRealTemplateTitle.setForeground(new Color(0, 102, 204));
        panel.add(lblRealTemplateTitle, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkEnableCustomTemplate = new JCheckBox("启用自定义模板（覆盖默认开头结尾格式）");
        chkEnableCustomTemplate.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkEnableCustomTemplate.addActionListener(e -> toggleTemplateSettings());
        panel.add(chkEnableCustomTemplate, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblStartTemplate = new JLabel("开头模板：");
        lblStartTemplate.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblStartTemplate, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtStartTemplate = new JTextArea(1, 40);
        txtStartTemplate.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        txtStartTemplate.setLineWrap(false);
        txtStartTemplate.setWrapStyleWord(false);
        txtStartTemplate.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200), 1));
        txtStartTemplate.setText(escapeNewlines(MhtBatchProcessor.DEFAULT_START_TEMPLATE));
        txtStartTemplate.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                    e.consume();
                    int caretPos = txtStartTemplate.getCaretPosition();
                    try {
                        txtStartTemplate.getDocument().insertString(caretPos, "\\n", null);
                    } catch (javax.swing.text.BadLocationException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });
        JScrollPane startTemplateScroll = new JScrollPane(txtStartTemplate);
        startTemplateScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        startTemplateScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(startTemplateScroll, gbc);


        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblEndTemplate = new JLabel("结尾模板：");
        lblEndTemplate.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblEndTemplate, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtEndTemplate = new JTextArea(1, 40);
        txtEndTemplate.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        txtEndTemplate.setLineWrap(false);
        txtEndTemplate.setWrapStyleWord(false);
        txtEndTemplate.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200), 1));
        txtEndTemplate.setText(escapeNewlines(MhtBatchProcessor.DEFAULT_END_TEMPLATE));
        txtEndTemplate.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                    e.consume();
                    int caretPos = txtEndTemplate.getCaretPosition();
                    try {
                        txtEndTemplate.getDocument().insertString(caretPos, "\\n", null);
                    } catch (javax.swing.text.BadLocationException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });
        JScrollPane endTemplateScroll = new JScrollPane(txtEndTemplate);
        endTemplateScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        endTemplateScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(endTemplateScroll, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        lblTemplateVars = new JLabel("可用变量：{filename} | {datetime} | {date} | {time} | \\n");
        lblTemplateVars.setFont(new Font("微软雅黑", Font.ITALIC, 11));
        lblTemplateVars.setForeground(new Color(100, 100, 200));
        panel.add(lblTemplateVars, gbc);

        return panel;
    }

    /**
     * 创建图片提取配置面板
     */
    private JPanel createImageExtractPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0, 102, 204), 1),
                "图片提取配置",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                new Font("微软雅黑", Font.BOLD, 13),
                new Color(0, 102, 204)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // --- 图片名称提取限定区域 ---
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        JLabel lblImageNameTitle = new JLabel("图片名称提取限定");
        lblImageNameTitle.setFont(new Font("微软雅黑", Font.BOLD, 13));
        lblImageNameTitle.setForeground(new Color(0, 102, 204));
        panel.add(lblImageNameTitle, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkEnableImageNameExtract = new JCheckBox("启用名称提取限定（从 HTML 中提取标题作为文件夹/文件名）");
        chkEnableImageNameExtract.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkEnableImageNameExtract.addActionListener(e -> toggleImageNameSettings());
        panel.add(chkEnableImageNameExtract, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblImageNameStart = new JLabel("名称起始标记：");
        lblImageNameStart.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblImageNameStart, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtImageNameStartMarker = new JTextField("", 40);
        txtImageNameStartMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtImageNameStartMarker, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblImageNameEnd = new JLabel("名称结束标记：");
        lblImageNameEnd.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblImageNameEnd, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtImageNameEndMarker = new JTextField("", 40);
        txtImageNameEndMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtImageNameEndMarker, gbc);

        // 分隔线
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        panel.add(new JSeparator(), gbc);

        // --- 图片区域提取限定区域 ---
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        JLabel lblImageRegionTitle = new JLabel("图片区域提取限定");
        lblImageRegionTitle.setFont(new Font("微软雅黑", Font.BOLD, 13));
        lblImageRegionTitle.setForeground(new Color(0, 102, 204));
        panel.add(lblImageRegionTitle, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkEnableImageRegionExtract = new JCheckBox("启用区域限定（只提取指定位置的图片，跳过广告等）");
        chkEnableImageRegionExtract.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkEnableImageRegionExtract.addActionListener(e -> toggleImageRegionSettings());
        panel.add(chkEnableImageRegionExtract, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblImageRegionStart = new JLabel("区域起始标记：");
        lblImageRegionStart.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblImageRegionStart, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtImageRegionStartMarker = new JTextField("", 40);
        txtImageRegionStartMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtImageRegionStartMarker, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        JLabel lblImageRegionEnd = new JLabel("区域结束标记：");
        lblImageRegionEnd.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblImageRegionEnd, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        txtImageRegionEndMarker = new JTextField("", 40);
        txtImageRegionEndMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtImageRegionEndMarker, gbc);

        // 分隔线
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        panel.add(new JSeparator(), gbc);

        // --- 图片后处理选项 ---
        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        JLabel lblPostProcess = new JLabel("图片后处理");
        lblPostProcess.setFont(new Font("微软雅黑", Font.BOLD, 13));
        lblPostProcess.setForeground(new Color(0, 102, 204));
        panel.add(lblPostProcess, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkEnableImageRename = new JCheckBox("按照顺序为图片批量重命名（格式：001、002、003...）");
        chkEnableImageRename.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(chkEnableImageRename, gbc);

        gbc.gridy = row++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkEnableImageZip = new JCheckBox("提取后打包为 ZIP 文件");
        chkEnableImageZip.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(chkEnableImageZip, gbc);

        return panel;
    }

    /**
     * 创建运行日志面板
     */
    private JPanel createConsolePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("运行日志"));
        panel.setPreferredSize(new Dimension(350, 250));

        consoleArea = new JTextArea(12, 40);
        consoleArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        consoleArea.setEditable(false);
        consoleArea.setBackground(new Color(245, 245, 245));
        consoleArea.setLineWrap(true);
        consoleArea.setWrapStyleWord(true);

        TextAreaAppender.setTextArea(consoleArea);

        consoleScrollPane = new JScrollPane(consoleArea);
        consoleScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        consoleScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(consoleScrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * 创建按钮面板
     */
    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        btnExecute = new JButton("开始执行");
        btnExecute.setFont(new Font("微软雅黑", Font.BOLD, 15));
        btnExecute.setPreferredSize(new Dimension(160, 45));
        btnExecute.setBackground(new Color(0, 153, 76));
        btnExecute.setForeground(Color.WHITE);
        btnExecute.setOpaque(true);
        btnExecute.setBorderPainted(false);
        btnExecute.setEnabled(false);
        btnExecute.addActionListener(this::executeProcess);
        panel.add(btnExecute);

        btnConfigManage = new JButton("保存/删除配置");
        btnConfigManage.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        btnConfigManage.setPreferredSize(new Dimension(160, 45));
        btnConfigManage.setEnabled(false);
        btnConfigManage.addActionListener(e -> showConfigDialog());
        panel.add(btnConfigManage);

        return panel;
    }

    // ==================== 模式切换与 UI 状态管理 ====================

    /**
     * 处理模式选择变化
     */
    private void onModeSelectionChanged(ActionEvent e) {
        Object source = e.getSource();

        // 实现互斥：选中一个时取消另一个
        if (source == chkTextMode) {
            if (chkTextMode.isSelected()) {
                logger.info("已选择纯文本提取模式");
                chkImageMode.setSelected(false);
            } else {
                logger.info("已取消纯文本提取模式，清空选项");
                clearTextModeConfig();
            }
        } else if (source == chkImageMode) {
            if (chkImageMode.isSelected()) {
                logger.info("已选择图片提取模式");
                chkTextMode.setSelected(false);
            } else {
                logger.info("已取消图片提取模式，清空选项");
                clearImageModeConfig();
            }
        }

        isTextModeSelected = chkTextMode.isSelected();
        isImageModeSelected = chkImageMode.isSelected();

        updateAllUIStates();
    }

    /**
     * 更新所有 UI 组件的状态
     */
    private void updateAllUIStates() {
        boolean anyModeSelected = isTextModeSelected || isImageModeSelected;

        // 1. 更新输入输出控件的启用状态
        txtInputFolder.setEnabled(anyModeSelected);
        btnBrowseInput.setEnabled(anyModeSelected);
        txtOutputPath.setEnabled(anyModeSelected);
        btnBrowseOutput.setEnabled(anyModeSelected);
        chkSeparateFiles.setEnabled(anyModeSelected);
        chkOpenFolderAfterComplete.setEnabled(anyModeSelected);
        importConfigComboBox.setEnabled(true);

        // 2. 更新执行按钮状态
        btnExecute.setEnabled(anyModeSelected);
        btnConfigManage.setEnabled(anyModeSelected);

        // 3. 根据选择的模式显示/隐藏对应的配置面板
        textExtractPanel.setVisible(isTextModeSelected);
        imageExtractPanel.setVisible(isImageModeSelected);

        // 4. 更新输出标签文本 — 始终显示"输出文件夹目录："
        lblOutput.setText("输出文件夹目录：");

        // 5. 更新模式复选框的互斥状态（允许取消选中以切换模式）
        if (isTextModeSelected) {
            chkImageMode.setEnabled(false);
        } else if (isImageModeSelected) {
            chkTextMode.setEnabled(false);
        } else {
            chkTextMode.setEnabled(true);
            chkImageMode.setEnabled(true);
        }

        // 6. 更新子面板内部控件的启用状态
        if (isTextModeSelected) {
            updateUIForMode();
            toggleLimitSettings();
            toggleTemplateSettings();
            toggleTitleSettings();
        } else if (isImageModeSelected) {
            toggleImageNameSettings();
            toggleImageRegionSettings();
        }

        // 7. 重新布局
        SwingUtilities.invokeLater(() -> {
            getContentPane().revalidate();
            getContentPane().repaint();
        });
    }

    /**
     * 更新文本模式下的 UI（主要处理合并/单独模式对章节标题的影响）
     */
    private void updateUIForMode() {
        boolean isSeparate = chkSeparateFiles.isSelected();

        // 当切换"单独输出模式"时，重新设置输出路径
        String inputPath = txtInputFolder.getText().trim();
        if (!inputPath.isEmpty()) {
            autoSetOutputPath(inputPath);
        }

        if (isTextModeSelected) {
            chkAddChapterNumber.setEnabled(true);
            chkEnableTitleExtract.setEnabled(true);
        }

        toggleTitleSettings();
    }

    /**
     * 清空文本模式的配置
     */
    private void clearTextModeConfig() {
        chkSeparateFiles.setSelected(false);
        chkOpenFolderAfterComplete.setSelected(false);

        chkEnableLimit.setSelected(false);
        txtStartMarker.setText("");
        txtEndMarker.setText("");
        chkUseRegex.setSelected(false);

        chkEnableCustomTemplate.setSelected(false);
        txtStartTemplate.setText(MhtBatchProcessor.DEFAULT_START_TEMPLATE);
        txtEndTemplate.setText(MhtBatchProcessor.DEFAULT_END_TEMPLATE);

        chkAddChapterNumber.setSelected(false);
        chkEnableTitleExtract.setSelected(false);
        txtTitleStartMarker.setText("");
        txtTitleEndMarker.setText("");
        chkTitleUseRegex.setSelected(false);
    }

    /**
     * 清空图片模式的配置
     */
    private void clearImageModeConfig() {
        chkSeparateFiles.setSelected(false);
        chkOpenFolderAfterComplete.setSelected(false);

        chkEnableImageNameExtract.setSelected(false);
        txtImageNameStartMarker.setText("");
        txtImageNameEndMarker.setText("");

        chkEnableImageRegionExtract.setSelected(false);
        txtImageRegionStartMarker.setText("");
        txtImageRegionEndMarker.setText("");

        chkEnableImageRename.setSelected(false);
        chkEnableImageZip.setSelected(false);
    }

    private void toggleLimitSettings() {
        boolean enabled = chkEnableLimit.isSelected();
        txtStartMarker.setEnabled(enabled);
        txtEndMarker.setEnabled(enabled);
        chkUseRegex.setEnabled(enabled);

        if (!enabled) {
            txtStartMarker.setText("");
            txtEndMarker.setText("");
            chkUseRegex.setSelected(false);
        }
    }

    private void toggleTemplateSettings() {
        boolean enabled = chkEnableCustomTemplate.isSelected();
        txtStartTemplate.setEnabled(enabled);
        txtEndTemplate.setEnabled(enabled);
        lblTemplateVars.setEnabled(enabled);

        if (!enabled) {
            txtStartTemplate.setText(escapeNewlines(MhtBatchProcessor.DEFAULT_START_TEMPLATE));
            txtEndTemplate.setText(escapeNewlines(MhtBatchProcessor.DEFAULT_END_TEMPLATE));
        }
    }

    private void toggleTitleSettings() {


        boolean enableExtract = chkEnableTitleExtract.isSelected();
        boolean hasChapterNumber = chkAddChapterNumber.isSelected();

        txtTitleStartMarker.setEnabled(enableExtract);
        txtTitleEndMarker.setEnabled(enableExtract);
        chkTitleUseRegex.setEnabled(enableExtract);

        if (!enableExtract) {
            txtTitleStartMarker.setText("");
            txtTitleEndMarker.setText("");
            chkTitleUseRegex.setSelected(false);
        }

        if (hasChapterNumber && enableExtract) {
            chkEnableTitleExtract.setToolTipText("模式：序号 + 标题");
        } else if (!hasChapterNumber && enableExtract) {
            chkEnableTitleExtract.setToolTipText("模式：仅标题");
        } else {
            chkEnableTitleExtract.setToolTipText("已禁用");
        }
    }

    /**
     * 切换图片名称提取设置的启用状态
     */
    private void toggleImageNameSettings() {
        boolean enabled = chkEnableImageNameExtract.isSelected();
        txtImageNameStartMarker.setEnabled(enabled);
        txtImageNameEndMarker.setEnabled(enabled);

        if (!enabled) {
            txtImageNameStartMarker.setText("");
            txtImageNameEndMarker.setText("");
        }
    }

    /**
     * 切换图片区域提取设置的启用状态
     */
    private void toggleImageRegionSettings() {
        boolean enabled = chkEnableImageRegionExtract.isSelected();
        txtImageRegionStartMarker.setEnabled(enabled);
        txtImageRegionEndMarker.setEnabled(enabled);

        if (!enabled) {
            txtImageRegionStartMarker.setText("");
            txtImageRegionEndMarker.setText("");
        }
    }

    // ==================== 浏览文件夹 ====================

    private void browseInputFolder(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("选择 MHT 文件所在文件夹");
        File currentDir = new File(txtInputFolder.getText());
        if (currentDir.exists()) fileChooser.setCurrentDirectory(currentDir);
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            String inputPath = fileChooser.getSelectedFile().getAbsolutePath();
            txtInputFolder.setText(inputPath);
            autoSetOutputPath(inputPath);
        }
    }

    private void browseOutputPath(ActionEvent e) {
        // 输出路径始终选择文件夹目录
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("选择输出文件夹路径");

        String currentPath = txtOutputPath.getText().trim();
        if (!currentPath.isEmpty()) {
            File currentFile = new File(currentPath);
            if (currentFile.exists()) {
                fileChooser.setCurrentDirectory(currentFile);
            } else {
                File parentDir = currentFile.getParentFile();
                if (parentDir != null && parentDir.exists()) {
                    fileChooser.setCurrentDirectory(parentDir);
                }
            }
        }

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            String path = fileChooser.getSelectedFile().getAbsolutePath();
            txtOutputPath.setText(path);
        }
    }

    /**
     * 根据输入路径自动设置输出路径
     * 输出路径始终为文件夹目录（不包含文件名）
     */
    private void autoSetOutputPath(String inputPath) {
        String projectDir = System.getProperty("user.dir");
        String outputBaseDir = projectDir + File.separator + "输出文件";

        File outDirFile = new File(outputBaseDir);
        if (!outDirFile.exists()) {
            outDirFile.mkdirs();
        }

        // 输出路径始终为文件夹目录
        txtOutputPath.setText(outputBaseDir);
    }

    // ==================== 执行处理 ====================

    private void executeProcess(ActionEvent e) {
        if (isImageModeSelected) {
            executeImageProcess();
            return;
        }

        // ===== 纯文本提取模式 =====
        boolean isSeparate = chkSeparateFiles.isSelected();
        boolean enableLimit = chkEnableLimit.isSelected();
        boolean enableCustomTemplate = chkEnableCustomTemplate.isSelected();
        boolean addChapterNumber = chkAddChapterNumber.isSelected();
        boolean enableTitleExtract = chkEnableTitleExtract.isSelected();

        String titleStartMarker = txtTitleStartMarker.getText().trim();
        String titleEndMarker = txtTitleEndMarker.getText().trim();
        boolean titleUseRegex = chkTitleUseRegex.isSelected();

        String inputFolder = txtInputFolder.getText().trim();
        String outputPath = txtOutputPath.getText().trim();

        if (inputFolder.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入 MHT 文件所在文件夹路径！", "输入错误", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File folder = new File(inputFolder);
        if (!folder.exists() || !folder.isDirectory()) {
            JOptionPane.showMessageDialog(this, "文件夹不存在或不是有效目录：\n" + inputFolder, "路径错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (outputPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请选择输出文件夹路径！", "输入错误", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 输出路径是文件夹目录，确保存在
        File outDirFile = new File(outputPath);
        if (!outDirFile.exists()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "输出目录不存在，是否创建？\n" + outDirFile.getAbsolutePath(),
                    "目录不存在", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                if (!outDirFile.mkdirs()) {
                    JOptionPane.showMessageDialog(this, "无法创建目录，请检查权限！", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } else {
                return;
            }
        }

        // 对于文本模式，根据是否单独输出确定实际的输出文件路径
        String actualOutputPath;
        if (isSeparate) {
            // 单独输出模式：输出到文件夹目录
            actualOutputPath = outputPath;
        } else {
            // 合并输出模式：在输出文件夹下生成以输入文件夹命名的 txt 文件
            String folderName = folder.getName();
            if (folderName == null || folderName.isEmpty()) {
                folderName = "mht_output";
            }
            actualOutputPath = outputPath + File.separator + folderName + ".txt";
        }

        String startMarker = null, endMarker = null;
        boolean useRegex = false;
        if (enableLimit) {
            startMarker = txtStartMarker.getText().trim();
            endMarker = txtEndMarker.getText().trim();
            useRegex = chkUseRegex.isSelected();
            if (startMarker.isEmpty() || endMarker.isEmpty()) {
                logger.warn("[警告] 已启用内容限定但标记为空，将忽略内容限定功能。");
                enableLimit = false;
            }
        }

        if (enableTitleExtract && (titleStartMarker.isEmpty() || titleEndMarker.isEmpty())) {
            logger.warn("[警告] 已启用标题提取但标记为空，将忽略标题提取功能。");
            enableTitleExtract = false;
        }

        String startTemplate, endTemplate;
        if (enableCustomTemplate) {
            startTemplate = unescapeNewlines(txtStartTemplate.getText());
            endTemplate = unescapeNewlines(txtEndTemplate.getText());
            if (startTemplate == null || startTemplate.trim().isEmpty())
                startTemplate = MhtBatchProcessor.DEFAULT_START_TEMPLATE;
            if (endTemplate == null || endTemplate.trim().isEmpty())
                endTemplate = MhtBatchProcessor.DEFAULT_END_TEMPLATE;
        } else {
            startTemplate = MhtBatchProcessor.DEFAULT_START_TEMPLATE;
            endTemplate = MhtBatchProcessor.DEFAULT_END_TEMPLATE;
        }

        final boolean finalIsSeparate = isSeparate;
        final boolean finalEnableLimit = enableLimit;
        final String finalStartMarker = startMarker;
        final String finalEndMarker = endMarker;
        final boolean finalUseRegex = useRegex;
        final String finalInputFolder = inputFolder;
        final String finalOutputPath = actualOutputPath;
        final String finalStartTemplate = startTemplate;
        final String finalEndTemplate = endTemplate;
        final boolean finalAddChapterNumber = addChapterNumber;
        final boolean finalEnableTitleExtract = enableTitleExtract;
        final String finalTitleStartMarker = titleStartMarker;
        final String finalTitleEndMarker = titleEndMarker;
        final boolean finalTitleUseRegex = titleUseRegex;

        setControlsEnabled(false);
        consoleArea.append("\n========================================\n");
        consoleArea.append("任务开始执行...\n");
        if (finalAddChapterNumber) consoleArea.append("模式：自动序号\n");
        if (finalEnableTitleExtract)
            consoleArea.append("模式：提取隐藏标题" + (finalAddChapterNumber ? " (与序号组合)" : " (独立使用)") + "\n");
        consoleArea.setCaretPosition(consoleArea.getDocument().getLength());

        SwingWorker<MhtBatchProcessor.ProcessStats, String> worker = new SwingWorker<>() {
            @Override
            protected MhtBatchProcessor.ProcessStats doInBackground() {
                try {
                    if (finalIsSeparate) {
                        return MhtBatchProcessor.processFolderSeparate(
                                finalInputFolder, finalOutputPath, true,
                                finalEnableLimit ? finalStartMarker : null,
                                finalEnableLimit ? finalEndMarker : null,
                                finalUseRegex,
                                finalStartTemplate, finalEndTemplate,
                                finalAddChapterNumber,
                                finalEnableTitleExtract, finalTitleStartMarker,
                                finalTitleEndMarker, finalTitleUseRegex);
                    } else {
                        return MhtBatchProcessor.processFolder(
                                finalInputFolder, finalOutputPath, true,
                                finalEnableLimit ? finalStartMarker : null,
                                finalEnableLimit ? finalEndMarker : null,
                                finalUseRegex,
                                finalStartTemplate, finalEndTemplate,
                                finalAddChapterNumber,
                                finalEnableTitleExtract, finalTitleStartMarker,
                                finalTitleEndMarker, finalTitleUseRegex);
                    }
                } catch (Exception ex) {
                    logger.error("后台处理发生异常", ex);
                    return null;
                }
            }

            @Override
            protected void done() {
                try {
                    MhtBatchProcessor.ProcessStats stats = get();
                    if (stats != null && stats.successCount > 0) {
                        consoleArea.append("\n处理完成！成功：" + stats.successCount + ", 失败：" + stats.failCount + "\n");
                        openOutputFolderIfNeeded();
                    } else if (stats != null) {
                        consoleArea.append("\n未找到任何 MHT 文件或所有内容为空。\n");
                        JOptionPane.showMessageDialog(MhtExtractorGUI.this,
                                "未找到任何 MHT 文件！", "提示", JOptionPane.WARNING_MESSAGE);
                    } else {
                        consoleArea.append("\n处理过程中发生错误。\n");
                        JOptionPane.showMessageDialog(MhtExtractorGUI.this,
                                "处理失败，请查看日志", "错误", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    consoleArea.append("\n发生异常：" + ex.getMessage() + "\n");
                    JOptionPane.showMessageDialog(MhtExtractorGUI.this,
                            "处理异常：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setControlsEnabled(true);
                    consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
                }
            }
        };
        worker.execute();
    }
    /**
     * 将换行符 \n 转义为字面的 \\n 用于显示
     */
    private String escapeNewlines(String text) {
        if (text == null) return null;
        return text.replace("\n", "\\n");
    }

    /**
     * 将字面的 \\n 还原为实际的换行符 \n 用于处理
     */
    private String unescapeNewlines(String text) {
        if (text == null) return null;
        return text.replace("\\n", "\n");
    }
    private void executeImageProcess() {
        boolean isSeparate = chkSeparateFiles.isSelected();
        boolean enableNameExtract = chkEnableImageNameExtract.isSelected();
        boolean enableRegionExtract = chkEnableImageRegionExtract.isSelected();
        boolean enableZip = chkEnableImageZip.isSelected();
        boolean enableRename = chkEnableImageRename.isSelected();

        String inputFolder = txtInputFolder.getText().trim();
        String outputPath = txtOutputPath.getText().trim();

        if (inputFolder.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入 MHT文件所在文件夹路径！", "输入错误", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File folder = new File(inputFolder);
        if (!folder.exists() || !folder.isDirectory()) {
            JOptionPane.showMessageDialog(this, "文件夹不存在或不是有效目录：\n" + inputFolder, "路径错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (outputPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请选择输出文件夹路径！", "输入错误", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 确保输出目录存在
        File outDirFile = new File(outputPath);
        if (!outDirFile.exists()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "输出目录不存在，是否创建？\n" + outDirFile.getAbsolutePath(),
                    "目录不存在", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                if (!outDirFile.mkdirs()) {
                    JOptionPane.showMessageDialog(this, "无法创建目录，请检查权限！", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } else {
                return;
            }
        }

        String nameStartMarker = null, nameEndMarker = null;
        if (enableNameExtract) {
            nameStartMarker = txtImageNameStartMarker.getText().trim();
            nameEndMarker = txtImageNameEndMarker.getText().trim();
            if (nameStartMarker.isEmpty() || nameEndMarker.isEmpty()) {
                logger.warn("[警告] 已启用名称提取但标记为空，将忽略名称提取功能。");
                enableNameExtract = false;
            }
        }

        String regionStartMarker = null, regionEndMarker = null;
        if (enableRegionExtract) {
            regionStartMarker = txtImageRegionStartMarker.getText().trim();
            regionEndMarker = txtImageRegionEndMarker.getText().trim();
            if (regionStartMarker.isEmpty() || regionEndMarker.isEmpty()) {
                logger.warn("[警告] 已启用区域提取但标记为空，将忽略区域提取功能。");
                enableRegionExtract = false;
            }
        }

        final String finalOutputPath = outputPath;
        final String finalInputFolder = inputFolder;
        final String finalNameStartMarker = nameStartMarker;
        final String finalNameEndMarker = nameEndMarker;
        final boolean finalEnableNameExtract = enableNameExtract;
        final String finalRegionStartMarker = regionStartMarker;
        final String finalRegionEndMarker = regionEndMarker;
        final boolean finalEnableRename = enableRename;
        final boolean finalEnableZip = enableZip;
        final boolean finalIsSeparate = isSeparate;
        final String inputFolderName = folder.getName();

        setControlsEnabled(false);
        consoleArea.append("\n========================================\n");
        consoleArea.append("图片提取任务开始执行...\n");
        consoleArea.append("模式：" + (finalIsSeparate ? "单独输出" : "合并输出") + "\n");
        consoleArea.append("名称提取限定：" + (finalEnableNameExtract ? "启用" : "禁用") + "\n");
        consoleArea.append("批量重命名：" + (finalEnableRename ? "启用" : "禁用") + "\n");
        consoleArea.append("ZIP 打包：" + (finalEnableZip ? "启用" : "禁用") + "\n");
        consoleArea.setCaretPosition(consoleArea.getDocument().getLength());

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                try {
                    File[] mhtFiles = new File(finalInputFolder).listFiles((dir, name) ->
                            name.toLowerCase().endsWith(".mht") || name.toLowerCase().endsWith(".mhtml"));

                    if (mhtFiles == null || mhtFiles.length == 0) {
                        publish("未找到任何 MHT文件\n");
                        return null;
                    }

                    // 按文件名排序，保证顺序稳定
                    java.util.Arrays.sort(mhtFiles, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

                    publish("找到 " + mhtFiles.length + " 个 MHT文件\n");

                    List<String> htmlContents = new ArrayList<>();
                    List<String> mhtFileNames = new ArrayList<>();
                    List<String> mhtFilePaths = new ArrayList<>();

                    for (File mhtFile : mhtFiles) {
                        try {
                            String html = extractHtmlFromMht(mhtFile.getAbsolutePath());
                            if (html != null && !html.isEmpty()) {
                                htmlContents.add(html);
                                String fileName = mhtFile.getName();
                                if (fileName.toLowerCase().endsWith(".mht") || fileName.toLowerCase().endsWith(".mhtml")) {
                                    fileName = fileName.substring(0, fileName.lastIndexOf("."));
                                }
                                mhtFileNames.add(fileName);
                                mhtFilePaths.add(mhtFile.getAbsolutePath());
                                publish("已加载：" + mhtFile.getName() + "\n");
                            }
                        } catch (Exception ex) {
                            logger.error("加载 MHT文件{}失败" , mhtFile.getName(), ex);
                            publish("加载失败：" + mhtFile.getName() + "\n");
                        }
                    }

                    if (htmlContents.isEmpty()) {
                        publish("无法从任何 MHT文件中提取 HTML 内容\n");
                        return null;
                    }

                    AppConfig config = new AppConfig();
                    config.setImageMode(true);
                    config.setImageNameExtractEnabled(finalEnableNameExtract);
                    config.setNameLimitStart(finalNameStartMarker);
                    config.setNameLimitEnd(finalNameEndMarker);
                    config.setContentLimitStart(finalRegionStartMarker);
                    config.setContentLimitEnd(finalRegionEndMarker);
                    config.setSingleOutputMode(finalIsSeparate);
                    config.setEnableBatchRenaming(finalEnableRename);
                    config.setEnableZipPackaging(finalEnableZip);
                    config.setInputFolder(finalInputFolder);

                    ImageBatchProcessor processor = new ImageBatchProcessor(
                            htmlContents, config, finalOutputPath, inputFolderName, mhtFileNames);
                    processor.setMhtFilePaths(mhtFilePaths);
                    processor.execute();

                    publish("图片处理完成！\n");
                } catch (Exception ex) {
                    logger.error("图片提取后台处理发生异常", ex);
                    publish("图片提取失败：" + ex.getMessage() + "\n");
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    consoleArea.append(msg);
                }
                consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
            }

            @Override
            protected void done() {
                try {
                    openOutputFolderIfNeeded();
                } catch (Exception ex) {
                    consoleArea.append("\n发生异常：" + ex.getMessage() + "\n");
                    JOptionPane.showMessageDialog(MhtExtractorGUI.this,
                            "处理异常：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setControlsEnabled(true);
                    consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
                }
            }
        };
        worker.execute();
    }

    /**
     * 如果勾选了"提取完成后打开输出文件夹"，则打开输出文件夹目录
     * 不论是纯文本模式还是图片模式，不论是否单独输出，都直接打开"输出文件夹目录"对应的路径
     */
    private void openOutputFolderIfNeeded() {
        if (!chkOpenFolderAfterComplete.isSelected()) return;

        String outputPath = txtOutputPath.getText().trim();
        if (outputPath.isEmpty()) return;

        File target = new File(outputPath);

        if (target.exists() && target.isDirectory()) {
            try {
                Desktop.getDesktop().open(target);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "无法打开文件夹：" + target.getAbsolutePath(),
                        "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        } else {
            logger.warn("输出文件夹不存在，无法打开：{}", outputPath);
        }
    }

    // ==================== MHT 解析 ====================

    /**
     * 从 MHT 文件中提取 HTML 内容
     */
    private String extractHtmlFromMht(String mhtPath) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("mail.mime.decodetext.strict", "false");
        props.setProperty("mail.mime.charset", "UTF-8");

        jakarta.mail.Session session = jakarta.mail.Session.getInstance(props);

        try (java.io.FileInputStream fis = new java.io.FileInputStream(mhtPath)) {
            jakarta.mail.internet.MimeMessage message =
                    new jakarta.mail.internet.MimeMessage(session, fis);
            return getRawHtmlFromMimeMessage(message);
        }
    }

    /**
     * 从 MimeMessage 中提取 HTML 部分
     */
    private String getRawHtmlFromMimeMessage(jakarta.mail.Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            try (java.io.InputStream is = part.getInputStream()) {
                if (is != null) {
                    String encoding = null;
                    if (part instanceof jakarta.mail.internet.MimePart) {
                        encoding = ((jakarta.mail.internet.MimePart) part).getEncoding();
                    }
                    if (encoding == null) encoding = "utf-8";

                    try (java.io.InputStream decodedIs =
                                 jakarta.mail.internet.MimeUtility.decode(is, encoding)) {

                        String charset = "UTF-8";
                        try {
                            String ct = part.getContentType();
                            if (ct != null) {
                                jakarta.mail.internet.ContentType contentType =
                                        new jakarta.mail.internet.ContentType(ct);
                                String cs = contentType.getParameter("charset");
                                if (cs != null) charset = cs;
                            }
                        } catch (Exception e) {
                            // 忽略异常
                        }

                        byte[] bytes = decodedIs.readAllBytes();
                        return new String(bytes, charset);
                    }
                }
            }
        } else if (part.isMimeType("multipart/*")) {
            jakarta.mail.Multipart mp = (jakarta.mail.Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String result = getRawHtmlFromMimeMessage(mp.getBodyPart(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    // ==================== 控件启用/禁用 ====================

    private void setControlsEnabled(boolean enabled) {
        // 基本控件
        txtInputFolder.setEnabled(enabled);
        txtOutputPath.setEnabled(enabled);
        btnBrowseInput.setEnabled(enabled);
        btnBrowseOutput.setEnabled(enabled);
        btnExecute.setEnabled(enabled && (isTextModeSelected || isImageModeSelected));
        btnConfigManage.setEnabled(enabled);
        chkSeparateFiles.setEnabled(enabled);
        chkOpenFolderAfterComplete.setEnabled(enabled);
        importConfigComboBox.setEnabled(enabled);

        // 模式复选框：执行期间禁用
        chkTextMode.setEnabled(enabled && !isImageModeSelected);
        chkImageMode.setEnabled(enabled && !isTextModeSelected);

        if (isTextModeSelected) {
            // 文本模式组件
            chkEnableLimit.setEnabled(enabled);
            chkEnableCustomTemplate.setEnabled(enabled);

            boolean limitEnabled = enabled && chkEnableLimit.isSelected();
            txtStartMarker.setEnabled(limitEnabled);
            txtEndMarker.setEnabled(limitEnabled);
            chkUseRegex.setEnabled(limitEnabled);

            boolean templateEnabled = enabled && chkEnableCustomTemplate.isSelected();
            txtStartTemplate.setEnabled(templateEnabled);
            txtEndTemplate.setEnabled(templateEnabled);
            lblTemplateVars.setEnabled(templateEnabled);

            boolean chapterEnabled = enabled && !chkSeparateFiles.isSelected();
            chkAddChapterNumber.setEnabled(chapterEnabled);
            chkEnableTitleExtract.setEnabled(chapterEnabled);

            boolean titleInputEnabled = enabled && !chkSeparateFiles.isSelected()
                    && chkEnableTitleExtract.isSelected();
            txtTitleStartMarker.setEnabled(titleInputEnabled);
            txtTitleEndMarker.setEnabled(titleInputEnabled);
            chkTitleUseRegex.setEnabled(titleInputEnabled);

        } else if (isImageModeSelected) {
            // 图片模式组件
            chkEnableImageNameExtract.setEnabled(enabled);
            chkEnableImageRegionExtract.setEnabled(enabled);
            chkEnableImageRename.setEnabled(enabled);
            chkEnableImageZip.setEnabled(enabled);

            boolean nameEnabled = enabled && chkEnableImageNameExtract.isSelected();
            txtImageNameStartMarker.setEnabled(nameEnabled);
            txtImageNameEndMarker.setEnabled(nameEnabled);

            boolean regionEnabled = enabled && chkEnableImageRegionExtract.isSelected();
            txtImageRegionStartMarker.setEnabled(regionEnabled);
            txtImageRegionEndMarker.setEnabled(regionEnabled);
        }
    }

    // ==================== 配置管理 ====================

    private void showConfigDialog() {
        AppConfig currentConfig = getCurrentConfig();

        JPanel dialogPanel = new JPanel(new GridLayout(2, 1, 5, 5));

        // 第一行：配置名称输入
        JPanel configNamePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        JLabel configNameLabel = new JLabel("配置名称：");
        JTextField configNameField = new JTextField(20);
        JButton saveButton = new JButton("保存");

        // 第二行：配置选择和删除
        JPanel configSelectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        JLabel configSelectLabel = new JLabel("选择配置：");
        JComboBox<String> configSelectComboBox = new JComboBox<>(
                configManager.getConfigNames().toArray(new String[0]));
        Dimension textFieldSize = configNameField.getPreferredSize();
        Dimension comboBoxSize = configSelectComboBox.getPreferredSize();
        configSelectComboBox.setPreferredSize(new Dimension(textFieldSize.width, comboBoxSize.height));

        JButton deleteButton = new JButton("删除");
        deleteButton.addActionListener(e -> {
            String selected = (String) configSelectComboBox.getSelectedItem();
            if (selected != null && !selected.isEmpty()) {
                int confirm = JOptionPane.showConfirmDialog(MhtExtractorGUI.this,
                        "确定要删除配置 '" + selected + "' 吗？",
                        "确认删除", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    try {
                        configManager.deleteConfig(selected);
                        logger.info("配置已删除，删除配置名称: {}", selected);
                        refreshConfigComboBox();
                        refreshDialogComboBox(configSelectComboBox);
                    } catch (IOException ex) {
                        logger.error("删除配置失败: {}", ex.getMessage());
                    }
                }
            }
        });

        saveButton.addActionListener(e -> {
            String name = configNameField.getText().trim();
            if (!name.isEmpty()) {
                try {
                    configManager.saveConfig(name, currentConfig);
                    logger.info("配置已保存，配置名称: {}", name);
                    refreshConfigComboBox();
                    refreshDialogComboBox(configSelectComboBox);
                } catch (IOException ex) {
                    logger.error("保存配置失败: {}", ex.getMessage());
                }
            } else {
                logger.warn("配置名称不能为空");
            }
        });

        configNamePanel.add(configNameLabel);
        configNamePanel.add(configNameField);
        configNamePanel.add(saveButton);

        configSelectPanel.add(configSelectLabel);
        configSelectPanel.add(configSelectComboBox);
        configSelectPanel.add(deleteButton);

        dialogPanel.add(configNamePanel);
        dialogPanel.add(configSelectPanel);

        JOptionPane.showMessageDialog(MhtExtractorGUI.this, dialogPanel,
                "配置管理", JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * 刷新对话框内的配置下拉框
     */
    private void refreshDialogComboBox(JComboBox<String> comboBox) {
        List<String> newNames = configManager.getConfigNames();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (String name : newNames) {
            model.addElement(name);
        }
        comboBox.setModel(model);
    }

    private AppConfig getCurrentConfig() {
        AppConfig config = new AppConfig();
        config.setInputFolder(txtInputFolder.getText().trim());
        config.setOutputFolder(txtOutputPath.getText().trim());
        config.setSeparateFiles(chkSeparateFiles.isSelected());
        config.setEnableLimit(chkEnableLimit.isSelected());
        config.setStartMarker(txtStartMarker.getText().trim());
        config.setEndMarker(txtEndMarker.getText().trim());
        config.setUseRegex(chkUseRegex.isSelected());
        config.setEnableCustomTemplate(chkEnableCustomTemplate.isSelected());
        config.setStartTemplate(escapeNewlines(txtStartTemplate.getText()));
        config.setEndTemplate(escapeNewlines(txtEndTemplate.getText()));
        config.setAddChapterNumber(chkAddChapterNumber.isSelected());
        config.setEnableTitleExtract(chkEnableTitleExtract.isSelected());
        config.setTitleStartMarker(txtTitleStartMarker.getText().trim());
        config.setTitleEndMarker(txtTitleEndMarker.getText().trim());
        config.setTitleUseRegex(chkTitleUseRegex.isSelected());

        // 保存图片模式相关配置
        config.setImageMode(isImageModeSelected);
        config.setImageNameExtractEnabled(chkEnableImageNameExtract.isSelected());
        config.setImageNameStartMarker(txtImageNameStartMarker.getText().trim());
        config.setImageNameEndMarker(txtImageNameEndMarker.getText().trim());
        config.setImageRegionExtractEnabled(chkEnableImageRegionExtract.isSelected());
        config.setImageRegionStartMarker(txtImageRegionStartMarker.getText().trim());
        config.setImageRegionEndMarker(txtImageRegionEndMarker.getText().trim());
        config.setImageZipEnabled(chkEnableImageZip.isSelected());
        config.setImageRenameEnabled(chkEnableImageRename.isSelected());

        return config;
    }

    private void refreshConfigComboBox() {
        importConfigComboBox.removeAllItems();
        importConfigComboBox.addItem("请选择配置");
        List<String> configNames = configManager.getConfigNames();
        for (String name : configNames) {
            importConfigComboBox.addItem(name);
        }
    }

    private void importConfig() {
        String selectedName = (String) importConfigComboBox.getSelectedItem();
        if (selectedName == null || selectedName.equals("请选择配置")) {
            JOptionPane.showMessageDialog(this, "请先选择一个配置！", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            AppConfig config = configManager.loadConfig(selectedName);
            setConfigFromAppConfig(config);
            logger.info("配置 {} 已导入", selectedName);
        } catch (IOException ex) {
            logger.error("导入配置 {} 失败: {}", selectedName, ex.getMessage());
        }
    }

    private void setConfigFromAppConfig(AppConfig config) {
        // 先设置模式（这会影响后续面板的可见性）
        boolean imageMode = config.isImageMode();
        chkTextMode.setSelected(!imageMode);
        chkImageMode.setSelected(imageMode);
        isTextModeSelected = !imageMode;
        isImageModeSelected = imageMode;

        // 设置基础字段
        txtInputFolder.setText(config.getInputFolder());
        txtOutputPath.setText(config.getOutputFolder());
        chkSeparateFiles.setSelected(config.isSeparateFiles());
        chkOpenFolderAfterComplete.setSelected(false); // 导入配置时默认不打开

        // 文本模式字段
        chkEnableLimit.setSelected(config.isEnableLimit());
        txtStartMarker.setText(config.getStartMarker());
        txtEndMarker.setText(config.getEndMarker());
        chkUseRegex.setSelected(config.isUseRegex());
        chkEnableCustomTemplate.setSelected(config.isEnableCustomTemplate());
        txtStartTemplate.setText(escapeNewlines(config.getStartTemplate()));
        txtEndTemplate.setText(escapeNewlines(config.getEndTemplate()));
        chkAddChapterNumber.setSelected(config.isAddChapterNumber());
        chkEnableTitleExtract.setSelected(config.isEnableTitleExtract());
        txtTitleStartMarker.setText(config.getTitleStartMarker());
        txtTitleEndMarker.setText(config.getTitleEndMarker());
        chkTitleUseRegex.setSelected(config.isTitleUseRegex());

        // 图片模式字段
        chkEnableImageNameExtract.setSelected(config.isImageNameExtractEnabled());
        txtImageNameStartMarker.setText(config.getImageNameStartMarker());
        txtImageNameEndMarker.setText(config.getImageNameEndMarker());
        chkEnableImageRegionExtract.setSelected(config.isImageRegionExtractEnabled());
        txtImageRegionStartMarker.setText(config.getImageRegionStartMarker());
        txtImageRegionEndMarker.setText(config.getImageRegionEndMarker());
        chkEnableImageZip.setSelected(config.isImageZipEnabled());
        chkEnableImageRename.setSelected(config.isImageRenameEnabled());

        // 更新所有 UI 状态
        updateAllUIStates();
    }

    // ==================== 主入口 ====================

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("正在关闭 HTTP 连接池和线程池...");
            ImageBatchProcessor.shutdownResources();
            logger.info("资源已关闭");
        }));

        try {
            // 替换原有的系统默认主题，启用 FlatLaf 浅色主题
            UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatLightLaf());
            //增强高分屏支持，让界面在 4K 屏幕上更清晰
            UIManager.put("TitlePane.useWindowDecorations", true);
        } catch (Exception e) {
            // 如果 FlatLaf 初始化失败（例如依赖缺失），回退到系统默认主题，防止程序崩溃
            logger.warn("FlatLaf 初始化失败，回退到系统默认主题", e);
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex) {
                logger.error("设置系统默认主题也失败", ex);
            }
        }

        System.out.println("正在启动 MHT 资源提取器...");

        SwingUtilities.invokeLater(() -> {
            try {
                MhtExtractorGUI gui = new MhtExtractorGUI();
                gui.setVisible(true);
            } catch (Exception e) {
                logger.error("启动失败", e);
                JOptionPane.showMessageDialog(null,
                        "启动失败:\n" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
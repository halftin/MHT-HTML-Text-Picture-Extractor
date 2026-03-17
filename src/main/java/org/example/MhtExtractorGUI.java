package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;
import java.io.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MhtExtractorGUI extends JFrame {


    //  主界面组件
    private JTextField txtInputFolder;
    private JTextField txtOutputPath;
    private JButton btnBrowseInput;
    private JButton btnBrowseOutput;
    private JButton btnExecute;
    private JButton btnExit;
    private JCheckBox chkSeparateFiles;
    private JLabel lblOutput;

    // 选项卡组件
    private JTabbedPane tabbedPane;
    private JPanel panelBasic;
    private JPanel panelAdvanced;

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


    private ConfigManager configManager;
    private JComboBox<String> importConfigComboBox;


    public MhtExtractorGUI() throws UnsupportedEncodingException {
        configManager = new ConfigManager();
        initUI();
    }

    private void initUI() throws UnsupportedEncodingException {
        setTitle("MHT 资源提取器 ");
        setSize(750, 850);
        setLocationRelativeTo(null);
        setResizable(true);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel mainContainer = new JPanel(new BorderLayout(10, 10));
        mainContainer.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setFont(new Font("微软雅黑", Font.PLAIN, 13));

        panelBasic = createBasicPanel();
        tabbedPane.addTab("提取操作", panelBasic);

        panelAdvanced = createAdvancedPanel();
        JScrollPane advancedScrollPane = new JScrollPane(panelAdvanced);
        advancedScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        advancedScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        tabbedPane.addTab("文本提取配置", advancedScrollPane);

        mainContainer.add(tabbedPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));

        mainContainer.add(bottomPanel, BorderLayout.SOUTH);
        add(mainContainer);

        updateUIForMode();
        toggleLimitSettings();
        toggleTemplateSettings();
        toggleTitleSettings();
        refreshConfigComboBox();
        SwingUtilities.invokeLater(() -> {
            consoleArea.append("欢迎使用 MHT 资源提取器！\n");
            consoleArea.append("请选择 MHT 文件所在文件夹，然后点击“开始执行”。\n");
        });



    }

    private JPanel createBasicPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JLabel lblInput = new JLabel("输入文件夹：");
        lblInput.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        inputPanel.add(lblInput, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtInputFolder = new JTextField("", 30);
        txtInputFolder.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        inputPanel.add(txtInputFolder, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        btnBrowseInput = new JButton("浏览...");
        btnBrowseInput.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        btnBrowseInput.setPreferredSize(new Dimension(80, 28));
        btnBrowseInput.addActionListener(this::browseInputFolder);
        inputPanel.add(btnBrowseInput, gbc);

        gbc.gridy = 1; gbc.gridx = 0; gbc.gridwidth = 3; gbc.weightx = 1.0;
        chkSeparateFiles = new JCheckBox("单独输出模式(将每个 MHT 文件提取到单独的文本文件中)");
        chkSeparateFiles.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        chkSeparateFiles.addActionListener(e -> updateUIForMode());
        inputPanel.add(chkSeparateFiles, gbc);

        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 1; gbc.weightx = 0;
        lblOutput = new JLabel("输出文件目录：");
        lblOutput.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        inputPanel.add(lblOutput, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtOutputPath = new JTextField("", 30);
        txtOutputPath.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        inputPanel.add(txtOutputPath, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        btnBrowseOutput = new JButton("浏览...");
        btnBrowseOutput.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        btnBrowseOutput.setPreferredSize(new Dimension(80, 28));
        btnBrowseOutput.addActionListener(this::browseOutputPath);
        inputPanel.add(btnBrowseOutput, gbc);

        gbc.gridy = 3;
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        JLabel lblImportConfig = new JLabel("导入配置：");
        lblImportConfig.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        inputPanel.add(lblImportConfig, gbc);
        gbc.gridx = 1;
        gbc.gridwidth = 1;
        importConfigComboBox = new JComboBox<>();
        importConfigComboBox.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        inputPanel.add(importConfigComboBox, gbc);
        gbc.gridx = 2;
        gbc.gridwidth = 1;
        JButton btnImportConfig = new JButton("导入");
        btnImportConfig.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        btnImportConfig.setPreferredSize(new Dimension(80, 28));
        btnImportConfig.addActionListener(e -> importConfig());
        inputPanel.add(btnImportConfig, gbc);

        panel.add(inputPanel, BorderLayout.CENTER);

        JPanel consolePanel = new JPanel(new BorderLayout());
        consolePanel.setBorder(BorderFactory.createTitledBorder("运行日志"));

        consoleArea = new JTextArea(15, 40);
        consoleArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        consoleArea.setEditable(false);
        consoleArea.setBackground(new Color(245, 245, 245));
        consoleArea.setLineWrap(true);
        consoleArea.setWrapStyleWord(true);

        TextAreaAppender.setTextArea(consoleArea);

        consoleScrollPane = new JScrollPane(consoleArea);
        consoleScrollPane.setPreferredSize(new Dimension(350, 300));
        consolePanel.add(consoleScrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        btnExecute = new JButton("开始执行");
        btnExecute.setFont(new Font("微软雅黑", Font.BOLD, 15));
        btnExecute.setPreferredSize(new Dimension(160, 45));
        btnExecute.setBackground(new Color(0, 153, 76));
        btnExecute.setForeground(Color.WHITE);
        btnExecute.setOpaque(true);
        btnExecute.setBorderPainted(false);
        btnExecute.addActionListener(this::executeProcess);
        buttonPanel.add(btnExecute);

        btnExit = new JButton("保存/删除配置");
        btnExit.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        btnExit.setPreferredSize(new Dimension(160, 45));
        btnExit.addActionListener(e -> showConfigDialog());
        buttonPanel.add(btnExit);


        JPanel southContainer = new JPanel(new BorderLayout());
        southContainer.add(consolePanel, BorderLayout.CENTER);
        southContainer.add(buttonPanel, BorderLayout.SOUTH);

        panel.add(inputPanel, BorderLayout.CENTER);
        panel.add(southContainer, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createAdvancedPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 10, 8, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // --- 内容限定区域 ---
        gbc.gridx = 0; gbc.gridy = 7; gbc.gridwidth = 2;
        JLabel lblLimitTitle = new JLabel("正文内容提取限定");
        lblLimitTitle.setFont(new Font("微软雅黑", Font.BOLD, 13));
        lblLimitTitle.setForeground(new Color(0, 102, 204));
        panel.add(lblLimitTitle, gbc);

        gbc.gridy = 8;
        chkEnableLimit = new JCheckBox("启用内容限定（只提取指定区域的内容）");
        chkEnableLimit.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkEnableLimit.addActionListener(e -> toggleLimitSettings());
        panel.add(chkEnableLimit, gbc);

        gbc.gridy = 9; gbc.gridwidth = 1; gbc.weightx = 0;
        JLabel lblStartMarker = new JLabel("起始标记：");
        lblStartMarker.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblStartMarker, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtStartMarker = new JTextField("", 40);
        txtStartMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtStartMarker, gbc);

        gbc.gridx = 0; gbc.gridy = 10; gbc.weightx = 0;
        JLabel lblEndMarker = new JLabel("结束标记：");
        lblEndMarker.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblEndMarker, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtEndMarker = new JTextField("", 40);
        txtEndMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtEndMarker, gbc);

        gbc.gridx = 0; gbc.gridy = 11; gbc.gridwidth = 2; gbc.weightx = 1.0;
        JPanel limitBottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        limitBottomPanel.setOpaque(false);

        chkUseRegex = new JCheckBox("使用正则表达式匹配");
        chkUseRegex.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        limitBottomPanel.add(chkUseRegex);
        limitBottomPanel.add(Box.createHorizontalStrut(20));

        panel.add(limitBottomPanel, gbc);

        // --- 分隔线 1 ---
        gbc.gridy = 12; gbc.gridwidth = 2;
        JSeparator separator1 = new JSeparator();
        panel.add(separator1, gbc);

        // --- 自定义模板区域 ---
        gbc.gridy = 0;
        JLabel lblTemplateTitle = new JLabel("自定义文件开头/结尾模板");
        lblTemplateTitle.setFont(new Font("微软雅黑", Font.BOLD, 13));
        lblTemplateTitle.setForeground(new Color(0, 102, 204));
        panel.add(lblTemplateTitle, gbc);

        gbc.gridy = 1;
        chkAddChapterNumber = new JCheckBox("为每个 MHT 文件添加章节名（仅合并模式有效）");
        chkAddChapterNumber.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkAddChapterNumber.setToolTipText("选中后生成“第一章”、“第二章”等序号");
        chkAddChapterNumber.addActionListener(e -> toggleTitleSettings());
        panel.add(chkAddChapterNumber, gbc);

        gbc.gridy = 2;
        chkEnableTitleExtract = new JCheckBox("启用隐藏章节标题提取（从源码固定位置提取标题）");
        chkEnableTitleExtract.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkEnableTitleExtract.setToolTipText("可独立使用！若提取到标题，将直接作为章节头；若同时勾选上方选项，则格式为“序号 标题”");
        chkEnableTitleExtract.addActionListener(e -> toggleTitleSettings());
        panel.add(chkEnableTitleExtract, gbc);

        // ---  标题提取标记输入区  ---
        gbc.gridy = 3; gbc.gridwidth = 1; gbc.weightx = 0;
        JLabel lblTitleStart = new JLabel("标题起始标记：");
        lblTitleStart.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblTitleStart, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtTitleStartMarker = new JTextField(" ", 40);
        txtTitleStartMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtTitleStartMarker, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0;
        JLabel lblTitleEnd = new JLabel("标题结束标记："); // 明确定义新变量
        lblTitleEnd.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblTitleEnd, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtTitleEndMarker = new JTextField(" ", 40);
        txtTitleEndMarker.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        panel.add(txtTitleEndMarker, gbc);

        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2; gbc.weightx = 1.0;
        JPanel titleBottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        titleBottomPanel.setOpaque(false);
        chkTitleUseRegex = new JCheckBox("使用正则表达式匹配标题");
        chkTitleUseRegex.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        titleBottomPanel.add(chkTitleUseRegex);
        panel.add(titleBottomPanel, gbc);

        // --- 分隔线 2 ---
        gbc.gridy = 6; gbc.gridwidth = 2;
        JSeparator separator2 = new JSeparator();
        panel.add(separator2, gbc);

        // --- 真正的自定义模板输入区 ---
        gbc.gridy = 13;
        JLabel lblRealTemplateTitle = new JLabel("自定义文件开头/结尾模板内容");
        lblRealTemplateTitle.setFont(new Font("微软雅黑", Font.BOLD, 13));
        lblRealTemplateTitle.setForeground(new Color(0, 102, 204));
        panel.add(lblRealTemplateTitle, gbc);

        gbc.gridy = 14;
        chkEnableCustomTemplate = new JCheckBox("启用自定义模板（覆盖默认开头结尾格式）");
        chkEnableCustomTemplate.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        chkEnableCustomTemplate.addActionListener(e -> toggleTemplateSettings());
        panel.add(chkEnableCustomTemplate, gbc);

        gbc.gridy = 15; gbc.gridwidth = 1; gbc.weightx = 0;
        JLabel lblStartTemplate = new JLabel("开头模板：");
        lblStartTemplate.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblStartTemplate, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtStartTemplate = new JTextArea(1, 40);
        txtStartTemplate.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        txtStartTemplate.setLineWrap(true);
        txtStartTemplate.setWrapStyleWord(true);
        txtStartTemplate.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200), 1));
        txtStartTemplate.setText(MhtBatchProcessor.DEFAULT_START_TEMPLATE);
        JScrollPane startTemplateScroll = new JScrollPane(txtStartTemplate);
        panel.add(startTemplateScroll, gbc);

        gbc.gridx = 0; gbc.gridy = 16; gbc.weightx = 0;
        JLabel lblEndTemplate = new JLabel("结尾模板：");
        lblEndTemplate.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        panel.add(lblEndTemplate, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtEndTemplate = new JTextArea(1, 40);
        txtEndTemplate.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        txtEndTemplate.setLineWrap(true);
        txtEndTemplate.setWrapStyleWord(true);
        txtEndTemplate.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200), 1));
        txtEndTemplate.setText(MhtBatchProcessor.DEFAULT_END_TEMPLATE);
        JScrollPane endTemplateScroll = new JScrollPane(txtEndTemplate);
        panel.add(endTemplateScroll, gbc);

        gbc.gridx = 0; gbc.gridy = 17; gbc.gridwidth = 2; gbc.weightx = 1.0;
        lblTemplateVars = new JLabel("可用变量：{filename} | {datetime} | {date} | {time} | \\n");
        lblTemplateVars.setFont(new Font("微软雅黑", Font.ITALIC, 11));
        lblTemplateVars.setForeground(new Color(100, 100, 200));
        panel.add(lblTemplateVars, gbc);

        return panel;
    }



    private void updateUIForMode() {
        boolean isSeparate = chkSeparateFiles.isSelected();
        if (isSeparate) {
            chkAddChapterNumber.setEnabled(false);
            chkAddChapterNumber.setSelected(false);
            chkEnableTitleExtract.setEnabled(false);
            chkEnableTitleExtract.setSelected(false);
        } else {
            chkAddChapterNumber.setEnabled(true);
            chkEnableTitleExtract.setEnabled(true);
        }
        toggleTitleSettings();
        txtOutputPath.setEnabled(true);
        btnBrowseOutput.setEnabled(true);
    }

    private void toggleLimitSettings() {
        boolean enabled = chkEnableLimit.isSelected();
        txtStartMarker.setEnabled(enabled);
        txtEndMarker.setEnabled(enabled);
        chkUseRegex.setEnabled(enabled);
    }

    private void toggleTemplateSettings() {
        boolean enabled = chkEnableCustomTemplate.isSelected();
        txtStartTemplate.setEnabled(enabled);
        txtEndTemplate.setEnabled(enabled);
        lblTemplateVars.setEnabled(enabled);
    }

    private void toggleTitleSettings() {
        boolean isSeparate = chkSeparateFiles.isSelected();
        if (isSeparate) return;

        boolean hasChapterNumber = chkAddChapterNumber.isSelected();
        boolean enableExtract = chkEnableTitleExtract.isSelected();

        chkEnableTitleExtract.setEnabled(true);

        boolean inputsEnabled = enableExtract;
        txtTitleStartMarker.setEnabled(inputsEnabled);
        txtTitleEndMarker.setEnabled(inputsEnabled);
        chkTitleUseRegex.setEnabled(inputsEnabled);

        if (hasChapterNumber && enableExtract) {
            chkEnableTitleExtract.setToolTipText("模式：序号 + 标题 ");
        } else if (!hasChapterNumber && enableExtract) {
            chkEnableTitleExtract.setToolTipText("模式：仅标题 ");
        } else {
            chkEnableTitleExtract.setToolTipText("已禁用");
        }
    }

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
        boolean isSeparate = chkSeparateFiles.isSelected();
        JFileChooser fileChooser = new JFileChooser();
        if (isSeparate) {
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setDialogTitle("选择输出文件夹路径");
        } else {
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setDialogTitle("选择输出 TXT 文件路径");
            String currentPath = txtOutputPath.getText().trim();
            if (!currentPath.isEmpty()) fileChooser.setSelectedFile(new File(currentPath));
            else fileChooser.setSelectedFile(new File("mht_text.txt"));
        }
        String currentPath = txtOutputPath.getText().trim();
        if (!currentPath.isEmpty()) {
            File currentFile = new File(currentPath);
            File currentDir = currentFile.getParentFile();
            if (currentDir != null && currentDir.exists()) fileChooser.setCurrentDirectory(currentDir);
        }
        int result = isSeparate ? fileChooser.showOpenDialog(this) : fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            String path = fileChooser.getSelectedFile().getAbsolutePath();
            if (!isSeparate && !path.toLowerCase().endsWith(".txt")) path = path + ".txt";
            txtOutputPath.setText(path);
        }
    }

    private void autoSetOutputPath(String inputPath) {
        File folder = new File(inputPath);
        String folderName = folder.getName();
        if (folderName == null || folderName.isEmpty()) {
            folderName = "mht_output";
        }

        // 项目根目录（当前工作目录）
        String projectDir = System.getProperty("user.dir");
        // 目标输出文件夹：项目根目录/输出文件
        String outputBaseDir = projectDir + File.separator + "输出文件";

        // 确保该文件夹存在
        File outDirFile = new File(outputBaseDir);
        if (!outDirFile.exists()) {
            outDirFile.mkdirs(); // 创建多级目录
        }

        // 根据模式决定输出路径
        boolean isSeparate = chkSeparateFiles.isSelected();
        if (isSeparate) {
            // 单独输出模式：路径就是文件夹
            txtOutputPath.setText(outputBaseDir);
        } else {
            // 合并模式：路径为文件夹内的文件，文件名取输入文件夹名 + ".txt"
            String outputFilePath = outputBaseDir + File.separator + folderName + ".txt";
            txtOutputPath.setText(outputFilePath);
        }
    }

    private void executeProcess(ActionEvent e) {
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
            JOptionPane.showMessageDialog(this, isSeparate ? "请选择输出文件夹路径！" : "请输入输出 TXT 文件路径！", "输入错误", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File outPathFile = new File(outputPath);
        if (isSeparate) {
            if (!outPathFile.exists() || !outPathFile.isDirectory()) {
                JOptionPane.showMessageDialog(this, "输出文件夹不存在：\n" + outputPath, "路径错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } else {
            File outputDir = outPathFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                int confirm = JOptionPane.showConfirmDialog(this, "输出文件所在目录不存在，是否创建？\n" + outputDir.getAbsolutePath(), "目录不存在", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    if (!outputDir.mkdirs()) {
                        JOptionPane.showMessageDialog(this, "无法创建目录，请检查权限！", "错误", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                } else { return; }
            }
        }

        String startMarker = null, endMarker = null;
        boolean useRegex = false;
        if (enableLimit) {
            startMarker = txtStartMarker.getText().trim();
            endMarker = txtEndMarker.getText().trim();
            useRegex = chkUseRegex.isSelected();
            if (startMarker.isEmpty() || endMarker.isEmpty()) {
                logger.info("[警告] 已启用内容限定但标记为空，将忽略内容限定功能。");
                enableLimit = false;
            }
        }

        if (enableTitleExtract && (titleStartMarker.isEmpty() || titleEndMarker.isEmpty())) {
            logger.info("[警告] 已启用标题提取但标记为空，将忽略标题提取功能。");
            enableTitleExtract = false;
        }

        String startTemplate = null, endTemplate = null;
        if (enableCustomTemplate) {
            startTemplate = txtStartTemplate.getText();
            endTemplate = txtEndTemplate.getText();
            if (startTemplate == null || startTemplate.trim().isEmpty()) startTemplate = MhtBatchProcessor.DEFAULT_START_TEMPLATE;
            if (endTemplate == null || endTemplate.trim().isEmpty()) endTemplate = MhtBatchProcessor.DEFAULT_END_TEMPLATE;
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
        final String finalOutputPath = outputPath;
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
        if (finalEnableTitleExtract) consoleArea.append("模式：提取隐藏标题" + (finalAddChapterNumber ? " (与序号组合)" : " (独立使用)") + "\n");
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
                                finalStartTemplate, finalEndTemplate);
                    } else {
                        return MhtBatchProcessor.processFolder(
                                finalInputFolder, finalOutputPath, true,
                                finalEnableLimit ? finalStartMarker : null,
                                finalEnableLimit ? finalEndMarker : null,
                                finalUseRegex,
                                finalStartTemplate, finalEndTemplate,
                                finalAddChapterNumber,
                                finalEnableTitleExtract, finalTitleStartMarker, finalTitleEndMarker, finalTitleUseRegex);
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
                        if (!finalIsSeparate) {
                            int openConfirm = JOptionPane.showConfirmDialog(MhtExtractorGUI.this, "是否立即打开输出文件？", "提示", JOptionPane.YES_NO_OPTION);
                            if (openConfirm == JOptionPane.YES_OPTION) {
                                try { Desktop.getDesktop().open(new File(finalOutputPath)); }
                                catch (Exception ex) { JOptionPane.showMessageDialog(MhtExtractorGUI.this, "无法打开文件", "提示", JOptionPane.INFORMATION_MESSAGE); }
                            }
                        }
                    } else if (stats != null) {
                        consoleArea.append("\n未找到任何 MHT 文件或所有内容为空。\n");
                        JOptionPane.showMessageDialog(MhtExtractorGUI.this, "未找到任何 MHT 文件！", "提示", JOptionPane.WARNING_MESSAGE);
                    } else {
                        consoleArea.append("\n处理过程中发生错误。\n");
                        JOptionPane.showMessageDialog(MhtExtractorGUI.this, "处理失败，请查看日志", "错误", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    consoleArea.append("\n发生异常：" + ex.getMessage() + "\n");
                    JOptionPane.showMessageDialog(MhtExtractorGUI.this, "处理异常：" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setControlsEnabled(true);
                    consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
                }
            }
        };
        worker.execute();
    }

    private void setControlsEnabled(boolean enabled) {
        txtInputFolder.setEnabled(enabled);
        txtOutputPath.setEnabled(enabled);
        btnBrowseInput.setEnabled(enabled);
        btnBrowseOutput.setEnabled(enabled);
        btnExecute.setEnabled(enabled);
        btnExit.setEnabled(enabled);
        chkSeparateFiles.setEnabled(enabled);
        tabbedPane.setEnabled(enabled);

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

        toggleTitleSettings();
    }
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
        // 声明并初始化下拉框
        JComboBox<String> configSelectComboBox = new JComboBox<>(configManager.getConfigNames().toArray(new String[0]));
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
                        List<String> newNames = configManager.getConfigNames();
                        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
                        for (String name : newNames) {
                            model.addElement(name);
                        }
                        configSelectComboBox.setModel(model);
                    } catch (IOException ex) {
                        logger.error("删除配置失败: {}", ex.getMessage());
                    }
                }
            }
        });

        // 保存按钮监听器
        saveButton.addActionListener(e -> {
            String name = configNameField.getText().trim();
            if (!name.isEmpty()) {
                try {
                    configManager.saveConfig(name, currentConfig);
                    logger.info("配置已保存，配置名称: {}", name);
                    refreshConfigComboBox();
                    List<String> newNames = configManager.getConfigNames();
                    DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
                    for (String n : newNames) {
                        model.addElement(n);
                    }
                    configSelectComboBox.setModel(model);
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
        config.setStartTemplate(txtStartTemplate.getText());
        config.setEndTemplate(txtEndTemplate.getText());
        config.setAddChapterNumber(chkAddChapterNumber.isSelected());
        config.setEnableTitleExtract(chkEnableTitleExtract.isSelected());
        config.setTitleStartMarker(txtTitleStartMarker.getText().trim());
        config.setTitleEndMarker(txtTitleEndMarker.getText().trim());
        config.setTitleUseRegex(chkTitleUseRegex.isSelected());
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
        txtInputFolder.setText(config.getInputFolder());
        txtOutputPath.setText(config.getOutputFolder());
        chkSeparateFiles.setSelected(config.isSeparateFiles());
        chkEnableLimit.setSelected(config.isEnableLimit());
        txtStartMarker.setText(config.getStartMarker());
        txtEndMarker.setText(config.getEndMarker());
        chkUseRegex.setSelected(config.isUseRegex());
        chkEnableCustomTemplate.setSelected(config.isEnableCustomTemplate());
        txtStartTemplate.setText(config.getStartTemplate());
        txtEndTemplate.setText(config.getEndTemplate());
        chkAddChapterNumber.setSelected(config.isAddChapterNumber());
        chkEnableTitleExtract.setSelected(config.isEnableTitleExtract());
        txtTitleStartMarker.setText(config.getTitleStartMarker());
        txtTitleEndMarker.setText(config.getTitleEndMarker());
        chkTitleUseRegex.setSelected(config.isTitleUseRegex());

        // 更新UI状态
        updateUIForMode();
        toggleLimitSettings();
        toggleTemplateSettings();
        toggleTitleSettings();
    }
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.error("操作失败", e);
        }
        System.out.println("正在启动 MHT 资源提取器...");

        SwingUtilities.invokeLater(() -> {
            try {
                MhtExtractorGUI gui = new MhtExtractorGUI();
                gui.setVisible(true);
            } catch (Exception e) {
                logger.error("操作失败", e);
                JOptionPane.showMessageDialog(null, "启动失败:\n" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

}
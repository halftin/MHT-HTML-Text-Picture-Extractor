package com.mhtextractor.i18n;

/**
 * 用户可见失败场景的错误码（架构设计 §五 错误契约）。
 *
 * <p>bundle 键约定 {@code error.<CODE>}（如 {@code error.ARCHIVE_FAILED}），由 {@link #key()} 生成。
 * UI 侧统一 {@code showError(ErrorCode, Throwable)}：文案取 {@link #message(Object...)}，
 * 技术细节（e.getMessage()）只进日志，不进用户文案——避免"英文界面 + 中文异常"混合体。</p>
 *
 * <p>按<b>语义</b>归纳，不按文案条数：同一场景多处调用共用一个常量
 * （如 KEEP_AT_LEAST_ONE 覆盖 MainController 三处 showAlert）。</p>
 *
 * <p>会话 14 在 MainController / ParamDialogController 落地替换时使用本清单。</p>
 */
public enum ErrorCode {

    /** MainController:947 导入扫描失败 */
    IMPORT_FAILED,
    /** MainController:1221 参数设置窗口打开失败 */
    PARAM_DIALOG_OPEN_FAILED,
    /** MainController:1488 文件所在目录不存在（WARNING） */
    FILE_DIR_MISSING,
    /** MainController:1492 打开所在文件夹失败 */
    OPEN_FOLDER_FAILED,
    /** MainController:1515 文件不存在，可能已被移动或删除（WARNING） */
    FILE_MISSING,
    /** MainController:1543 未能启动浏览器，页面已生成（WARNING） */
    BROWSER_LAUNCH_FAILED,
    /** MainController:1550 生成浏览器查看页失败 */
    BROWSER_VIEW_FAILED,
    /** MainController:1916 请提供有效的输入目录（WARNING） */
    INPUT_DIR_INVALID,
    /** MainController:1920 请提供有效的输出目录（WARNING） */
    OUTPUT_DIR_INVALID,
    /** MainController:1924 输入目录不存在 */
    INPUT_DIR_MISSING,
    /** MainController:1929 未找到任何 MHT 文件（WARNING） */
    NO_MHT_FOUND,
    /** MainController:1929 / 3335 / 3357 至少保留一个文件 */
    KEEP_AT_LEAST_ONE,
    /** MainController:2138 后台任务处理异常 */
    PROCESS_FAILED,
    /** MainController:4604 计算缓存键/读取文件信息失败 */
    READ_FILE_INFO_FAILED,
    /** ParamDialogController:333 保存配置失败 */
    CONFIG_SAVE_FAILED,
    /** ParamDialogController:342 请选择要加载的配置（WARNING） */
    CONFIG_SELECT_TO_LOAD,
    /** ParamDialogController:362 加载配置失败 */
    CONFIG_LOAD_FAILED,
    /** ParamDialogController:370 请选择要删除的配置（WARNING） */
    CONFIG_SELECT_TO_DELETE,
    /** ParamDialogController:373 确定删除配置确认框（占位符 {name} = 配置名） */
    CONFIG_DELETE_CONFIRM,
    /** ParamDialogController:382 删除配置失败 */
    CONFIG_DELETE_FAILED,
    /** ImageBatchProcessor:450 高速内存模式下预加载的 MHT 文件无法提供（占位符 {reason} = 原始失败原因） */
    LOAD_MHT_FAILED;

    /** bundle 键：{@code error.<CODE>}。 */
    public String key() {
        return "error." + name();
    }

    /** 取该错误码的用户文案（经 {@link I18n#get}，可传占位符键值对）。 */
    public String message(Object... args) {
        return I18n.get(key(), args);
    }
}

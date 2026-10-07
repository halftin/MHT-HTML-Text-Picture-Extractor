package com.mhtextractor.backend;

import com.mhtextractor.i18n.I18n;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * {@code --serve} 常驻模式（P4-1 骨架）。
 *
 * <p><b>本批不做 IPC</b>（任务边界）：就绪后阻塞在 stdin 上，读到 EOF 即干净退出（退出码 0）。
 * 后续批次接入端口命令通道后，stdin/stdout 将由 IPC 协议取代 —— 那时本类只保留
 * "常驻 + 生命周期收口"的职责，协议层另建。</p>
 */
@Slf4j
public final class ServeRunner {

    public int run(CliArgs args) {
        I18n.setLocale(args.locale());
        log.info("serve mode entered (P4-1 skeleton: no IPC yet)");
        System.out.println("backend ready (no IPC in this batch; EOF on stdin to exit)");
        try {
            while (System.in.read() != -1) {
                // 骨架期不解析任何协议：仅在 stdin EOF 时退出
            }
            return BackendCli.EXIT_OK;
        } catch (IOException e) {
            System.err.println("error: stdin read failed: " + e.getMessage());
            return BackendCli.EXIT_FAILURE;
        }
    }
}

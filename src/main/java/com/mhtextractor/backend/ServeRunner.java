package com.mhtextractor.backend;

import com.mhtextractor.i18n.I18n;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * {@code --serve}：装配空工作区与同步端口，stdio 协议见 {@code IPC.md}。
 * stdin EOF 正常退出；协议坏帧由 IpcServer 隔离，传输 IO 错误退出码 1。
 */
@Slf4j
public final class ServeRunner {

    public int run(CliArgs args) {
        I18n.setLocale(args.locale());
        log.info("serve mode entered (JSON Lines v1)");
        try (var session = new IpcSession(new ObjectMapper())) {
            new IpcServer(session).run(System.in, System.out);
            return BackendCli.EXIT_OK;
        } catch (IOException e) {
            log.error("IPC transport failed", e);
            return BackendCli.EXIT_FAILURE;
        }
    }
}

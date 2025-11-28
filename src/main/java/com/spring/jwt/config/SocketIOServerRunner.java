package com.spring.jwt.config;

import com.corundumstudio.socketio.SocketIOServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SocketIOServerRunner implements CommandLineRunner {

    private final SocketIOServer socketIOServer;

    @Override
    public void run(String... args) throws Exception {
        socketIOServer.start();
        log.info("✅ [SERVER] Socket.IO server started on port: {}", socketIOServer.getConfiguration().getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                socketIOServer.stop();
                log.info("🛑 [SERVER] Socket.IO server stopped");
            } catch (Exception e) {
                log.error("❌ [SERVER] Error stopping Socket.IO server", e);
            }
        }));
    }
}

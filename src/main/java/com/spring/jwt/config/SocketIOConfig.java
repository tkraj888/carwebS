package com.spring.jwt.config;

import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SocketIOConfig {

    @Value("${socketio.host:0.0.0.0}")
    private String host;

    @Value("${socketio.port:3000}")
    private Integer port;

    @Value("${socketio.bossCount:1}")
    private int bossCount;

    @Value("${socketio.workCount:0}")
    private int workCount;

    @Value("${socketio.allowCustomRequests:true}")
    private boolean allowCustomRequests;

    @Value("${socketio.upgradeTimeout:10000}")
    private int upgradeTimeout;

    @Value("${socketio.pingTimeout:60000}")
    private int pingTimeout;

    @Value("${socketio.pingInterval:25000}")
    private int pingInterval;

    @Value("${socketio.maxFramePayloadLength:65536}")
    private int maxFramePayloadLength;

    @Value("${socketio.maxHttpContentLength:65536}")
    private int maxHttpContentLength;

    @Bean
    public SocketIOServer socketIOServer() {
        SocketConfig socketConfig = new SocketConfig();
        socketConfig.setTcpNoDelay(true);
        socketConfig.setSoLinger(0);

        com.corundumstudio.socketio.Configuration config =
            new com.corundumstudio.socketio.Configuration();

        config.setSocketConfig(socketConfig);
        config.setHostname(host);
        config.setPort(port);
        config.setBossThreads(bossCount);
        config.setWorkerThreads(workCount);
        config.setAllowCustomRequests(allowCustomRequests);
        config.setUpgradeTimeout(upgradeTimeout);
        config.setPingTimeout(pingTimeout);
        config.setPingInterval(pingInterval);
        config.setMaxFramePayloadLength(maxFramePayloadLength);
        config.setMaxHttpContentLength(maxHttpContentLength);

        // Allow all origins / frontends (adjust to production)
        config.setOrigin("*");

        return new SocketIOServer(config);
    }
}

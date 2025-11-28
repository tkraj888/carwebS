package com.spring.jwt.controller;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.spring.jwt.Interfaces.BeadingCarService;
import com.spring.jwt.Interfaces.PlacedBidService;
import com.spring.jwt.dto.BeedingDtos.PlacedBidDTO;
import com.spring.jwt.dto.BidCarsDTO;
import com.spring.jwt.dto.LiveCarSocketDTO;
import com.spring.jwt.dto.ResponseDto;
import com.spring.jwt.entity.BidCars;
import com.spring.jwt.exception.*;
import com.spring.jwt.repository.BidCarsRepo;
import com.spring.jwt.service.BidCarsServiceImpl;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class SocketIOController {

    private final SocketIOServer socketIOServer;
    private final PlacedBidService placedBidService;
    private final BeadingCarService beadingCarService;
    private final BidCarsRepo bidCarsRepo;
    private final BidCarsServiceImpl bidCarsService;

    // Store connected clients
    private final Map<String, SocketIOClient> clientMap = new ConcurrentHashMap<>();

    // Track last liveCars count to avoid logging same message repeatedly
    private final AtomicInteger lastLiveCarsCount = new AtomicInteger(-1);

    @PostConstruct
    public void init() {
        // Connection listener
        socketIOServer.addConnectListener(this::onConnect);

        // Disconnection listener
        socketIOServer.addDisconnectListener(this::onDisconnect);

        // Event: Get Live Cars
        socketIOServer.addEventListener("getLiveCars", Object.class, (client, data, ackRequest) -> {
            String payload = (data != null) ? data.toString() : "null";
            // Log request once, but keep payload log only at debug level to reduce noise
            log.debug("📥 [SERVER] Received 'getLiveCars' from {} with payload: {}", client.getSessionId(), payload);

            try {
                List<BidCarsDTO> liveCars = beadingCarService.getAllLiveCars();
                List<LiveCarSocketDTO> socketCars = liveCars.stream()
                    .map(this::toSocketDto)
                    .toList();

                int newCount = socketCars.size();
                int previous = lastLiveCarsCount.getAndSet(newCount);
                if (previous != newCount) {
                    log.info("📤 [SERVER] Sending {} live cars to client {}", newCount, client.getSessionId());
                }

                client.sendEvent("liveCars", socketCars);
            } catch (Exception e) {
                log.error("❌ [SERVER] Error getting live cars:", e);
                client.sendEvent("error", Collections.singletonMap("message", e.getMessage()));
            }
        });

        // Event: Place Bid
        socketIOServer.addEventListener("placeBid", PlacedBidDTO.class, (client, placedBidDTO, ackRequest) -> {
            log.info("📥 [SERVER] Received 'placeBid' from {}: {}", client.getSessionId(), placedBidDTO);

            try {
                Optional<BidCars> bidCarOpt = bidCarsRepo.findById(placedBidDTO.getBidCarId());

                if (bidCarOpt.isPresent()) {
                    BidCars bidCar = bidCarOpt.get();
                    LocalDateTime now = LocalDateTime.now();
                    LocalDateTime closingTime = bidCar.getClosingTime();

                    if (closingTime.isBefore(now)) {
                        ResponseDto errorResponse = new ResponseDto("error",
                            "Bidding is over. No more bids can be placed.");
                        client.sendEvent("bidResponse", errorResponse);
                        return;
                    }

                    String result = placedBidService.placeBid(placedBidDTO, placedBidDTO.getBidCarId());

                    // Check if bid is within last 2 minutes
                    if (closingTime.isAfter(now) && closingTime.minusMinutes(2).isBefore(now)) {
                        log.info("📣 Bid placed within the last 2 minutes. Extending closing time for carId={}", placedBidDTO.getBidCarId());
                        bidCar.setClosingTime(closingTime.plusMinutes(2));
                        bidCarsRepo.save(bidCar);
                        bidCarsService.scheduleBidProcessing(bidCar);
                        log.info("📣 Updated Closing Time: {}", bidCar.getClosingTime());
                    }

                    // Broadcast updates to all clients
                    List<BidCarsDTO> liveCars = beadingCarService.getAllLiveCars();
                    List<LiveCarSocketDTO> socketCars = liveCars.stream()
                        .map(this::toSocketDto)
                        .toList();
                    log.info("📣 [SERVER] Broadcasting liveCars to all clients. Count: {}", socketCars.size());
                    socketIOServer.getBroadcastOperations().sendEvent("liveCars", socketCars);

                    // Send bid update
                    socketIOServer.getBroadcastOperations().sendEvent("bidUpdate", placedBidDTO);

                    // Send top three bids
                    List<PlacedBidDTO> topThreeBids = placedBidService.getTopThree(placedBidDTO.getBidCarId());
                    socketIOServer.getBroadcastOperations().sendEvent("topThreeBids", topThreeBids);

                    // Send top bid
                    PlacedBidDTO topBid = placedBidService.getTopBid(placedBidDTO.getBidCarId());
                    socketIOServer.getBroadcastOperations().sendEvent("topBid", topBid);

                    // Send response to the bidder
                    ResponseDto successResponse = new ResponseDto("success", result);
                    client.sendEvent("bidResponse", successResponse);

                    if (ackRequest.isAckRequested()) {
                        ackRequest.sendAckData(successResponse);
                    }
                } else {
                    log.error("❌ [SERVER] BidCar not found with ID: {}", placedBidDTO.getBidCarId());
                    ResponseDto errorResponse = new ResponseDto("error",
                        "BidCar not found with ID: " + placedBidDTO.getBidCarId());
                    client.sendEvent("bidResponse", errorResponse);
                }
            } catch (BidAmountLessException | UserNotFoundExceptions |
                     BidForSelfAuctionException | InsufficientBalanceException e) {
                log.error("❌ [SERVER] Error placing bid: {}", e.getMessage());
                ResponseDto errorResponse = new ResponseDto("error", e.getMessage());
                client.sendEvent("bidResponse", errorResponse);
            } catch (Exception ex) {
                log.error("❌ [SERVER] Unexpected error in placeBid:", ex);
                client.sendEvent("error", Collections.singletonMap("message", ex.getMessage()));
            }
        });

        // Event: Get Top Three Bids
        socketIOServer.addEventListener("getTopThreeBids", Object.class, (client, data, ackRequest) -> {
            String payload = (data != null) ? data.toString() : "null";
            log.info("📥 [SERVER] Received 'getTopThreeBids' from {} payload: {}", client.getSessionId(), payload);

            try {
                Integer bidCarId = null;
                // data might be a map or a plain number; try to extract
                if (data instanceof Number) {
                    bidCarId = ((Number) data).intValue();
                } else if (data instanceof String) {
                    try {
                        bidCarId = Integer.parseInt((String) data);
                    } catch (NumberFormatException ignored) { }
                } else if (data instanceof java.util.Map) {
                    Object val = ((java.util.Map<?, ?>) data).get("bidCarId");
                    if (val instanceof Number) bidCarId = ((Number) val).intValue();
                    else if (val instanceof String) {
                        try { bidCarId = Integer.parseInt((String) val); } catch (Exception ignored) {}
                    }
                }

                if (bidCarId == null) {
                    log.warn("⚠️ [SERVER] bidCarId not provided or unreadable in 'getTopThreeBids' payload");
                    client.sendEvent("topThreeBids", Collections.emptyList());
                    return;
                }

                List<com.spring.jwt.dto.BeedingDtos.PlacedBidDTO> topThreeBids = placedBidService.getTopThree(bidCarId);
                client.sendEvent("topThreeBids", topThreeBids);
            } catch (BidNotFoundExceptions e) {
                log.error("❌ [SERVER] Error finding top three bids: {}", e.getMessage());
                client.sendEvent("topThreeBids", Collections.emptyList());
            } catch (Exception ex) {
                log.error("❌ [SERVER] Unexpected error in getTopThreeBids:", ex);
                client.sendEvent("error", Collections.singletonMap("message", ex.getMessage()));
            }
        });

        // Event: Get Top Bid
        socketIOServer.addEventListener("getTopBid", Object.class, (client, data, ackRequest) -> {
            String payload = (data != null) ? data.toString() : "null";
            log.info("📥 [SERVER] Received 'getTopBid' from {} payload: {}", client.getSessionId(), payload);

            try {
                Integer bidCarId = null;
                if (data instanceof Number) {
                    bidCarId = ((Number) data).intValue();
                } else if (data instanceof String) {
                    try {
                        bidCarId = Integer.parseInt((String) data);
                    } catch (NumberFormatException ignored) { }
                } else if (data instanceof java.util.Map) {
                    Object val = ((java.util.Map<?, ?>) data).get("bidCarId");
                    if (val instanceof Number) bidCarId = ((Number) val).intValue();
                    else if (val instanceof String) {
                        try { bidCarId = Integer.parseInt((String) val); } catch (Exception ignored) {}
                    }
                }

                if (bidCarId == null) {
                    log.warn("⚠️ [SERVER] bidCarId not provided or unreadable in 'getTopBid' payload");
                    client.sendEvent("topBid", null);
                    return;
                }

                com.spring.jwt.dto.BeedingDtos.PlacedBidDTO topBid = placedBidService.getTopBid(bidCarId);
                client.sendEvent("topBid", topBid);
            } catch (BidNotFoundExceptions e) {
                log.error("❌ [SERVER] Error finding top bid: {}", e.getMessage());
                client.sendEvent("topBid", null);
            } catch (Exception ex) {
                log.error("❌ [SERVER] Unexpected error in getTopBid:", ex);
                client.sendEvent("error", Collections.singletonMap("message", ex.getMessage()));
            }
        });

        log.info("✅ [SERVER] Socket.IO event listeners registered");
    }

    private void onConnect(SocketIOClient client) {
        String clientId = client.getSessionId().toString();
        clientMap.put(clientId, client);
        log.info("🟢 [SERVER] Client connected: {} from {}", clientId, client.getRemoteAddress());
        client.sendEvent("connected", Collections.singletonMap("sessionId", clientId));
    }

    private void onDisconnect(SocketIOClient client) {
        String clientId = client.getSessionId().toString();
        clientMap.remove(clientId);
        log.info("🔴 [SERVER] Client disconnected: {}", clientId);
    }

    // Optional: periodic broadcast
    public void broadcastLiveCars() {
        try {
            List<BidCarsDTO> liveCars = beadingCarService.getAllLiveCars();
            List<LiveCarSocketDTO> socketCars = liveCars.stream()
                .map(this::toSocketDto)
                .toList();
            socketIOServer.getBroadcastOperations().sendEvent("liveCars", socketCars);
            log.info("📣 [SERVER] Broadcasted {} live cars to all clients", socketCars.size());
        } catch (Exception e) {
            log.error("❌ [SERVER] Error broadcasting live cars:", e);
        }
    }

    private LiveCarSocketDTO toSocketDto(BidCarsDTO src) {
        return new LiveCarSocketDTO(
            src.getBidCarId(),
            src.getBeadingCarId(),
            src.getClosingTime() != null ? src.getClosingTime().toString() : null,
            src.getCreatedAt() != null ? src.getCreatedAt().toString() : null,
            src.getBasePrice(),
            src.getUserId()
        );
    }
}

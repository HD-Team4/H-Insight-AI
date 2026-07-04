package com.hinsight.live.service;

import com.hinsight.live.dao.LiveSessionDao;
import com.hinsight.live.model.dto.LiveSessionRequest;
import com.hinsight.live.model.vo.LiveSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LiveSessionService {

    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_ON_AIR = "ON_AIR";
    public static final String STATUS_ENDED = "ENDED";

    private final LiveSessionDao liveSessionDao;

    public List<LiveSession> getLiveSessions() {
        return liveSessionDao.findAll();
    }

    public LiveSession getLiveSession(Long liveSessionId) {
        return liveSessionDao.findById(liveSessionId);
    }

    public LiveSession getOnAirSessionByProductId(Long productId) {
        return liveSessionDao.findOnAirByProductId(productId);
    }

    public LiveSession getCurrentOnAirSession() {
        return liveSessionDao.findCurrentOnAir();
    }

    @Transactional
    public LiveSession create(LiveSessionRequest request) {
        LiveSession liveSession = toLiveSession(null, request);
        liveSessionDao.insert(liveSession);
        return liveSession;
    }

    @Transactional
    public LiveSession update(Long liveSessionId, LiveSessionRequest request) {
        LiveSession liveSession = toLiveSession(liveSessionId, request);
        liveSessionDao.update(liveSession);
        return getLiveSession(liveSessionId);
    }

    @Transactional
    public LiveSession updateStatus(Long liveSessionId, String status) {
        liveSessionDao.updateStatus(liveSessionId, normalizeStatus(status));
        return getLiveSession(liveSessionId);
    }

    @Transactional
    public void delete(Long liveSessionId) {
        liveSessionDao.deleteById(liveSessionId);
    }

    public String toDisplayStatus(String status) {
        return switch (normalizeStatus(status)) {
            case STATUS_ON_AIR -> "방송중";
            case STATUS_ENDED -> "종료";
            default -> "예정";
        };
    }

    private LiveSession toLiveSession(Long liveSessionId, LiveSessionRequest request) {
        LiveSession liveSession = new LiveSession();
        liveSession.setLiveSessionId(liveSessionId);
        liveSession.setProductId(request.productId());
        liveSession.setStatus(normalizeStatus(request.status()));
        liveSession.setStartedAt(request.startedAt() == null ? LocalDateTime.now() : request.startedAt());
        return liveSession;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_SCHEDULED;
        }

        return switch (status.trim().toUpperCase()) {
            case "ON_AIR", "LIVE", "BROADCASTING", "방송중" -> STATUS_ON_AIR;
            case "ENDED", "END", "FINISHED", "종료" -> STATUS_ENDED;
            default -> STATUS_SCHEDULED;
        };
    }
}

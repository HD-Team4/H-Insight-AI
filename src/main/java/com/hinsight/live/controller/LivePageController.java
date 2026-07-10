package com.hinsight.live.controller;

import com.hinsight.live.model.vo.LiveSession;
import com.hinsight.live.service.LiveSessionService;
import com.hinsight.live.service.LiveViewerService;
import com.hinsight.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "live-page-controller", description = "라이브 방송 시청 페이지 뷰")
@Controller
@RequiredArgsConstructor
public class LivePageController {

    private final LiveSessionService liveSessionService;
    private final LiveViewerService liveViewerService;
    private final ProductService productService;

    @Operation(summary = "라이브 방송 룸", description = "방송중(ON_AIR)인 세션의 라이브 시청 페이지를 렌더링한다")
    @GetMapping("/live/{liveSessionId}")
    public String liveRoom(@PathVariable Long liveSessionId, Model model) {
        LiveSession liveSession = liveSessionService.getLiveSession(liveSessionId);
        if (liveSession == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!LiveSessionService.STATUS_ON_AIR.equals(liveSession.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        model.addAttribute("liveSession", liveSession);
        model.addAttribute("liveStatusLabel", liveSessionService.toDisplayStatus(liveSession.getStatus()));
        model.addAttribute("product", productService.getProductDetailById(liveSession.getProductId()));
        model.addAttribute("viewerCount", liveViewerService.count(liveSessionId));
        return "customer/live/room";
    }
}

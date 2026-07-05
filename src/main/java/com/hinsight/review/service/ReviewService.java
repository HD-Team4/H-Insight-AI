package com.hinsight.review.service;

import com.hinsight.common.dto.PageResponse;
import com.hinsight.review.dao.ReviewDao;
import com.hinsight.review.model.dto.ReviewCreateRequest;
import com.hinsight.review.model.dto.ReviewDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final int MAX_CONTENT_LENGTH = 1000;

    private final ReviewDao reviewDao;

    @Value("${review.page-size:5}")
    private int reviewPageSize;

    /**
     * 리뷰 작성. 별점 1~5·내용 필수를 검증하고 저장한다.
     * sentiment는 배치(리뷰 Lambda)가 채우므로 여기선 NULL로 남긴다.
     */
    @Transactional
    public void createReview(Long userId, ReviewCreateRequest request) {
        if (userId == null) {
            throw new IllegalArgumentException("로그인이 필요합니다.");
        }
        if (request == null || request.getProductId() == null) {
            throw new IllegalArgumentException("상품 정보가 올바르지 않습니다.");
        }
        Integer rating = request.getRating();
        if (rating == null || rating < 1 || rating > 5) {
            throw new IllegalArgumentException("별점은 1~5점 사이로 선택해 주세요.");
        }
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("리뷰 내용을 입력해 주세요.");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("리뷰는 최대 " + MAX_CONTENT_LENGTH + "자까지 작성할 수 있습니다.");
        }
        reviewDao.insert(request.getProductId(), userId, rating, content);
    }

    public PageResponse<ReviewDto> getReviewPageByProductId(Long productId, Integer requestedPage) {
        int size = Math.max(reviewPageSize, 1);
        long total = reviewDao.countByProductId(productId);
        int totalPages = (int) Math.ceil((double) total / size);
        int page = requestedPage == null || requestedPage < 1 ? 1 : requestedPage;
        if (totalPages > 0 && page > totalPages) {
            page = totalPages;
        }
        int offset = (page - 1) * size;

        List<ReviewDto> content = reviewDao.findPageByProductId(productId, offset, size);

        return new PageResponse<>(content, page, size, total, totalPages);
    }

    public double getAverageRatingByProductId(Long productId) {
        Double averageRating = reviewDao.findAverageRatingByProductId(productId);
        return averageRating == null ? 0.0 : averageRating;
    }

    public String toRatingStars(double rating) {
        int starCount = (int) Math.round(rating);
        starCount = Math.max(0, Math.min(starCount, 5));
        return "★".repeat(starCount) + "☆".repeat(5 - starCount);
    }
}

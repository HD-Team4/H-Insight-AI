package com.hinsight.review.service;

import com.hinsight.common.dto.PageResponse;
import com.hinsight.review.dao.ReviewDao;
import com.hinsight.review.model.dto.ReviewDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewDao reviewDao;

    @Value("${review.page-size:5}")
    private int reviewPageSize;

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

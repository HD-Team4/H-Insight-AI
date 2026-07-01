package com.hinsight.review.service;

import com.hinsight.review.dao.ReviewDao;
import com.hinsight.review.model.vo.Review;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewDao reviewDao;

    public List<Review> getReviewsByProductId(Long productId) {
        return reviewDao.findByProductId(productId);
    }
}

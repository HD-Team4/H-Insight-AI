package com.hinsight.review.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReviewDto {

    private Long reviewId;
    private Long productId;
    private Long userId;
    private String maskedUserName;
    private String content;
    private Integer rating;
    private String sentiment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String ratingStars;

    public void setUserName(String userName) {
        this.maskedUserName = maskName(userName);
    }

    public void setRating(Integer rating) {
        this.rating = rating;
        this.ratingStars = ratingStars(rating);
    }

    private String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "고객님";
        }

        String trimmed = name.trim();
        int length = trimmed.codePointCount(0, trimmed.length());

        if (length <= 1) {
            return "X";
        }

        int firstEnd = trimmed.offsetByCodePoints(0, 1);
        String first = trimmed.substring(0, firstEnd);

        if (length == 2) {
            return first + "X";
        }

        int lastStart = trimmed.offsetByCodePoints(0, length - 1);
        String last = trimmed.substring(lastStart);
        int maskLength = length == 3 ? 1 : length - 2;

        return first + "X".repeat(maskLength) + last;
    }

    private String ratingStars(Integer rating) {
        int starCount = rating == null ? 0 : Math.max(0, Math.min(rating, 5));
        return "★".repeat(starCount) + "☆".repeat(5 - starCount);
    }
}

package com.hinsight.review.dao;

import com.hinsight.review.model.dto.ReviewDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReviewDao {

    int insert(@Param("productId") Long productId,
               @Param("userId") Long userId,
               @Param("rating") Integer rating,
               @Param("content") String content);

    long countPurchased(@Param("userId") Long userId,
                        @Param("productId") Long productId);

    List<ReviewDto> findPageByProductId(@Param("productId") Long productId,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    long countByProductId(Long productId);

    Double findAverageRatingByProductId(Long productId);
}

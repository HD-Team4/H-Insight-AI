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

    List<ReviewDto> findPageByProductId(@Param("productId") Long productId,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    long countByProductId(Long productId);

    Double findAverageRatingByProductId(Long productId);

    /** 임베딩 적재용 배치 조회 (review_id keyset 페이징). content 가 있는 리뷰만. */
    List<ReviewDto> findBatchForEmbedding(@Param("lastId") long lastId,
                                          @Param("limit") int limit);
}

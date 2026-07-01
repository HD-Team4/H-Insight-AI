package com.hinsight.review.dao;

import com.hinsight.review.model.vo.Review;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ReviewDao {

    List<Review> findByProductId(Long productId);
}

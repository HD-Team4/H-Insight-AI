package com.hinsight.live.dao;

import com.hinsight.live.model.vo.LiveSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LiveSessionDao {

    List<LiveSession> findAll();

    LiveSession findById(Long liveSessionId);

    LiveSession findOnAirByProductId(Long productId);

    List<LiveSession> findOnAir();

    List<Long> findOnAirProductIds();

    int insert(LiveSession liveSession);

    int update(LiveSession liveSession);

    int updateStatus(@Param("liveSessionId") Long liveSessionId, @Param("status") String status);

    int deleteById(Long liveSessionId);
}

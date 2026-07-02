package com.hinsight.product.dao;

import com.hinsight.product.model.vo.SynonymSet;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SynonymDao {

    List<SynonymSet> findAll();

    List<SynonymSet> findAllActive();

    int insert(SynonymSet synonymSet);

    int update(SynonymSet synonymSet);

    int delete(Long synonymId);
}

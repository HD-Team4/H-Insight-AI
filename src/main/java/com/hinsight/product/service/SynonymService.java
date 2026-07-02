package com.hinsight.product.service;

import com.hinsight.product.dao.SynonymDao;
import com.hinsight.product.model.vo.SynonymSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SynonymService {

    private final SynonymDao synonymDao;

    public List<SynonymSet> getAll() {
        return synonymDao.findAll();
    }

    public List<SynonymSet> getAllActive() {
        return synonymDao.findAllActive();
    }

    @Transactional
    public SynonymSet create(String terms) {
        SynonymSet s = new SynonymSet();
        s.setTerms(terms.trim());
        s.setIsActive(true);
        synonymDao.insert(s);
        return s;
    }

    @Transactional
    public void update(SynonymSet synonymSet) {
        synonymDao.update(synonymSet);
    }

    @Transactional
    public void delete(Long synonymId) {
        synonymDao.delete(synonymId);
    }
}

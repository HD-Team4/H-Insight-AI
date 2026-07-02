package com.hinsight.product.service;

import com.hinsight.product.dao.ProductKeywordDao;
import com.hinsight.product.model.vo.ProductKeyword;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductKeywordService {

    private final ProductKeywordDao productKeywordDao;

    public List<ProductKeyword> getKeywords(Long productId) {
        return productKeywordDao.findByProductId(productId);
    }

    @Transactional
    public ProductKeyword addKeyword(Long productId, String keyword) {
        ProductKeyword pk = new ProductKeyword();
        pk.setProductId(productId);
        pk.setKeyword(keyword.trim());
        productKeywordDao.insert(pk);
        return pk;
    }

    @Transactional
    public void removeKeyword(Long keywordId) {
        productKeywordDao.delete(keywordId);
    }
}

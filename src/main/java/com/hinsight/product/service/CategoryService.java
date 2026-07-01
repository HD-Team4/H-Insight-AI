package com.hinsight.product.service;

import com.hinsight.product.dao.CategoryDao;
import com.hinsight.product.model.vo.Category;
import com.hinsight.product.model.vo.CategoryGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryDao categoryDao;

    public List<Category> getAllCategories() {
        return categoryDao.getAllCategories();
    }

    public List<Long> getCategoryIdsByGroup(CategoryGroup group) {
        if (group == null) return List.of();

        return categoryDao.getAllCategories().stream()
                .filter(c -> group == CategoryGroup.classify(c.getCategoryName()))
                .map(Category::getCategoryId)
                .collect(Collectors.toList());
    }
}

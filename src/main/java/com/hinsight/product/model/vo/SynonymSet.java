package com.hinsight.product.model.vo;

import com.hinsight.common.vo.BaseTimeVo;
import lombok.Data;

@Data
public class SynonymSet extends BaseTimeVo {
    private Long synonymId;
    private String terms;       // terms (쉼표 구분 동의어 그룹)
    private Boolean isActive;
}

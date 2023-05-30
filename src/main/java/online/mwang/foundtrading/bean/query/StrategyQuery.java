package online.mwang.foundtrading.bean.query;

import lombok.Data;
import online.mwang.foundtrading.bean.base.BaseQuery;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/5/30 15:56
 * @description: ScoreStrategy
 */
@Data
public class StrategyQuery extends BaseQuery {
    private String name;
    private String description;
    private String status;
}
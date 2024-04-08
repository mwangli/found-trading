package online.mwang.stockTrading.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.ObjectUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import online.mwang.stockTrading.web.bean.base.Response;
import online.mwang.stockTrading.web.bean.po.ModelStrategy;
import online.mwang.stockTrading.web.bean.query.StrategyQuery;
import online.mwang.stockTrading.web.service.ScoreStrategyService;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/3/20 10:56
 * @description: StrategyController
 */
@RestController
@RequestMapping("/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final static String ASCEND = "ascend";
    private final static String DESCEND = "descend";
    private final ScoreStrategyService strategyService;

    @PostMapping("/choose")
    public Boolean choose(@RequestBody ModelStrategy strategy) {
        cancelChoose();
        strategy.setStatus(1);
        return strategyService.update(strategy, new QueryWrapper<ModelStrategy>().lambda().eq(ModelStrategy::getId, strategy.getId()));
    }

    private void cancelChoose() {
        // 取消选中
        final List<ModelStrategy> oldChooseList = strategyService.list(new LambdaQueryWrapper<ModelStrategy>().eq(ModelStrategy::getStatus, "1"));
        oldChooseList.forEach(o -> {
            o.setStatus(0);
            strategyService.updateById(o);
        });
    }

    @PostMapping("/create")
    public Boolean create(@RequestBody ModelStrategy strategy) {
        final Date now = new Date();
        strategy.setCreateTime(now);
        strategy.setUpdateTime(now);
        strategy.setDeleted(1);
        if (strategy.getStatus() == 1) {
            cancelChoose();
        }
        return strategyService.save(strategy);
    }

    @PutMapping("/update")
    public Boolean update(@RequestBody ModelStrategy strategy) {
        if (strategy.getStatus() == 1) cancelChoose();
        strategy.setUpdateTime(new Date());
        return strategyService.updateById(strategy);
    }

    @DeleteMapping("/delete")
    public Boolean delete(@RequestBody ModelStrategy strategy) {
            strategy.setDeleted(0);
        return strategyService.updateById(strategy);
    }

    @GetMapping("/list")
    public Response<List<ModelStrategy>> list(StrategyQuery query) {
        LambdaQueryWrapper<ModelStrategy> queryWrapper = new QueryWrapper<ModelStrategy>().lambda()
                .like(ObjectUtils.isNotNull(query.getName()), ModelStrategy::getName, query.getName())
                .like(ObjectUtils.isNotNull(query.getParams()), ModelStrategy::getParams, query.getParams())
                .eq((ObjectUtils.isNotNull(query.getStatus())), ModelStrategy::getStatus, query.getStatus())
                .eq(ModelStrategy::getDeleted, "1")
                .orderBy(true, !DESCEND.equals(query.getSortOrder()), ModelStrategy.getOrder(query.getSortKey()));
        Page<ModelStrategy> pageResult = strategyService.page(Page.of(query.getCurrent(), query.getPageSize()), queryWrapper);
        return Response.success(pageResult.getRecords(), pageResult.getTotal());
    }
}

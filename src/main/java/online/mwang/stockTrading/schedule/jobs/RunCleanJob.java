package online.mwang.stockTrading.schedule.jobs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import online.mwang.stockTrading.web.bean.po.AccountInfo;
import online.mwang.stockTrading.web.bean.po.StockHistoryPrice;
import online.mwang.stockTrading.web.bean.po.StockInfo;
import online.mwang.stockTrading.web.service.AccountInfoService;
import online.mwang.stockTrading.web.service.StockInfoService;
import online.mwang.stockTrading.web.utils.DateUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/3/20 13:22
 * @description: FoundTradingMapper
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunCleanJob extends BaseJob {

    private final AccountInfoService accountInfoService;
    private final StockInfoService stockInfoService;
    private final MongoTemplate mongoTemplate;

    @Override
    public void run() {
        cleanAccountInfo();
        cleanStockInfo();
        cleanHistoryPrice();
    }

    private void cleanAccountInfo() {
        // 移除AccountInfo中一半的历史数据
        final List<AccountInfo> list = accountInfoService.list();
        final List<AccountInfo> deleteList = list.stream().skip(list.size() >> 1).collect(Collectors.toList());
        accountInfoService.removeBatchByIds(deleteList);
        log.info("共清理{}条账户信息历史数据。", deleteList.size());
    }

    private void cleanStockInfo() {
        // 清除已经退市的股票信息
        LambdaQueryWrapper<StockInfo> queryWrapper = new LambdaQueryWrapper<StockInfo>().eq(StockInfo::getDeleted, "0");
        List<StockInfo> deleteList = stockInfoService.list(queryWrapper);
        stockInfoService.removeBatchByIds(deleteList);
        log.info("共清理{}条账户退市股票信息。", deleteList.size());
    }

    private void cleanHistoryPrice() {
        // 移除MongoDB中前几个的历史数据的预测价格历史数据
        // 只保留最新的三个月数据
        final Query query = new Query(Criteria.where("date").lt(getPreMonthDate()));
        final List<StockHistoryPrice> remove = mongoTemplate.findAllAndRemove(query, StockHistoryPrice.class);
        log.info("共清理{}条价格预测历史数据。", remove.size());
    }

    private String getPreMonthDate() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        calendar.add(Calendar.MONTH, -3);
        return DateUtils.dateFormat.format(calendar.getTime());
    }
}
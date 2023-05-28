package online.mwang.foundtrading.bean.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/5/22 11:00
 * @description: StockInfo
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private String market;
    private Double increase;
    private Double price;
    private String prices;
    @TableField(exist = false)
    private List<Point> pricesList;
    private String increaseRate;
    @TableField(exist = false)
    private List<Point> increaseRateList;
    private Double score;
    private String permission;
    private Integer buySaleCount;
    private Date createTime;
    private Date updateTime;

    public static SFunction<StockInfo, Object> getOrder(String key) {
        if (key == null) return StockInfo::getUpdateTime;
        switch (key) {
            case "market":
                return StockInfo::getMarket;
            case "increase":
                return StockInfo::getIncrease;
            case "price":
                return StockInfo::getPrice;
            case "score":
                return StockInfo::getScore;
            case "permission":
                return StockInfo::getPermission;
            case "buySaleCount":
                return StockInfo::getBuySaleCount;
            default:
                return StockInfo::getUpdateTime;
        }
    }
}

package online.mwang.stockTrading.web.bean.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @version 1.0.0
 * @author: mwangli
 * @date: 2023/5/26 15:13
 * @description: Point
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Point {
    private String x;
    private Double y;
    private String z;
    private String t;
    private String s;
    private String type;

    public Point(String x, Double y) {
        this.x = x;
        this.y = y;
    }


    public Point(String x, Double y,String z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
}

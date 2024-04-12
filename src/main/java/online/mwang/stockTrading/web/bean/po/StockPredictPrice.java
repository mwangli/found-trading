package online.mwang.stockTrading.web.bean.po;


import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * @author 13255
 */
@Data
@EqualsAndHashCode(exclude = {"id"})
@Document
public class StockPredictPrice {
    @Id
    private String id;
    @Indexed(unique = true)
    private String date;
    private String name;
    @Indexed(unique = true)
    private String code;
    private Double price1;
    private Double price2;
    private Double price3;
    private Double price4;
}
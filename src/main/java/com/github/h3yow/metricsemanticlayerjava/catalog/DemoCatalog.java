package com.github.h3yow.metricsemanticlayerjava.catalog;

import com.github.h3yow.metricsemanticlayerjava.model.Column;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;
import com.github.h3yow.metricsemanticlayerjava.model.LocalTable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地演示数据：用内存表模拟订单分析表。
 *
 * <p>{@code biz_date} 为 yyyyMMdd 整数（配合 year/month/day 函数）；
 * {@code is_refund} 为布尔退款标记；数据故意同时含 2023/2024、CN/US、不同渠道，
 * 以便验证版本口径切换、过滤、分组、复合指标。</p>
 */
public final class DemoCatalog {

    private DemoCatalog() {
    }

    /** 构造订单明细演示快照。 */
    public static DataSnapshot buildSnapshot() {
        List<Column> columns = List.of(
                new Column("order_id", DataType.LONG),
                new Column("region", DataType.STRING),
                new Column("channel", DataType.STRING),
                new Column("price", DataType.DOUBLE),
                new Column("qty", DataType.LONG),
                new Column("amount", DataType.DOUBLE),
                new Column("is_refund", DataType.BOOLEAN),
                new Column("biz_date", DataType.LONG)
        );

        // region, channel, price, qty, amount, is_refund, biz_date
        Object[][] data = {
                {1L, "CN", "app",  100.0, 2L, 200.0, false, 20240105L},
                {2L, "CN", "web",   50.0, 1L,  50.0, false, 20240211L},
                {3L, "CN", "app",  300.0, 1L, 300.0, true,  20240301L},
                {4L, "US", "app",  120.0, 3L, 360.0, false, 20240315L},
                {5L, "US", "web",   80.0, 2L, 160.0, false, 20240620L},
                {6L, "US", "app",  200.0, 1L, 200.0, true,  20240708L},
                {7L, "CN", "web",   90.0, 2L, 180.0, false, 20231125L},
                {8L, "CN", "app",  150.0, 1L, 150.0, false, 20231202L},
                {9L, "US", "web",   40.0, 5L, 200.0, false, 20231219L},
                {10L, "CN", "app",   60.0, 0L,   0.0, false, 20240830L},
        };

        List<Map<String, Object>> rows = new java.util.ArrayList<>(data.length);
        for (Object[] d : data) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                r.put(columns.get(i).name(), d[i]);
            }
            rows.add(Map.copyOf(r));
        }

        LocalTable orders = new LocalTable("orders", columns, rows);
        return new DataSnapshot("snap-2024q3", List.of(orders));
    }
}

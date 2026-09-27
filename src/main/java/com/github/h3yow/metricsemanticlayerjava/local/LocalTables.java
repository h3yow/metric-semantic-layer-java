package com.github.h3yow.metricsemanticlayerjava.local;

import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.model.FieldDef;
import com.github.h3yow.metricsemanticlayerjava.model.Row;
import com.github.h3yow.metricsemanticlayerjava.model.TableData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.github.h3yow.metricsemanticlayerjava.model.DataType.BOOLEAN;
import static com.github.h3yow.metricsemanticlayerjava.model.DataType.DOUBLE;
import static com.github.h3yow.metricsemanticlayerjava.model.DataType.LONG;
import static com.github.h3yow.metricsemanticlayerjava.model.DataType.STRING;

/**
 * Local stand-in for an analytic fact table. All data is generated in-process;
 * no database or network is involved.
 */
public final class LocalTables {

    public static final String ORDERS = "orders";

    private LocalTables() {
    }

    public static TableData ordersTable(long rowCount) {
        List<FieldDef> columns = List.of(
                new FieldDef("order_id", LONG),
                new FieldDef("region", STRING),
                new FieldDef("channel", STRING),
                new FieldDef("amount", DOUBLE),
                new FieldDef("cost", DOUBLE),
                new FieldDef("quantity", LONG),
                new FieldDef("is_refunded", BOOLEAN),
                new FieldDef("year", LONG));

        Random rnd = new Random(42); // deterministic data
        String[] regions = {"CN", "US", "EU"};
        String[] channels = {"web", "app", "store"};
        List<Row> rows = new ArrayList<>((int) rowCount);
        for (long i = 1; i <= rowCount; i++) {
            boolean refunded = rnd.nextDouble() < 0.1;
            double amount = Math.round((20 + rnd.nextDouble() * 980) * 100.0) / 100.0;
            double cost = Math.round(amount * (0.4 + rnd.nextDouble() * 0.4) * 100.0) / 100.0;
            rows.add(Row.of(
                    "order_id", i,
                    "region", regions[(int) (i % regions.length)],
                    "channel", channels[(int) (i % channels.length)],
                    "amount", amount,
                    "cost", cost,
                    "quantity", 1L + (i % 5),
                    "is_refunded", refunded,
                    "year", i % 2 == 0 ? 2024L : 2023L));
        }
        return new TableData(ORDERS, columns, rows);
    }

    public static DataSnapshot snapshot(long id, long rowCount) {
        return DataSnapshot.builder(id, "orders-snapshot-" + id)
                .add(ordersTable(rowCount))
                .build();
    }
}

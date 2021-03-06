package io.mykit.data.monitor.mysql.binlog.impl.variable.status;


import io.mykit.data.monitor.mysql.common.glossary.column.StringColumn;
import io.mykit.data.monitor.mysql.common.util.MySQLConstants;
import io.mykit.data.monitor.mysql.common.util.ToStringBuilder;
import io.mykit.data.monitor.mysql.io.XInputStream;

import java.io.IOException;

public class QTimeZoneCode extends AbstractStatusVariable {
    public static final int TYPE = MySQLConstants.Q_TIME_ZONE_CODE;

    private final StringColumn timeZone;

    public QTimeZoneCode(StringColumn timeZone) {
        super(TYPE);
        this.timeZone = timeZone;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("timeZone", timeZone).toString();
    }

    public StringColumn getTimeZone() {
        return timeZone;
    }

    public static QTimeZoneCode valueOf(XInputStream tis) throws IOException {
        final int length = tis.readInt(1); // Length
        return new QTimeZoneCode(tis.readFixedLengthString(length));
    }
}

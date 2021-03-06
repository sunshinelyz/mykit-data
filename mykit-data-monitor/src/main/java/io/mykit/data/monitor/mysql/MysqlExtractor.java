/**
 * Copyright 2020-9999 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mykit.data.monitor.mysql;

import io.mykit.data.connector.config.DatabaseConfig;
import io.mykit.data.connector.constants.ConnectorConstants;
import io.mykit.data.monitor.AbstractExtractor;
import io.mykit.data.monitor.config.Host;
import io.mykit.data.monitor.exception.ListenerException;
import io.mykit.data.monitor.mysql.binlog.BinlogEventListener;
import io.mykit.data.monitor.mysql.binlog.BinlogEventV4;
import io.mykit.data.monitor.mysql.binlog.BinlogRemoteClient;
import io.mykit.data.monitor.mysql.binlog.impl.event.*;
import io.mykit.data.monitor.mysql.common.glossary.Column;
import io.mykit.data.monitor.mysql.common.glossary.Pair;
import io.mykit.data.monitor.mysql.common.glossary.Row;
import io.mykit.data.monitor.mysql.common.glossary.column.StringColumn;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.*;
import java.util.regex.Matcher;

import static java.util.regex.Pattern.compile;

/**
 * @author binghe
 * @version 1.0.0
 * @description MySQL提取器
 */
public class MysqlExtractor extends AbstractExtractor {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static final String BINLOG_FILENAME = "fileName";
    private static final String BINLOG_POSITION = "position";
    private BinlogRemoteClient client;
    private List<Host> cluster;
    private int master = 0;

    @Override
    public void start() {
        try {
            final DatabaseConfig config = (DatabaseConfig) connectorConfig;
            cluster = readNodes(config.getUrl());
            Assert.notEmpty(cluster, "Mysql连接地址有误.");

            final Host host = cluster.get(master);
            final String username = config.getUsername();
            final String password = config.getPassword();
            // mysql-binlog-127.0.0.1:3306-654321
            final String threadSuffixName = new StringBuilder("mysql-binlog-")
                    .append(host.getIp()).append(":").append(host.getPort()).append("-")
                    .append(RandomStringUtils.randomNumeric(6))
                    .toString();

            client = new BinlogRemoteClient(host.getIp(), host.getPort(), username, password, threadSuffixName);
            client.setBinlogFileName(map.get(BINLOG_FILENAME));
            String pos = map.get(BINLOG_POSITION);
            client.setBinlogPosition(StringUtils.isBlank(pos) ? 0 : Long.parseLong(pos));
            client.setBinlogEventListener(new MysqlEventListener());
            client.start();
        } catch (Exception e) {
            logger.error("启动失败:{}", e.getMessage());
            throw new ListenerException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (null != client) {
                client.stopQuietly();
            }
        } catch (Exception e) {
            logger.error("关闭失败:{}", e.getMessage());
        }
    }

    private List<Host> readNodes(String url) {
        if (StringUtils.isBlank(url)) {
            return Collections.EMPTY_LIST;
        }
        Matcher matcher = compile("(//)(?!(/)).+?(/)").matcher(url);
        while (matcher.find()) {
            url = matcher.group(0);
            break;
        }
        url = StringUtils.replace(url, "/", "");

        List<Host> cluster = new ArrayList<>();
        String[] arr = StringUtils.split(url, ",");
        int size = arr.length;
        for (int i = 0; i < size; i++) {
            String[] host = StringUtils.split(arr[i], ":");
            if (2 == host.length) {
                cluster.add(new Host(host[0], Integer.parseInt(host[1])));
            }
        }
        return cluster;
    }

    /**
     * 有变化触发刷新binlog增量事件
     *
     * @param event
     */
    private void refresh(AbstractBinlogEventV4 event) {
        String binlogFilename = event.getBinlogFilename();
        long nextPosition = event.getHeader().getNextPosition();

        // binlogFileName
        if (StringUtils.isNotBlank(binlogFilename) && !StringUtils.equals(binlogFilename, client.getBinlogFileName())) {
            client.setBinlogFileName(binlogFilename);
        }
        client.setBinlogPosition(nextPosition);

        // nextPosition
        map.put(BINLOG_FILENAME, client.getBinlogFileName());
        map.put(BINLOG_POSITION, String.valueOf(client.getBinlogPosition()));
    }

    final class MysqlEventListener implements BinlogEventListener {

        private Map<Long, String> table = new HashMap<>();

        @Override
        public void onEvents(BinlogEventV4 event) {
            if (event == null) {
                logger.error("binlog event is null");
                return;
            }

            if (event instanceof TableMapEvent) {
                TableMapEvent tableEvent = (TableMapEvent) event;
                table.putIfAbsent(tableEvent.getTableId(), tableEvent.getTableName().toString());
                return;
            }

            if (event instanceof UpdateRowsEventV2) {
                UpdateRowsEventV2 e = (UpdateRowsEventV2) event;
                final String tableName = table.get(e.getTableId());
                List<Pair<Row>> rows = e.getRows();
                for (Pair<Row> p : rows) {
                    List<Object> before = new ArrayList<>();
                    List<Object> after = new ArrayList<>();
                    addAll(before, p.getBefore().getColumns());
                    addAll(after, p.getAfter().getColumns());
                    changedLogEvent(tableName, ConnectorConstants.OPERTION_UPDATE, before, after);
                    //break;
                }
                return;
            }

            if (event instanceof WriteRowsEventV2) {
                WriteRowsEventV2 e = (WriteRowsEventV2) event;
                final String tableName = table.get(e.getTableId());
                List<Row> rows = e.getRows();
                for (Row row : rows) {
                    List<Object> after = new ArrayList<>();
                    addAll(after, row.getColumns());
                    changedLogEvent(tableName, ConnectorConstants.OPERTION_INSERT, Collections.EMPTY_LIST, after);
                    //break;
                }
                return;
            }

            if (event instanceof DeleteRowsEventV2) {
                DeleteRowsEventV2 e = (DeleteRowsEventV2) event;
                final String tableName = table.get(e.getTableId());
                List<Row> rows = e.getRows();
                for (Row row : rows) {
                    List<Object> before = new ArrayList<>();
                    addAll(before, row.getColumns());
                    changedLogEvent(tableName, ConnectorConstants.OPERTION_DELETE, before, Collections.EMPTY_LIST);
                    //break;
                }
                return;
            }

            // 处理事件优先级：RotateEvent > FormatDescriptionEvent > TableMapEvent > RowsEvent > XidEvent
            if (event instanceof XidEvent) {
                refresh((XidEvent) event);
                return;
            }

            // 切换binlog
            if (event instanceof RotateEvent) {
                refresh((RotateEvent) event);
                return;
            }

        }

        private void addAll(List<Object> before, List<Column> columns) {
            columns.forEach(c -> before.add((c instanceof StringColumn) ? c.toString() : c.getValue()));
        }

    }
}

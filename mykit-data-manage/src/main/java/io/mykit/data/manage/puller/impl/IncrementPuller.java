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
package io.mykit.data.manage.puller.impl;

import io.mykit.data.common.event.Event;
import io.mykit.data.common.utils.CollectionUtils;
import io.mykit.data.common.utils.UUIDUtils;
import io.mykit.data.connector.config.ConnectorConfig;
import io.mykit.data.connector.config.Table;
import io.mykit.data.connector.factory.ConnectorFactory;
import io.mykit.data.manage.Manager;
import io.mykit.data.manage.config.ExtractorConfig;
import io.mykit.data.manage.config.FieldPicker;
import io.mykit.data.manage.puller.AbstractPuller;
import io.mykit.data.monitor.AbstractExtractor;
import io.mykit.data.monitor.Extractor;
import io.mykit.data.monitor.Listener;
import io.mykit.data.monitor.config.ListenerConfig;
import io.mykit.data.monitor.enums.ListenerTypeEnum;
import io.mykit.data.monitor.quartz.QuartzExtractor;
import io.mykit.data.monitor.quartz.ScheduledTaskJob;
import io.mykit.data.monitor.quartz.ScheduledTaskService;
import io.mykit.data.parser.Parser;
import io.mykit.data.parser.logger.LogService;
import io.mykit.data.parser.logger.LogType;
import io.mykit.data.parser.model.*;
import io.mykit.data.parser.utils.PickerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * @author binghe
 * @version 1.0.0
 * @description 增量同步
 */
@Component
public class IncrementPuller extends AbstractPuller implements ScheduledTaskJob, InitializingBean, DisposableBean {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private Parser parser;

    @Autowired
    private Listener listener;

    @Autowired
    private Manager manager;

    @Autowired
    private LogService logService;

    @Autowired
    private ScheduledTaskService scheduledTaskService;

    @Autowired
    private ConnectorFactory connectorFactory;

    private String key;

    private Map<String, Extractor> map = new ConcurrentHashMap<>();

    @Override
    public void asyncStart(Mapping mapping) {
        final String mappingId = mapping.getId();
        final String metaId = mapping.getMetaId();
        try {
            Connector connector = manager.getConnector(mapping.getSourceConnectorId());
            Assert.notNull(connector, "连接器不能为空.");
            List<TableGroup> list = manager.getTableGroupAll(mappingId);
            Assert.notEmpty(list, "映射关系不能为空.");
            Meta meta = manager.getMeta(metaId);
            Assert.notNull(meta, "Meta不能为空.");
            AbstractExtractor extractor = getExtractor(mapping, connector, list, meta);
            Assert.notNull(extractor, "未知的监听配置.");

            long now = Instant.now().toEpochMilli();
            meta.setBeginTime(now);
            meta.setEndTime(now);
            manager.editMeta(meta);
            map.putIfAbsent(metaId, extractor);

            // 执行任务
            logger.info("启动成功:{}", metaId);
            map.get(metaId).start();
        } catch (Exception e) {
            close(metaId);
            logService.log(LogType.TableGroupLog.INCREMENT_FAILED, e.getMessage());
            logger.error("运行异常，结束任务{}:{}", metaId, e.getMessage());
        }
    }

    @Override
    public void close(String metaId) {
        Extractor extractor = map.get(metaId);
        if (null != extractor) {
            extractor.clearAllListener();
            extractor.close();
        }
        map.remove(metaId);
        publishClosedEvent(metaId);
        logger.info("关闭成功:{}", metaId);
    }

    @Override
    public void run() {
        // 定时同步增量信息
        map.forEach((k, v) -> v.flushEvent());
    }

    @Override
    public void afterPropertiesSet() {
        key = UUIDUtils.getUUID();
        scheduledTaskService.start(key, "*/10 * * * * ?", this);
    }

    @Override
    public void destroy() {
        scheduledTaskService.stop(key);
    }

    private AbstractExtractor getExtractor(Mapping mapping, Connector connector, List<TableGroup> list, Meta meta)
            throws InstantiationException, IllegalAccessException {
        ConnectorConfig connectorConfig = connector.getConfig();
        ListenerConfig listenerConfig = mapping.getListener();

        // timing/log
        final String listenerType = listenerConfig.getListenerType();

        // 默认定时抽取
        if (ListenerTypeEnum.isTiming(listenerType)) {
            QuartzExtractor extractor = listener.getExtractor(listenerType, QuartzExtractor.class);
            List<Map<String, String>> commands = list.stream().map(t -> t.getCommand()).collect(Collectors.toList());

            ExtractorConfig config = new ExtractorConfig(connectorConfig, listenerConfig, meta.getMap(), new QuartzListener(mapping, list));
            setExtractorConfig(extractor, config);
            extractor.setConnectorFactory(connectorFactory);
            extractor.setScheduledTaskService(scheduledTaskService);
            extractor.setCommands(commands);
            return extractor;
        }

        // 基于日志抽取
        if (ListenerTypeEnum.isLog(listenerType)) {
            final String connectorType = connectorConfig.getConnectorType();
            AbstractExtractor extractor = listener.getExtractor(connectorType, AbstractExtractor.class);

            ExtractorConfig config = new ExtractorConfig(connectorConfig, listenerConfig, meta.getMap(), new LogListener(mapping, list));
            setExtractorConfig(extractor, config);
            return extractor;
        }
        return null;
    }

    private void setExtractorConfig(AbstractExtractor extractor, ExtractorConfig config) {
        extractor.setConnectorConfig(config.getConnectorConfig());
        extractor.setListenerConfig(config.getListenerConfig());
        extractor.setMap(config.getMap());
        extractor.addListener(config.getEvent());
    }

    abstract class AbstractListener implements Event {
        protected Mapping mapping;
        protected String metaId;
        protected AtomicBoolean changed = new AtomicBoolean();

        @Override
        public void changedLogEvent(String tableName, String event, List<Object> before, List<Object> after) {
            // nothing to do
        }

        @Override
        public void changedQuartzEvent(int tableGroupIndex, String event, Map<String, Object> before, Map<String, Object> after) {
            // nothing to do
        }

        @Override
        public void flushEvent(Map<String, String> map) {
            // 如果有变更，执行更新
            if (changed.compareAndSet(true, false)) {
                Meta meta = manager.getMeta(metaId);
                if (null != meta) {
                    meta.setMap(map);
                    manager.editMeta(meta);
                }
            }
        }

        @Override
        public void errorEvent(Exception e) {
            logService.log(LogType.TableGroupLog.INCREMENT_FAILED, e.getMessage());
        }

    }

    /**
     * </p>定时模式
     * <ol>
     * <li>根据过滤条件筛选</li>
     * </ol>
     * </p>同步关系：
     * </p>数据源表 >> 目标源表
     * <ul>
     * <li>A >> B</li>
     * <li>A >> C</li>
     * <li>E >> F</li>
     * </ul>
     * </p>PS：
     * <ol>
     * <li>依次执行同步关系A >> B 然后 A >> C ...</li>
     * </ol>
     */
    final class QuartzListener extends AbstractListener {

        private List<FieldPicker> tablePicker;

        public QuartzListener(Mapping mapping, List<TableGroup> list) {
            this.mapping = mapping;
            this.metaId = mapping.getMetaId();
            this.tablePicker = new LinkedList<>();
            list.forEach(t -> tablePicker.add(new FieldPicker(PickerUtils.mergeTableGroupConfig(mapping, t))));
        }

        @Override
        public void changedQuartzEvent(int tableGroupIndex, String event, Map<String, Object> before, Map<String, Object> after) {
            final FieldPicker picker = tablePicker.get(tableGroupIndex);
            logger.info("监听数据=> tableName:{}, event:{}, before:{}, after:{}", picker.getTableGroup().getSourceTable().getName(), event, before, after);

            // 处理过程有异常向上抛
            DataEvent data = new DataEvent(event, before, after);
            parser.execute(mapping, picker.getTableGroup(), data);

            // 标记有变更记录
            changed.compareAndSet(false, true);
        }
    }

    /**
     * </p>日志模式
     * <ol>
     * <li>监听表增量数据</li>
     * <li>根据过滤条件筛选</li>
     * </ol>
     * </p>同步关系：
     * </p>数据源表 >> 目标源表
     * <ul>
     * <li>A >> B</li>
     * <li>A >> C</li>
     * <li>E >> F</li>
     * </ul>
     * </p>PS：
     * <ol>
     * <li>为减少开销而选择复用监听器实例, 启动时只需创建一个数据源连接器.</li>
     * <li>关系A >> B和A >> C会复用A监听的数据, A监听到增量数据，会发送给B和C.</li>
     * <li>该模式下，会监听表所有字段.</li>
     * </ol>
     */
    final class LogListener extends AbstractListener {

        private Map<String, List<FieldPicker>> tablePicker;

        public LogListener(Mapping mapping, List<TableGroup> list) {
            this.mapping = mapping;
            this.metaId = mapping.getMetaId();
            this.tablePicker = new LinkedHashMap<>();
            list.forEach(t -> {
                final Table table = t.getSourceTable();
                final String tableName = table.getName();
                tablePicker.putIfAbsent(tableName, new ArrayList<>());
                TableGroup group = PickerUtils.mergeTableGroupConfig(mapping, t);
                tablePicker.get(tableName).add(new FieldPicker(group, group.getFilter(), table.getColumn(), group.getFieldMapping()));
            });
        }

        @Override
        public void changedLogEvent(String tableName, String event, List<Object> before, List<Object> after) {
            logger.info("监听数据=> tableName:{}, event:{}, before:{}, after:{}", tableName, event, before, after);

            // 处理过程有异常向上抛
            List<FieldPicker> pickers = tablePicker.get(tableName);
            if (!CollectionUtils.isEmpty(pickers)) {
                pickers.parallelStream().forEach(picker -> {
                    DataEvent data = new DataEvent(event, picker.getColumns(before), picker.getColumns(after));
                    if (picker.filter(data)) {
                        parser.execute(mapping, picker.getTableGroup(), data);
                    }
                });
            }

            // 标记有变更记录
            changed.compareAndSet(false, true);
        }

    }
}

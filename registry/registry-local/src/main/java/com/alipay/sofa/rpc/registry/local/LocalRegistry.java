/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.registry.local;

import com.alipay.sofa.rpc.client.ProviderGroup;
import com.alipay.sofa.rpc.client.ProviderInfo;
import com.alipay.sofa.rpc.common.struct.MapDifference;
import com.alipay.sofa.rpc.common.struct.ScheduledService;
import com.alipay.sofa.rpc.common.struct.ValueDifference;
import com.alipay.sofa.rpc.common.utils.CommonUtils;
import com.alipay.sofa.rpc.common.utils.StringUtils;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.config.ProviderConfig;
import com.alipay.sofa.rpc.config.RegistryConfig;
import com.alipay.sofa.rpc.config.ServerConfig;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.event.ConsumerSubEvent;
import com.alipay.sofa.rpc.event.EventBus;
import com.alipay.sofa.rpc.event.ProviderPubEvent;
import com.alipay.sofa.rpc.ext.Extension;
import com.alipay.sofa.rpc.listener.ProviderInfoListener;
import com.alipay.sofa.rpc.log.LogCodes;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;
import com.alipay.sofa.rpc.registry.Registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Local registry
 *
 * @author <a href="mailto:zhanggeng.zg@antfin.com">GengZhang</a>
 */
@Extension("local")
public class LocalRegistry extends Registry {

    /**
     * Logger
     */
    private static final Logger                 LOGGER          = LoggerFactory.getLogger(LocalRegistry.class);

    /**
     * ????????????
     */
    private ScheduledService                    scheduledExecutorService;

    /**
     * ???????????????????????? {service : [provider...]}
     */
    protected Map<String, ProviderGroup>        memoryCache     = new ConcurrentHashMap<String, ProviderGroup>();

    /**
     * ?????????????????????????????????true?????????????????????????????????
     */
    private boolean                             needBackup      = false;

    /**
     * ?????????????????????????????????????????????????????????true
     * ??????FileRegistry??????????????????????????????????????????????????????????????????false?????????????????????
     */
    private boolean                             subscribe       = true;

    /**
     * ????????????????????????key????????????????????????value???ConsumerConfig?????????
     */
    protected Map<String, List<ConsumerConfig>> notifyListeners = new ConcurrentHashMap<String, List<ConsumerConfig>>();

    /**
     * ????????????digest???
     */
    private String                              lastDigest;

    /**
     * ?????????????????????
     */
    private int                                 scanPeriod      = 2000;
    /**
     * ???????????????????????????
     */
    private String                              regFile;

    /**
     * ??????????????????
     *
     * @param registryConfig ??????????????????
     */
    protected LocalRegistry(RegistryConfig registryConfig) {
        super(registryConfig);
    }

    @Override
    public void init() {

        if (StringUtils.isNotBlank(regFile)) {
            return;
        }

        this.regFile = registryConfig.getFile();
        if (regFile == null) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_LOCAL_FILE_NULL));
        }
        // ???????????????
        if (subscribe) {
            doLoadCache();
        }
        // ????????????
        this.scanPeriod = CommonUtils.parseInt(registryConfig.getParameter("registry.local.scan.period"),
            scanPeriod);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    // ?????????????????????????????????????????????????????????????????????
                    doWriteFile();

                    // ???????????????????????????????????????
                    // ??????????????????????????????????????????????????????
                    if (subscribe && LocalRegistryHelper.checkModified(regFile, lastDigest)) {
                        doLoadCache();
                    }
                } catch (Throwable e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
        };
        //??????????????????
        scheduledExecutorService = new ScheduledService("LocalRegistry-Back-Load",
            ScheduledService.MODE_FIXEDDELAY,
            task, //??????load??????
            scanPeriod, // ??????????????????
            scanPeriod, // ??????????????????
            TimeUnit.MILLISECONDS
                ).start();

    }

    protected void doLoadCache() {
        // ???????????????
        Map<String, ProviderGroup> tempCache = LocalRegistryHelper.loadBackupFileToCache(regFile);
        // ?????????????????????????????????????????????????????????
        notifyConsumer(tempCache);

        // ????????????????????????
        memoryCache = tempCache;
        // ?????????????????????,?????????????????????????????????????????????
        lastDigest = LocalRegistryHelper.calMD5Checksum(regFile);
    }

    /**
     * ?????????
     */
    protected void doWriteFile() {
        if (needBackup) {
            if (LocalRegistryHelper.backup(regFile, memoryCache)) {
                needBackup = false;
            }
        }
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public void register(ProviderConfig config) {
        String appName = config.getAppName();
        if (!registryConfig.isRegister()) {
            if (LOGGER.isInfoEnabled(appName)) {
                LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_REGISTRY_IGNORE));
            }
            return;
        }
        if (!config.isRegister()) { // ??????????????????????????????????????????
            return;
        }
        List<ServerConfig> serverConfigs = config.getServer();
        if (CommonUtils.isNotEmpty(serverConfigs)) {
            for (ServerConfig server : serverConfigs) {
                String serviceName = LocalRegistryHelper.buildListDataId(config, server.getProtocol());
                ProviderInfo providerInfo = LocalRegistryHelper.convertProviderToProviderInfo(config, server);
                if (LOGGER.isInfoEnabled(appName)) {
                    LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_PUB_START, serviceName));
                }
                doRegister(appName, serviceName, providerInfo);

                if (LOGGER.isInfoEnabled(appName)) {
                    LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_PUB_OVER, serviceName));
                }
            }
            if (EventBus.isEnable(ProviderPubEvent.class)) {
                ProviderPubEvent event = new ProviderPubEvent(config);
                EventBus.post(event);
            }

        }
    }

    /**
     * ????????????????????????
     *
     * @param appName      ?????????
     * @param serviceName  ???????????????
     * @param providerInfo ?????????????????????
     */
    protected void doRegister(String appName, String serviceName, ProviderInfo providerInfo) {
        if (LOGGER.isInfoEnabled(appName)) {
            LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_PUB, serviceName));
        }
        //{service : [provider...]}
        ProviderGroup oldGroup = memoryCache.get(serviceName);
        if (oldGroup != null) { // ????????????key
            oldGroup.add(providerInfo);
        } else { // ????????????key??????????????????
            List<ProviderInfo> news = new ArrayList<ProviderInfo>();
            news.add(providerInfo);
            memoryCache.put(serviceName, new ProviderGroup(news));
        }
        // ??????????????? ???????????????
        needBackup = true;
        doWriteFile();

        if (subscribe) {
            notifyConsumerListeners(serviceName, memoryCache.get(serviceName));
        }
    }

    @Override
    public void unRegister(ProviderConfig config) {
        String appName = config.getAppName();
        if (!registryConfig.isRegister()) { // ?????????????????????
            if (LOGGER.isInfoEnabled(appName)) {
                LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_REGISTRY_IGNORE));
            }
            return;
        }
        if (!config.isRegister()) { // ???????????????
            return;
        }
        List<ServerConfig> serverConfigs = config.getServer();
        if (CommonUtils.isNotEmpty(serverConfigs)) {
            for (ServerConfig server : serverConfigs) {
                String serviceName = LocalRegistryHelper.buildListDataId(config, server.getProtocol());
                ProviderInfo providerInfo = LocalRegistryHelper.convertProviderToProviderInfo(config, server);
                try {
                    doUnRegister(serviceName, providerInfo);
                    if (LOGGER.isInfoEnabled(appName)) {
                        LOGGER.infoWithApp(appName,
                            LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_UNPUB, serviceName, "1"));
                    }
                } catch (Exception e) {
                    LOGGER.errorWithApp(appName, LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_UNPUB, serviceName, "0"),
                        e);
                }
            }
        }
    }

    /**
     * ?????????????????????
     *
     * @param serviceName  ???????????????
     * @param providerInfo ?????????????????????
     */
    protected void doUnRegister(String serviceName, ProviderInfo providerInfo) {
        //{service : [provider...]}
        ProviderGroup oldGroup = memoryCache.get(serviceName);
        if (oldGroup != null) { // ????????????key
            oldGroup.remove(providerInfo);
        } else {
            return;
        }
        // ??????????????? ???????????????
        needBackup = true;
        doWriteFile();

        if (subscribe) {
            notifyConsumerListeners(serviceName, memoryCache.get(serviceName));
        }
    }

    @Override
    public void batchUnRegister(List<ProviderConfig> configs) {
        for (ProviderConfig config : configs) {
            String appName = config.getAppName();
            try {
                unRegister(config);
            } catch (Exception e) {
                LOGGER.errorWithApp(appName, "Error when batch unregistry", e);
            }
        }
    }

    @Override
    public List<ProviderGroup> subscribe(ConsumerConfig config) {
        String key = LocalRegistryHelper.buildListDataId(config, config.getProtocol());
        List<ConsumerConfig> listeners = notifyListeners.get(key);
        if (listeners == null) {
            listeners = new ArrayList<ConsumerConfig>();
            notifyListeners.put(key, listeners);
        }
        listeners.add(config);
        // ????????????????????????????????????????????????????????????)
        ProviderGroup group = memoryCache.get(key);
        if (group == null) {
            group = new ProviderGroup();
            memoryCache.put(key, group);
        }

        if (EventBus.isEnable(ConsumerSubEvent.class)) {
            ConsumerSubEvent event = new ConsumerSubEvent(config);
            EventBus.post(event);
        }

        return Collections.singletonList(group);
    }

    @Override
    public void unSubscribe(ConsumerConfig config) {
        String key = LocalRegistryHelper.buildListDataId(config, config.getProtocol());
        // ???????????????????????????????????????????????????
        List<ConsumerConfig> listeners = notifyListeners.get(key);
        if (listeners != null) {
            listeners.remove(config);
            if (listeners.size() == 0) {
                notifyListeners.remove(key);
            }
        }
    }

    @Override
    public void batchUnSubscribe(List<ConsumerConfig> configs) {
        // ????????????????????????????????????????????????
        for (ConsumerConfig config : configs) {
            String appName = config.getAppName();
            try {
                unSubscribe(config);
            } catch (Exception e) {
                LOGGER.errorWithApp(appName, "Error when batch unSubscribe", e);
            }
        }
    }

    @Override
    public void destroy() {
        // ?????????????????????
        // LocalRegistryHelper.backup(regFile, memoryCache);
        try {
            if (scheduledExecutorService != null) {
                scheduledExecutorService.shutdown();
                scheduledExecutorService = null;
            }
        } catch (Throwable t) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(t.getMessage(), t);
            }
        }
    }

    /**
     * Notify consumer.
     *
     * @param newCache the new cache
     */
    void notifyConsumer(Map<String, ProviderGroup> newCache) {
        Map<String, ProviderGroup> oldCache = memoryCache;
        // ????????????map?????????
        MapDifference<String, ProviderGroup> difference =
                new MapDifference<String, ProviderGroup>(newCache, oldCache);
        // ?????????????????????????????????
        Map<String, ProviderGroup> onlynew = difference.entriesOnlyOnLeft();
        for (Map.Entry<String, ProviderGroup> entry : onlynew.entrySet()) {
            notifyConsumerListeners(entry.getKey(), entry.getValue());
        }
        // ???????????????????????????????????????
        Map<String, ProviderGroup> onlyold = difference.entriesOnlyOnRight();
        for (Map.Entry<String, ProviderGroup> entry : onlyold.entrySet()) {
            notifyConsumerListeners(entry.getKey(), new ProviderGroup());
        }

        // ??????????????????????????????
        Map<String, ValueDifference<ProviderGroup>> changed = difference.entriesDiffering();
        for (Map.Entry<String, ValueDifference<ProviderGroup>> entry : changed.entrySet()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{} has differente", entry.getKey());
            }
            ValueDifference<ProviderGroup> differentValue = entry.getValue();
            ProviderGroup innew = differentValue.leftValue();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("new(right) is {}", innew);
            }
            // ???????????????????????????
            notifyConsumerListeners(entry.getKey(), innew);
        }
    }

    private void notifyConsumerListeners(String serviceName, ProviderGroup providerGroup) {
        List<ConsumerConfig> consumerConfigs = notifyListeners.get(serviceName);
        if (consumerConfigs != null) {
            for (ConsumerConfig config : consumerConfigs) {
                ProviderInfoListener listener = config.getProviderInfoListener();
                if (listener != null) {
                    listener.updateProviders(providerGroup); // ????????????
                }
            }
        }
    }
}

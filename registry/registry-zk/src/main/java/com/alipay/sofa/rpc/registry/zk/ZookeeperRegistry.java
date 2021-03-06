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
package com.alipay.sofa.rpc.registry.zk;

import com.alipay.sofa.rpc.client.ProviderGroup;
import com.alipay.sofa.rpc.client.ProviderInfo;
import com.alipay.sofa.rpc.common.utils.CommonUtils;
import com.alipay.sofa.rpc.common.utils.StringUtils;
import com.alipay.sofa.rpc.config.AbstractInterfaceConfig;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.config.ProviderConfig;
import com.alipay.sofa.rpc.config.RegistryConfig;
import com.alipay.sofa.rpc.context.RpcRunningState;
import com.alipay.sofa.rpc.core.exception.SofaRpcRuntimeException;
import com.alipay.sofa.rpc.event.ConsumerSubEvent;
import com.alipay.sofa.rpc.event.EventBus;
import com.alipay.sofa.rpc.event.ProviderPubEvent;
import com.alipay.sofa.rpc.ext.Extension;
import com.alipay.sofa.rpc.listener.ConfigListener;
import com.alipay.sofa.rpc.listener.ProviderInfoListener;
import com.alipay.sofa.rpc.log.LogCodes;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;
import com.alipay.sofa.rpc.registry.Registry;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.AuthInfo;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.alipay.sofa.rpc.common.utils.StringUtils.CONTEXT_SEP;
import static com.alipay.sofa.rpc.registry.zk.ZookeeperRegistryHelper.buildConfigPath;
import static com.alipay.sofa.rpc.registry.zk.ZookeeperRegistryHelper.buildConsumerPath;
import static com.alipay.sofa.rpc.registry.zk.ZookeeperRegistryHelper.buildOverridePath;
import static com.alipay.sofa.rpc.registry.zk.ZookeeperRegistryHelper.buildProviderPath;

/**
 * <p>?????????Zookeeper????????????,?????????????????????<br>
 * 1.?????????????????????????????????????????????????????????????????????<br>
 * 2.??????zk??????????????????????????????????????????<br>
 * 3.????????????????????????????????????????????????<br>
 * 4.???????????????????????????????????????recover??????<br><br>
 * <pre>
 *  ???zookeeper??????????????????????????????
 *  -$rootPath (?????????)
 *         ???--sofa-rpc
 *             |--com.alipay.sofa.rpc.example.HelloService ????????????
 *             |       |-providers ???????????????????????????
 *             |       |     |--bolt://192.168.1.100:22000?xxx=yyy [1]
 *             |       |     |--bolt://192.168.1.110:22000?xxx=yyy [1]
 *             |       |     ???--bolt://192.168.1.120?xxx=yyy [1]
 *             |       |-consumers ???????????????????????????
 *             |       |     |--bolt://192.168.3.100?xxx=yyy []
 *             |       |     |--bolt://192.168.3.110?xxx=yyy []
 *             |       |     ???--bolt://192.168.3.120?xxx=yyy []
 *             |       |-configs ?????????????????????
 *             |       |     |--invoke.blacklist ["xxxx"]
 *             |       |     ???--monitor.open ["true"]
 *             |       ???overrides ???IP????????????
 *             |       |     ???--bolt://192.168.3.100?xxx=yyy []
 *             |--com.alipay.sofa.rpc.example.EchoService ?????????????????????
 *             | ......
 *  </pre>
 * </p>
 *
 * @author <a href=mailto:zhanggeng.zg@antfin.com>GengZhang</a>
 */
@Extension("zookeeper")
public class ZookeeperRegistry extends Registry {

    public static final String  EXT_NAME = "ZookeeperRegistry";

    /**
     * slf4j Logger for this class
     */
    private final static Logger LOGGER   = LoggerFactory.getLogger(ZookeeperRegistry.class);

    /**
     * ??????????????????
     *
     * @param registryConfig ??????????????????
     */
    protected ZookeeperRegistry(RegistryConfig registryConfig) {
        super(registryConfig);
    }

    /**
     * ??????????????????????????????
     */
    public final static String                          PARAM_PREFER_LOCAL_FILE = "preferLocalFile";

    /**
     * ???????????????????????????????????????<br>
     * ????????????????????????????????????????????????????????????zookeeper????????????????????????????????????????????????????????????????????????????????????<br>
     * ??????????????????zookeeper?????????????????????????????????????????????????????????????????????<br>
     * ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????<br>
     * ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     */
    public final static String                          PARAM_CREATE_EPHEMERAL  = "createEphemeral";
    /**
     * ???????????????
     */
    private final static byte[]                         PROVIDER_OFFLINE        = new byte[] { 0 };
    /**
     * ??????????????????
     */
    private final static byte[]                         PROVIDER_ONLINE         = new byte[] { 1 };

    /**
     * Zookeeper zkClient
     */
    private CuratorFramework                            zkClient;

    /**
     * Root path of registry data
     */
    private String                                      rootPath;

    /**
     * Prefer get data from local file to remote zk cluster.
     *
     * @see ZookeeperRegistry#PARAM_PREFER_LOCAL_FILE
     */
    private boolean                                     preferLocalFile         = false;

    /**
     * Create EPHEMERAL node when true, otherwise PERSISTENT
     *
     * @see ZookeeperRegistry#PARAM_CREATE_EPHEMERAL
     * @see CreateMode#PERSISTENT
     * @see CreateMode#EPHEMERAL
     */
    private boolean                                     ephemeralNode           = true;

    /**
     * ???????????????????????????
     */
    private ZookeeperConfigObserver                     configObserver;

    /**
     * IP?????????????????????
     */
    private ZookeeperOverrideObserver                   overrideObserver;

    /**
     * ?????????????????????
     */
    private ZookeeperProviderObserver                   providerObserver;

    /**
     * ????????????????????????url
     */
    private ConcurrentMap<ProviderConfig, List<String>> providerUrls            = new ConcurrentHashMap<ProviderConfig, List<String>>();

    /**
     * ????????????????????????url
     */
    private ConcurrentMap<ConsumerConfig, String>       consumerUrls            = new ConcurrentHashMap<ConsumerConfig, String>();

    @Override
    public synchronized void init() {
        if (zkClient != null) {
            return;
        }
        String addressInput = registryConfig.getAddress(); // xxx:2181,yyy:2181/path1/paht2
        if (StringUtils.isEmpty(addressInput)) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_EMPTY_ADDRESS, EXT_NAME));
        }
        int idx = addressInput.indexOf(CONTEXT_SEP);
        String address; // IP??????
        if (idx > 0) {
            address = addressInput.substring(0, idx);
            rootPath = addressInput.substring(idx);
            if (!rootPath.endsWith(CONTEXT_SEP)) {
                rootPath += CONTEXT_SEP; // ?????????"/"??????
            }
        } else {
            address = addressInput;
            rootPath = CONTEXT_SEP;
        }
        preferLocalFile = !CommonUtils.isFalse(registryConfig.getParameter(PARAM_PREFER_LOCAL_FILE));
        ephemeralNode = !CommonUtils.isFalse(registryConfig.getParameter(PARAM_CREATE_EPHEMERAL));
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                "Init ZookeeperRegistry with address {}, root path is {}. preferLocalFile:{}, ephemeralNode:{}",
                address, rootPath, preferLocalFile, ephemeralNode);
        }
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        CuratorFrameworkFactory.Builder zkClientuilder = CuratorFrameworkFactory.builder()
            .connectString(address)
            .sessionTimeoutMs(registryConfig.getConnectTimeout() * 3)
            .connectionTimeoutMs(registryConfig.getConnectTimeout())
            .canBeReadOnly(false)
            .retryPolicy(retryPolicy)
            .defaultData(null);

        //??????????????????zk???????????????
        List<AuthInfo> authInfos = buildAuthInfo();
        if (CommonUtils.isNotEmpty(authInfos)) {
            zkClientuilder = zkClientuilder.aclProvider(getDefaultAclProvider())
                .authorization(authInfos);
        }

        zkClient = zkClientuilder.build();

        zkClient.getConnectionStateListenable().addListener(new ConnectionStateListener() {
            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState) {

                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("reconnect to zookeeper,recover provider and consumer data");
                }
                if (newState == ConnectionState.RECONNECTED) {
                    recoverRegistryData();
                }
            }
        });
    }

    //recover data when connect with zk again.

    protected void recoverRegistryData() {

        for (ProviderConfig providerConfig : providerUrls.keySet()) {
            registerProviderUrls(providerConfig);
        }

        for (ConsumerConfig consumerConfig : consumerUrls.keySet()) {
            subscribeConsumerUrls(consumerConfig);
        }

    }

    @Override
    public synchronized boolean start() {
        if (zkClient == null) {
            LOGGER.warn("Start zookeeper registry must be do init first!");
            return false;
        }
        if (zkClient.getState() == CuratorFrameworkState.STARTED) {
            return true;
        }
        try {
            zkClient.start();
        } catch (Exception e) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_ZOOKEEPER_CLIENT_START), e);
        }
        return zkClient.getState() == CuratorFrameworkState.STARTED;
    }

    @Override
    public void destroy() {
        closePathChildrenCache(INTERFACE_CONFIG_CACHE);
        closePathChildrenCache(INTERFACE_OVERRIDE_CACHE);
        if (zkClient != null && zkClient.getState() == CuratorFrameworkState.STARTED) {
            zkClient.close();
        }
        providerUrls.clear();
        consumerUrls.clear();
    }

    @Override
    public void destroy(DestroyHook hook) {
        hook.preDestroy();
        destroy();
        hook.postDestroy();
    }

    /**
     * ????????????{ConsumerConfig???PathChildrenCache} <br>
     * ?????????{ConsumerConfig ??? PathChildrenCache }
     */
    private static final ConcurrentMap<ConsumerConfig, PathChildrenCache> INTERFACE_PROVIDER_CACHE = new ConcurrentHashMap<ConsumerConfig, PathChildrenCache>();

    /**
     * ????????????{?????????????????????PathChildrenCache} <br>
     * ?????????{/sofa-rpc/com.alipay.sofa.rpc.example/configs ??? PathChildrenCache }
     */
    private static final ConcurrentMap<String, PathChildrenCache>         INTERFACE_CONFIG_CACHE   = new ConcurrentHashMap<String, PathChildrenCache>();

    /**
     * IP??????{?????????????????????PathChildrenCache} <br>
     * ?????????{/sofa-rpc/com.alipay.sofa.rpc.example/overrides ??? PathChildrenCache }
     */
    private static final ConcurrentMap<String, PathChildrenCache>         INTERFACE_OVERRIDE_CACHE = new ConcurrentHashMap<String, PathChildrenCache>();

    @Override
    public void register(ProviderConfig config) {
        String appName = config.getAppName();
        if (!registryConfig.isRegister()) {
            if (LOGGER.isInfoEnabled(appName)) {
                LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_REGISTRY_IGNORE));
            }
            return;
        }

        //??????
        if (config.isRegister()) {
            registerProviderUrls(config);
        }

        if (config.isSubscribe()) {
            // ??????????????????
            if (!INTERFACE_CONFIG_CACHE.containsKey(buildConfigPath(rootPath, config))) {
                //?????????????????????
                subscribeConfig(config, config.getConfigListener());
            }
        }
    }

    /***
     * ?????? ????????????
     * @param config
     * @return
     * @throws Exception
     */
    protected void registerProviderUrls(ProviderConfig config) {
        String appName = config.getAppName();

        // ?????????????????????
        try {
            // ??????????????????
            List<String> urls;
            if (providerUrls.containsKey(config)) {
                urls = providerUrls.get(config);
            } else {
                urls = ZookeeperRegistryHelper.convertProviderToUrls(config);
                providerUrls.put(config, urls);
            }
            if (CommonUtils.isNotEmpty(urls)) {

                String providerPath = buildProviderPath(rootPath, config);
                if (LOGGER.isInfoEnabled(appName)) {
                    LOGGER.infoWithApp(appName,
                        LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_PUB_START, providerPath));
                }
                for (String url : urls) {
                    url = URLEncoder.encode(url, "UTF-8");
                    String providerUrl = providerPath + CONTEXT_SEP + url;

                    try {
                        getAndCheckZkClient().create().creatingParentContainersIfNeeded()
                            .withMode(ephemeralNode ? CreateMode.EPHEMERAL : CreateMode.PERSISTENT) // ??????????????????
                            .forPath(providerUrl, config.isDynamic() ? PROVIDER_ONLINE : PROVIDER_OFFLINE); // ?????????????????????
                        if (LOGGER.isInfoEnabled(appName)) {
                            LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_PUB, providerUrl));
                        }
                    } catch (KeeperException.NodeExistsException nodeExistsException) {
                        if (LOGGER.isWarnEnabled(appName)) {
                            LOGGER.warnWithApp(appName,
                                "provider has exists in zookeeper, provider=" + providerUrl);
                        }
                    }
                }

                if (LOGGER.isInfoEnabled(appName)) {
                    LOGGER.infoWithApp(appName,
                        LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_PUB_OVER, providerPath));
                }

            }
        } catch (SofaRpcRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_REG_PROVIDER, "zookeeperRegistry",
                config.buildKey()), e);
        }
        if (EventBus.isEnable(ProviderPubEvent.class)) {
            ProviderPubEvent event = new ProviderPubEvent(config);
            EventBus.post(event);
        }
    }

    /**
     * ?????????????????????
     *
     * @param config   provider/consumer config
     * @param listener config listener
     */
    protected void subscribeConfig(final AbstractInterfaceConfig config, ConfigListener listener) {
        try {
            if (configObserver == null) { // ?????????
                configObserver = new ZookeeperConfigObserver();
            }
            configObserver.addConfigListener(config, listener);
            final String configPath = buildConfigPath(rootPath, config);
            // ????????????????????? ?????????????????????????????????????????????Data????????????
            PathChildrenCache pathChildrenCache = new PathChildrenCache(zkClient, configPath, true);
            pathChildrenCache.getListenable().addListener(new PathChildrenCacheListener() {
                @Override
                public void childEvent(CuratorFramework client1, PathChildrenCacheEvent event) throws Exception {
                    if (LOGGER.isDebugEnabled(config.getAppName())) {
                        LOGGER.debug("Receive zookeeper event: " + "type=[" + event.getType() + "]");
                    }
                    switch (event.getType()) {
                        case CHILD_ADDED: //?????????????????????
                            configObserver.addConfig(config, configPath, event.getData());
                            break;
                        case CHILD_REMOVED: //?????????????????????
                            configObserver.removeConfig(config, configPath, event.getData());
                            break;
                        case CHILD_UPDATED:// ?????????????????????
                            configObserver.updateConfig(config, configPath, event.getData());
                            break;
                        default:
                            break;
                    }
                }
            });
            pathChildrenCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
            INTERFACE_CONFIG_CACHE.put(configPath, pathChildrenCache);
            configObserver.updateConfigAll(config, configPath, pathChildrenCache.getCurrentData());
        } catch (Exception e) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_SUB_PROVIDER_CONFIG, EXT_NAME), e);
        }
    }

    /**
     * ??????IP???????????????????????????????????????????????????,??????????????????ConsumerConfig???????????????
     *
     * @param config   consumer config
     * @param listener config listener
     */
    protected void subscribeOverride(final ConsumerConfig config, ConfigListener listener) {
        try {
            if (overrideObserver == null) { // ?????????
                overrideObserver = new ZookeeperOverrideObserver();
            }
            overrideObserver.addConfigListener(config, listener);
            final String overridePath = buildOverridePath(rootPath, config);
            final AbstractInterfaceConfig registerConfig = getRegisterConfig(config);
            // ????????????????????? ?????????????????????????????????????????????Data????????????
            PathChildrenCache pathChildrenCache = new PathChildrenCache(zkClient, overridePath, true);
            pathChildrenCache.getListenable().addListener(new PathChildrenCacheListener() {
                @Override
                public void childEvent(CuratorFramework client1, PathChildrenCacheEvent event) throws Exception {
                    if (LOGGER.isDebugEnabled(config.getAppName())) {
                        LOGGER.debug("Receive zookeeper event: " + "type=[" + event.getType() + "]");
                    }
                    switch (event.getType()) {
                        case CHILD_ADDED: //??????IP?????????
                            overrideObserver.addConfig(config, overridePath, event.getData());
                            break;
                        case CHILD_REMOVED: //??????IP?????????
                            overrideObserver.removeConfig(config, overridePath, event.getData(), registerConfig);
                            break;
                        case CHILD_UPDATED:// ??????IP?????????
                            overrideObserver.updateConfig(config, overridePath, event.getData());
                            break;
                        default:
                            break;
                    }
                }
            });
            pathChildrenCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
            INTERFACE_OVERRIDE_CACHE.put(overridePath, pathChildrenCache);
            overrideObserver.updateConfigAll(config, overridePath, pathChildrenCache.getCurrentData());
        } catch (Exception e) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_SUB_PROVIDER_OVERRIDE, EXT_NAME), e);
        }
    }

    @Override
    public void unRegister(ProviderConfig config) {
        String appName = config.getAppName();
        if (!registryConfig.isRegister()) {
            // ?????????????????????
            if (LOGGER.isInfoEnabled(appName)) {
                LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_REGISTRY_IGNORE));
            }
            return;
        }
        // ????????????????????????
        if (config.isRegister()) {
            try {
                List<String> urls = providerUrls.remove(config);
                if (CommonUtils.isNotEmpty(urls)) {
                    String providerPath = buildProviderPath(rootPath, config);
                    for (String url : urls) {
                        url = URLEncoder.encode(url, "UTF-8");
                        getAndCheckZkClient().delete().forPath(providerPath + CONTEXT_SEP + url);
                    }
                    if (LOGGER.isInfoEnabled(appName)) {
                        LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_UNPUB,
                            providerPath, "1"));
                    }
                }
            } catch (Exception e) {
                if (!RpcRunningState.isShuttingDown()) {
                    throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_UNREG_PROVIDER, EXT_NAME), e);
                }
            }
        }
        // ?????????????????????
        if (config.isSubscribe()) {
            try {
                if (null != configObserver) {
                    configObserver.removeConfigListener(config);
                }
                if (null != overrideObserver) {
                    overrideObserver.removeConfigListener(config);
                }
            } catch (Exception e) {
                if (!RpcRunningState.isShuttingDown()) {
                    throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_UNSUB_PROVIDER_CONFIG, EXT_NAME),
                        e);
                }
            }
        }
    }

    @Override
    public void batchUnRegister(List<ProviderConfig> configs) {
        // ?????????????????????????????????????????????curator?????????
        for (ProviderConfig config : configs) {
            unRegister(config);
        }
    }

    @Override
    public List<ProviderGroup> subscribe(final ConsumerConfig config) {
        String appName = config.getAppName();
        if (!registryConfig.isSubscribe()) {
            // ?????????????????????
            if (LOGGER.isInfoEnabled(appName)) {
                LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_REGISTRY_IGNORE));
            }
            return null;
        }

        //?????????????????????
        subscribeConsumerUrls(config);

        if (config.isSubscribe()) {

            List<ProviderInfo> matchProviders;
            // ????????????
            if (!INTERFACE_CONFIG_CACHE.containsKey(buildConfigPath(rootPath, config))) {
                //?????????????????????
                subscribeConfig(config, config.getConfigListener());
            }
            if (!INTERFACE_OVERRIDE_CACHE.containsKey(buildOverridePath(rootPath, config))) {
                //??????IP?????????
                subscribeOverride(config, config.getConfigListener());
            }

            // ??????Providers??????
            try {
                if (providerObserver == null) { // ?????????
                    providerObserver = new ZookeeperProviderObserver();
                }
                final String providerPath = buildProviderPath(rootPath, config);
                if (LOGGER.isInfoEnabled(appName)) {
                    LOGGER.infoWithApp(appName, LogCodes.getLog(LogCodes.INFO_ROUTE_REGISTRY_SUB, providerPath));
                }
                PathChildrenCache pathChildrenCache = INTERFACE_PROVIDER_CACHE.get(config);
                if (pathChildrenCache == null) {
                    // ????????????????????? ?????????????????????????????????????????????Data????????????
                    ProviderInfoListener providerInfoListener = config.getProviderInfoListener();
                    providerObserver.addProviderListener(config, providerInfoListener);
                    // TODO ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                    pathChildrenCache = new PathChildrenCache(zkClient, providerPath, true);
                    final PathChildrenCache finalPathChildrenCache = pathChildrenCache;
                    pathChildrenCache.getListenable().addListener(new PathChildrenCacheListener() {
                        @Override
                        public void childEvent(CuratorFramework client1, PathChildrenCacheEvent event) throws Exception {
                            if (LOGGER.isDebugEnabled(config.getAppName())) {
                                LOGGER.debugWithApp(config.getAppName(),
                                    "Receive zookeeper event: " + "type=[" + event.getType() + "]");
                            }
                            switch (event.getType()) {
                                case CHILD_ADDED: //????????????provider
                                    providerObserver.addProvider(config, providerPath, event.getData(),
                                        finalPathChildrenCache.getCurrentData());
                                    break;
                                case CHILD_REMOVED: //????????????provider
                                    providerObserver.removeProvider(config, providerPath, event.getData(),
                                        finalPathChildrenCache.getCurrentData());
                                    break;
                                case CHILD_UPDATED: // ????????????Provider
                                    providerObserver.updateProvider(config, providerPath, event.getData(),
                                        finalPathChildrenCache.getCurrentData());
                                    break;
                                default:
                                    break;
                            }
                        }
                    });
                    pathChildrenCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
                    INTERFACE_PROVIDER_CACHE.put(config, pathChildrenCache);
                }
                List<ProviderInfo> providerInfos = ZookeeperRegistryHelper.convertUrlsToProviders(
                    providerPath, pathChildrenCache.getCurrentData());
                matchProviders = ZookeeperRegistryHelper.matchProviderInfos(config, providerInfos);
            } catch (Exception e) {
                throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_SUB_PROVIDER, EXT_NAME), e);
            }

            if (EventBus.isEnable(ConsumerSubEvent.class)) {
                ConsumerSubEvent event = new ConsumerSubEvent(config);
                EventBus.post(event);
            }

            return Collections.singletonList(new ProviderGroup().addAll(matchProviders));

        }
        return null;
    }

    /***
     * ??????
     * @param config
     */
    protected void subscribeConsumerUrls(ConsumerConfig config) {
        // ??????Consumer??????
        String url = null;
        if (config.isRegister()) {
            try {
                String consumerPath = buildConsumerPath(rootPath, config);
                if (consumerUrls.containsKey(config)) {
                    url = consumerUrls.get(config);
                } else {
                    url = ZookeeperRegistryHelper.convertConsumerToUrl(config);
                    consumerUrls.put(config, url);
                }
                String encodeUrl = URLEncoder.encode(url, "UTF-8");
                getAndCheckZkClient().create().creatingParentContainersIfNeeded()
                    .withMode(CreateMode.EPHEMERAL) // Consumer????????????
                    .forPath(consumerPath + CONTEXT_SEP + encodeUrl);

            } catch (KeeperException.NodeExistsException nodeExistsException) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("consumer has exists in zookeeper, consumer=" + url);
                }
            } catch (SofaRpcRuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_REG_CONSUMER_CONFIG, EXT_NAME), e);
            }
        }
    }

    @Override
    public void unSubscribe(ConsumerConfig config) {
        // ????????????????????????
        if (config.isRegister()) {
            try {
                String url = consumerUrls.remove(config);
                if (url != null) {
                    String consumerPath = buildConsumerPath(rootPath, config);
                    url = URLEncoder.encode(url, "UTF-8");
                    getAndCheckZkClient().delete().forPath(consumerPath + CONTEXT_SEP + url);
                }
            } catch (Exception e) {
                if (!RpcRunningState.isShuttingDown()) {
                    throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_UNREG_CONSUMER_CONFIG, EXT_NAME),
                        e);
                }
            }
        }
        // ?????????????????????
        if (config.isSubscribe()) {
            try {
                providerObserver.removeProviderListener(config);
            } catch (Exception e) {
                if (!RpcRunningState.isShuttingDown()) {
                    throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_UNSUB_PROVIDER_CONFIG, EXT_NAME),
                        e);
                }
            }
            try {
                configObserver.removeConfigListener(config);
            } catch (Exception e) {
                if (!RpcRunningState.isShuttingDown()) {
                    throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_UNSUB_CONSUMER_CONFIG, EXT_NAME),
                        e);
                }
            }
            PathChildrenCache childrenCache = INTERFACE_PROVIDER_CACHE.remove(config);
            if (childrenCache != null) {
                try {
                    childrenCache.close();
                } catch (Exception e) {
                    if (!RpcRunningState.isShuttingDown()) {
                        throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_UNSUB_CONSUMER_CONFIG,
                            EXT_NAME), e);
                    }
                }
            }
        }
    }

    @Override
    public void batchUnSubscribe(List<ConsumerConfig> configs) {
        // ?????????????????????????????????????????????curator?????????
        for (ConsumerConfig config : configs) {
            unSubscribe(config);
        }
    }

    protected CuratorFramework getZkClient() {
        return zkClient;
    }

    private CuratorFramework getAndCheckZkClient() {
        if (zkClient == null || zkClient.getState() != CuratorFrameworkState.STARTED) {
            throw new SofaRpcRuntimeException(LogCodes.getLog(LogCodes.ERROR_ZOOKEEPER_CLIENT_UNAVAILABLE));
        }
        return zkClient;
    }

    /**
     * ??????????????????
     *
     * @param config consumer config
     * @return
     */
    private AbstractInterfaceConfig getRegisterConfig(ConsumerConfig config) {
        String url = ZookeeperRegistryHelper.convertConsumerToUrl(config);
        String addr = url.substring(0, url.indexOf("?"));
        for (Map.Entry<ConsumerConfig, String> consumerUrl : consumerUrls.entrySet()) {
            if (consumerUrl.getValue().contains(addr)) {
                return consumerUrl.getKey();
            }
        }
        return null;
    }

    private void closePathChildrenCache(Map<String, PathChildrenCache> map) {
        for (Map.Entry<String, PathChildrenCache> entry : map.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.error(LogCodes.getLog(LogCodes.ERROR_CLOSE_PATH_CACHE), e);
            }
        }
    }

    /**
     * ???????????????AclProvider
     * @return
     */
    private ACLProvider getDefaultAclProvider() {
        return new ACLProvider() {
            @Override
            public List<ACL> getDefaultAcl() {
                return ZooDefs.Ids.CREATOR_ALL_ACL;
            }

            @Override
            public List<ACL> getAclForPath(String path) {
                return ZooDefs.Ids.CREATOR_ALL_ACL;
            }
        };
    }

    /**
     * ??????????????????
     * @return
     */
    private List<AuthInfo> buildAuthInfo() {
        List<AuthInfo> info = new ArrayList<AuthInfo>();

        String scheme = registryConfig.getParameter("scheme");

        //?????????????????????????????????????????????????????????addAuth=user1:paasswd1,user2:passwd2
        String addAuth = registryConfig.getParameter("addAuth");

        if (StringUtils.isNotEmpty(addAuth)) {
            String[] addAuths = addAuth.split(",");
            for (String singleAuthInfo : addAuths) {
                info.add(new AuthInfo(scheme, singleAuthInfo.getBytes()));
            }
        }

        return info;
    }
}
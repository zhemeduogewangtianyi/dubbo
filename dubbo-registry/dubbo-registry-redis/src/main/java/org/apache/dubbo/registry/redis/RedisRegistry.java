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
package org.apache.dubbo.registry.redis;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.redis.RedisClient;
import org.apache.dubbo.remoting.redis.jedis.ClusterRedisClient;
import org.apache.dubbo.remoting.redis.jedis.MonoRedisClient;
import org.apache.dubbo.remoting.redis.jedis.SentinelRedisClient;
import org.apache.dubbo.rpc.RpcException;

import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_REDIS;
import static org.apache.dubbo.common.constants.CommonConstants.MONO_REDIS;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.REDIS_CLIENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SENTINEL_REDIS;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RECONNECT_PERIOD;
import static org.apache.dubbo.registry.Constants.DEFAULT_SESSION_TIMEOUT;
import static org.apache.dubbo.registry.Constants.REGISTER;
import static org.apache.dubbo.registry.Constants.REGISTRY_RECONNECT_PERIOD_KEY;
import static org.apache.dubbo.registry.Constants.SESSION_TIMEOUT_KEY;
import static org.apache.dubbo.registry.Constants.UNREGISTER;

/**
 * RedisRegistry
 *
 * 使用Redis作为注册中心，其订阅发布实现方式与ZooKeeper不同。我们在Redis注册中心
 * 的数据结构中已经了解到，Redis订阅发布使用的是过期机制和publish/subscribe通道。服务提
 * 供者发布服务，首先会在Redis中创建一个key,然后在通道中发布一条register事件消息。 但服务的key写入Redis后，发布者需要周期性地刷新key过期时间，在RedisRegistry构造方
 * 法中会启动一个expireExecutor定时调度线程池，不断调用deferExpired()方法去延续key
 * 的超时时间。如果服务提供者服务宕机，没有续期，则key会因为超时而被Redis删除，服务
 * 也就会被认定为下线
 *
 */
public class RedisRegistry extends FailbackRegistry {

    private final static String DEFAULT_ROOT = "dubbo";

    private final ScheduledExecutorService expireExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryExpireTimer", true));

    private final ScheduledFuture<?> expireFuture;

    private final String root;

    private RedisClient redisClient;

    private final ConcurrentMap<String, Notifier> notifiers = new ConcurrentHashMap<>();

    private final int reconnectPeriod;

    private final int expirePeriod;

    private volatile boolean admin = false;

    public RedisRegistry(URL url) {
        super(url);
        String type = url.getParameter(REDIS_CLIENT_KEY, MONO_REDIS);
        if (SENTINEL_REDIS.equals(type)) {
            redisClient = new SentinelRedisClient(url);
        } else if (CLUSTER_REDIS.equals(type)) {
            redisClient = new ClusterRedisClient(url);
        } else {
            redisClient = new MonoRedisClient(url);
        }

        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }

        this.reconnectPeriod = url.getParameter(REGISTRY_RECONNECT_PERIOD_KEY, DEFAULT_REGISTRY_RECONNECT_PERIOD);
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        if (!group.endsWith(PATH_SEPARATOR)) {
            group = group + PATH_SEPARATOR;
        }
        this.root = group;

        this.expirePeriod = url.getParameter(SESSION_TIMEOUT_KEY, DEFAULT_SESSION_TIMEOUT);

        //创建一个一直更新生命周期的线程池
        this.expireFuture = expireExecutor.scheduleWithFixedDelay(() -> {
            try {
                //续期
                deferExpired(); // Extend the expiration time
            } catch (Throwable t) { // Defensive fault tolerance
                logger.error("Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
            }
        }, expirePeriod / 2, expirePeriod / 2, TimeUnit.MILLISECONDS);
    }

    private void deferExpired() {
        //获取本地缓存的所有已被注册的 key，遍历
        for (URL url : new HashSet<>(getRegistered())) {
            if (url.getParameter(DYNAMIC_KEY, true)) {
                //获取 key
                String key = toCategoryPath(url);
                //对 key 进行续期
                if (redisClient.hset(key, url.toFullString(), String.valueOf(System.currentTimeMillis() + expirePeriod)) == 1) {
                    //续期返回 1 ，册说明 key 过期或者被删除了，这次算重新发布，在通道中广播
                    redisClient.publish(key, REGISTER);
                }
            }
        }
        //如果是服务治理中心，则清理过期的 key
        if (admin) {
            clean();
        }
    }

    /**
     * 生产者注册，不断自我续期
     * 消费者订阅 RedisRegistry 接收变化通知
     * 服务治理中心(dubbo-admin) 订阅 RedisRegister 清理过期的 key，并且不断接收 RedisRegistry 的通知
     * */

    private void clean() {
        Set<String> keys = redisClient.scan(root + ANY_VALUE);
        if (CollectionUtils.isNotEmpty(keys)) {
            for (String key : keys) {
                Map<String, String> values = redisClient.hgetAll(key);
                if (CollectionUtils.isNotEmptyMap(values)) {
                    boolean delete = false;
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        URL url = URL.valueOf(entry.getKey());
                        if (url.getParameter(DYNAMIC_KEY, true)) {
                            long expire = Long.parseLong(entry.getValue());
                            if (expire < now) {
                                redisClient.hdel(key, entry.getKey());
                                delete = true;
                                if (logger.isWarnEnabled()) {
                                    logger.warn("Delete expired key: " + key + " -> value: " + entry.getKey() + ", expire: " + new Date(expire) + ", now: " + new Date(now));
                                }
                            }
                        }
                    }
                    if (delete) {
                        redisClient.publish(key, UNREGISTER);
                    }
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return redisClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            expireFuture.cancel(true);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            for (Notifier notifier : notifiers.values()) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            redisClient.destroy();
        } catch (Throwable t) {
            logger.warn("Failed to destroy the redis registry client. registry: " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
        }
        ExecutorUtil.gracefulShutdown(expireExecutor, expirePeriod);
    }

    @Override
    public void doRegister(URL url) {
        String key = toCategoryPath(url);
        String value = url.toFullString();
        //计算过期时间
        String expire = String.valueOf(System.currentTimeMillis() + expirePeriod);
        try {
            //向Redis中注册
            redisClient.hset(key, value, expire);
            //在通道中发布注册事件
            redisClient.publish(key, REGISTER);
        } catch (Throwable t) {
            throw new RpcException("Failed to register service to redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doUnregister(URL url) {
        String key = toCategoryPath(url);
        String value = url.toFullString();
        try {
            redisClient.hdel(key, value);
            redisClient.publish(key, UNREGISTER);
        } catch (Throwable t) {
            throw new RpcException("Failed to unregister service to redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        String service = toServicePath(url);
        Notifier notifier = notifiers.get(service);
        if (notifier == null) {
            Notifier newNotifier = new Notifier(service);
            notifiers.putIfAbsent(service, newNotifier);
            notifier = notifiers.get(service);
            if (notifier == newNotifier) {
                notifier.start();
            }
        }
        try {
            if (service.endsWith(ANY_VALUE)) {
                admin = true;
                Set<String> keys = redisClient.scan(service);
                if (CollectionUtils.isNotEmpty(keys)) {
                    Map<String, Set<String>> serviceKeys = new HashMap<>();
                    for (String key : keys) {
                        String serviceKey = toServicePath(key);
                        Set<String> sk = serviceKeys.computeIfAbsent(serviceKey, k -> new HashSet<>());
                        sk.add(key);
                    }
                    for (Set<String> sk : serviceKeys.values()) {
                        doNotify(sk, url, Collections.singletonList(listener));
                    }
                }
            } else {
                doNotify(redisClient.scan(service + PATH_SEPARATOR + ANY_VALUE), url, Collections.singletonList(listener));
            }
        } catch (Throwable t) {
            throw new RpcException("Failed to subscribe service from redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
    }

    private void doNotify(String key) {
        for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<>(getSubscribed()).entrySet()) {
            doNotify(Collections.singletonList(key), entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    private void doNotify(Collection<String> keys, URL url, Collection<NotifyListener> listeners) {
        if (keys == null || keys.isEmpty()
                || listeners == null || listeners.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<URL> result = new ArrayList<>();
        List<String> categories = Arrays.asList(url.getParameter(CATEGORY_KEY, new String[0]));
        String consumerService = url.getServiceInterface();
        for (String key : keys) {
            if (!ANY_VALUE.equals(consumerService)) {
                String providerService = toServiceName(key);
                if (!providerService.equals(consumerService)) {
                    continue;
                }
            }
            String category = toCategoryName(key);
            if (!categories.contains(ANY_VALUE) && !categories.contains(category)) {
                continue;
            }
            List<URL> urls = new ArrayList<>();
            Map<String, String> values = redisClient.hgetAll(key);
            if (CollectionUtils.isNotEmptyMap(values)) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    URL u = URL.valueOf(entry.getKey());
                    if (!u.getParameter(DYNAMIC_KEY, true)
                            || Long.parseLong(entry.getValue()) >= now) {
                        if (UrlUtils.isMatch(url, u)) {
                            urls.add(u);
                        }
                    }
                }
            }
            if (urls.isEmpty()) {
                urls.add(URLBuilder.from(url)
                        .setProtocol(EMPTY_PROTOCOL)
                        .setAddress(ANYHOST_VALUE)
                        .setPath(toServiceName(key))
                        .addParameter(CATEGORY_KEY, category)
                        .build());
            }
            result.addAll(urls);
            if (logger.isInfoEnabled()) {
                logger.info("redis notify: " + key + " = " + urls);
            }
        }
        if (CollectionUtils.isEmpty(result)) {
            return;
        }
        for (NotifyListener listener : listeners) {
            notify(url, listener, result);
        }
    }

    private String toServiceName(String categoryPath) {
        String servicePath = toServicePath(categoryPath);
        return servicePath.startsWith(root) ? servicePath.substring(root.length()) : servicePath;
    }

    private String toCategoryName(String categoryPath) {
        int i = categoryPath.lastIndexOf(PATH_SEPARATOR);
        return i > 0 ? categoryPath.substring(i + 1) : categoryPath;
    }

    private String toServicePath(String categoryPath) {
        int i;
        if (categoryPath.startsWith(root)) {
            i = categoryPath.indexOf(PATH_SEPARATOR, root.length());
        } else {
            i = categoryPath.indexOf(PATH_SEPARATOR);
        }
        return i > 0 ? categoryPath.substring(0, i) : categoryPath;
    }

    private String toServicePath(URL url) {
        return root + url.getServiceInterface();
    }

    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    private class NotifySub extends JedisPubSub {
        public NotifySub() {}

        @Override
        public void onMessage(String key, String msg) {
            if (logger.isInfoEnabled()) {
                logger.info("redis event: " + key + " = " + msg);
            }
            if (msg.equals(REGISTER)
                    || msg.equals(UNREGISTER)) {
                try {
                    doNotify(key);
                } catch (Throwable t) { // TODO Notification failure does not restore mechanism guarantee
                    logger.error(t.getMessage(), t);
                }
            }
        }

        @Override
        public void onPMessage(String pattern, String key, String msg) {
            onMessage(key, msg);
        }

        @Override
        public void onSubscribe(String key, int num) {
        }

        @Override
        public void onPSubscribe(String pattern, int num) {
        }

        @Override
        public void onUnsubscribe(String key, int num) {
        }

        @Override
        public void onPUnsubscribe(String pattern, int num) {
        }

    }

    private class Notifier extends Thread {

        private final String service;
        private final AtomicInteger connectSkip = new AtomicInteger();
        private final AtomicInteger connectSkipped = new AtomicInteger();

        private volatile boolean first = true;
        private volatile boolean running = true;
        private volatile int connectRandom;

        public Notifier(String service) {
            super.setDaemon(true);
            super.setName("DubboRedisSubscribe");
            this.service = service;
        }

        private void resetSkip() {
            connectSkip.set(0);
            connectSkipped.set(0);
            connectRandom = 0;
        }

        private boolean isSkip() {
            int skip = connectSkip.get(); // Growth of skipping times
            if (skip >= 10) { // If the number of skipping times increases by more than 10, take the random number
                if (connectRandom == 0) {
                    connectRandom = ThreadLocalRandom.current().nextInt(10);
                }
                skip = 10 + connectRandom;
            }
            if (connectSkipped.getAndIncrement() < skip) { // Check the number of skipping times
                return true;
            }
            connectSkip.incrementAndGet();
            connectSkipped.set(0);
            connectRandom = 0;
            return false;
        }

        @Override
        public void run() {
            while (running) {
                try {
                    if (!isSkip()) {
                        try {
                            if (!redisClient.isConnected()) {
                                continue;
                            }
                            try {
                                if (service.endsWith(ANY_VALUE)) {
                                    if (first) {
                                        first = false;
                                        Set<String> keys = redisClient.scan(service);
                                        if (CollectionUtils.isNotEmpty(keys)) {
                                            for (String s : keys) {
                                                doNotify(s);
                                            }
                                        }
                                        resetSkip();
                                    }
                                    redisClient.psubscribe(new NotifySub(), service);
                                } else {
                                    if (first) {
                                        first = false;
                                        doNotify(service);
                                        resetSkip();
                                    }
                                    redisClient.psubscribe(new NotifySub(), service + PATH_SEPARATOR + ANY_VALUE); // blocking
                                }
                            } catch (Throwable t) { // Retry another server
                                logger.warn("Failed to subscribe service from redis registry. registry: " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
                                // If you only have a single redis, you need to take a rest to avoid overtaking a lot of CPU resources
                                sleep(reconnectPeriod);
                            }
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                            sleep(reconnectPeriod);
                        }
                    }
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }

        public void shutdown() {
            try {
                running = false;
                redisClient.disconnect();
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

    }

}

package com.alibaba.nacos.core.cluster.lookup;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.utils.StringUtils;
import com.alibaba.nacos.common.utils.ExceptionUtil;
import com.alibaba.nacos.core.cluster.AbstractMemberLookup;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.MemberUtil;
import com.alibaba.nacos.core.utils.GlobalExecutor;
import com.alibaba.nacos.core.utils.Loggers;
import com.alibaba.nacos.sys.env.EnvUtil;
import com.alibaba.nacos.sys.utils.ApplicationUtils;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.params.SetParams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import org.springframework.util.Assert;

/**
 * Cluster.conf redis managed cluster member node addressing pattern. 
 * 注意事项: 
 * 启用 - 在启动配置文件中,配置
 * nacos.core.member.lookup.type=redis 
 * 
 * 设置发送心跳间隔时间 - 在启动配置文件中,配置 
 * nacos.core.redis.sync.delay=
 * 单位ms,如果不配置,默认5000ms
 * 
 * 
 * 设置超时剔除集群时间 - 在启动配置文件中,配置 
 * nacos.core.redis.node.expire=
 * 单位ms,如果不配置,默认3分钟(3 * 60 * 1000ms).
 * 
 * @author gongran
 */
public class RedisMemberLookup extends AbstractMemberLookup {

    private JedisCluster jedisCluster;

    private long syncTaskDelay = 0L;

    private long serverNodeExpire = 0L;

    private static final String NACOS_CLUSTER_KEY = "nacos:core:cluster:info";

    private static final String EVICT_LOCK_KEY = "nacos:core:redis:lock";

    private static final long DEFAULT_SYNC_TASK_DELAY_MS = 5_000L;

    /**
     * 默认3分钟过期
     */
    private static final long DEFAULT_SERVER_NODE_EXPIRE_TIME_MS = 3 * 60 * 1000L;

    private static final int EVICT_LOCK_EXPIRE_SECOND = 30;
    
    /**
     * 发送心跳间隔
     */
    private static final String REDIS_SYNC_TASK_DELAY_PROPERTY = "nacos.core.redis.sync.delay";
    
    /**
     * 超时剔除集群时间
     */
    private static final String SERVER_NODE_EXPIRE_PROPERTY = "nacos.core.redis.node.expire";

    @Override
    protected void doStart() throws NacosException {
        // instantiate redis client.
        JedisCluster jedis = ApplicationUtils.getApplicationContext().getBean(JedisCluster.class);
        this.jedisCluster = jedis;

        String syncDelayConf = EnvUtil.getProperty(REDIS_SYNC_TASK_DELAY_PROPERTY);
        if (!StringUtils.isBlank(syncDelayConf)) {
            syncTaskDelay = Long.parseLong(syncDelayConf);
        }

        String nodeExpireConf = EnvUtil.getProperty(SERVER_NODE_EXPIRE_PROPERTY);
        if (!StringUtils.isBlank(nodeExpireConf)) {
            serverNodeExpire = Long.parseLong(nodeExpireConf);
        }

        run();
    }

    @Override
    protected void doDestroy() throws NacosException {
        // deregister from redis
        String localAddress = EnvUtil.getLocalAddress();
        jedisCluster.zrem(NACOS_CLUSTER_KEY, localAddress);
    }

    @Override
    public boolean useAddressServer() {
        return false;
    }

    private void run() {
        doRegisterOrUpdate();
        readClusterConfFromRedis();
        GlobalExecutor.scheduleByCommon(new RedisServerSyncTask(),
            syncTaskDelay > 0L ? syncTaskDelay : DEFAULT_SYNC_TASK_DELAY_MS);
        GlobalExecutor.scheduleByCommon(new RedisServerEvictTask(), getNextMinuteTimeIntervalInMillis());
    }

    /**
     * register current node info to redis server.
     */
    private void doRegisterOrUpdate() {
        Objects.requireNonNull(jedisCluster, "Redis client in RedisMemberLookup does not instantiated");
        // 192.168.7.43:8848
        String localAddress = EnvUtil.getLocalAddress();
        double timestamp = (double)System.currentTimeMillis();
        jedisCluster.zadd(NACOS_CLUSTER_KEY, timestamp, localAddress);
    }

    /**
     * read cluster node info from redis,parse infos into member,invoke afterLookup.
     */
    private void readClusterConfFromRedis() {
        Collection<Member> tmpMembers = new ArrayList<>();
        try {
            Set<String> tmp = jedisCluster.zrange(NACOS_CLUSTER_KEY, 0L, -1L);
            tmpMembers = MemberUtil.readServerConf(tmp);
        } catch (Throwable e) {
            Loggers.CLUSTER.error("nacos-XXXX [serverlist] failed to get serverlist from redis!, error : {}",
                e.getMessage());
        }
        afterLookup(tmpMembers);
    }

    private long getNextMinuteTimeIntervalInMillis() {
        long currentTimestamp = System.currentTimeMillis();
        long nextMinuteTimestamp = (currentTimestamp / (1000 * 60) + 1) * 1000 * 60;
        return nextMinuteTimestamp - currentTimestamp;
    }

    /**
     * schedule task: 1. update timestamp in redis. 2. readClusterConfFromRedis.
     */
    class RedisServerSyncTask implements Runnable {
        @Override
        public void run() {
            try {
                doRegisterOrUpdate();
                readClusterConfFromRedis();
            } catch (Throwable ex) {
                Loggers.CLUSTER.error("[serverlist] exception, error : {}", ExceptionUtil.getAllExceptionMsg(ex));
            } finally {
                GlobalExecutor.scheduleByCommon(this, syncTaskDelay > 0L ? syncTaskDelay : DEFAULT_SYNC_TASK_DELAY_MS);
            }
        }
    }

    class RedisServerEvictTask implements Runnable {
        final String REDIS_LOCK_SUCCESS = "OK";

        @Override
        public void run() {
            try {
                String lock = jedisCluster.set(EVICT_LOCK_KEY, EnvUtil.getLocalAddress(),
                    SetParams.setParams().ex(EVICT_LOCK_EXPIRE_SECOND).nx());
                if (REDIS_LOCK_SUCCESS.equalsIgnoreCase(lock)) {
                    long maxLease = serverNodeExpire > 0 ? serverNodeExpire : DEFAULT_SERVER_NODE_EXPIRE_TIME_MS;
                    long currentTimestamp = System.currentTimeMillis();
                    Set<Tuple> tuples = jedisCluster.zrangeWithScores(NACOS_CLUSTER_KEY, 0L, -1L);
                    String[] expireNodes = tuples.stream().filter(var -> {
                        long lastRenewTimestamp = (long)var.getScore();
                        return currentTimestamp - lastRenewTimestamp >= maxLease;
                    }).map(Tuple::getElement).toArray(String[]::new);
                    if (expireNodes.length > 0) {
                        jedisCluster.zrem(NACOS_CLUSTER_KEY, expireNodes);
                    }
                    jedisCluster.del(EVICT_LOCK_KEY);
                }
            } catch (Throwable ex) {
                Loggers.CLUSTER.error("RedisMemberLookup evict expire server node exception, error : {}",
                    ExceptionUtil.getAllExceptionMsg(ex));
            } finally {
                GlobalExecutor.scheduleByCommon(this, getNextMinuteTimeIntervalInMillis());
            }
        }
    }
}

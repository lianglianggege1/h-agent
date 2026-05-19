package com.h.backend.utils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson 工具类
 * 封装 Redisson 常用功能，提供分布式锁、分布式集合等操作
 * 所有 key 自动添加服务名前缀
 *
 */
@Slf4j
@Component
public class RedissonUtil {

    @Resource
    private RedissonClient redissonClient;

    @Value("${spring.application.name}")
    private String applicationName;

    /**
     * 构建带服务名前缀的 Redis key
     *
     * @param key 原始 key
     * @return 带前缀的 key，格式: {applicationName}:{key}
     */
    private String buildKey(String key) {
        if (key == null) {
            return null;
        }
        return applicationName + ":" + key;
    }

    // ============================== 分布式锁 ==============================

    /**
     * 获取锁（阻塞等待）
     * 默认等待时间：30秒，锁超时时间：10秒
     *
     * @param lockKey 锁的 key
     * @return 锁对象
     */
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(buildKey(lockKey));
    }

    /**
     * 尝试获取锁
     *
     * @param lockKey   锁的 key
     * @param waitTime  等待时间
     * @param leaseTime 锁超时时间
     * @param unit      时间单位
     * @return true-获取成功，false-获取失败
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            log.error("尝试获取锁被中断，lockKey: {}", lockKey, e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 尝试获取锁（不指定锁超时时间，使用看门狗自动续期）
     * 锁会一直有效，直到主动释放或服务宕机
     *
     * @param lockKey  锁的 key
     * @param waitTime 等待时间
     * @param unit     时间单位
     * @return true-获取成功，false-获取失败
     */
    public boolean tryLock(String lockKey, long waitTime, TimeUnit unit) {
        RLock lock = getLock(lockKey);
        try {
            return lock.tryLock(waitTime, unit);
        } catch (InterruptedException e) {
            log.error("尝试获取锁被中断，lockKey: {}", lockKey, e);
            Thread.currentThread().interrupt();
            return false;
        }
    }



    /**
     * 尝试获取锁（使用默认时间）
     * 等待时间：3秒，锁超时时间：10秒
     *
     * @param lockKey 锁的 key
     * @return true-获取成功，false-获取失败
     */
    public boolean tryLock(String lockKey) {
        return tryLock(lockKey, 3, 10, TimeUnit.SECONDS);
    }

    /**
     * 释放锁
     *
     * @param lockKey 锁的 key
     */
    public void unlock(String lockKey) {
        RLock lock = getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * 释放锁（安全方式）
     *
     * @param lock 锁对象
     */
    public void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.error("释放锁失败", e);
            }
        }
    }

    /**
     * 使用分布式锁执行业务逻辑（自动加锁和释放）
     *
     * @param lockKey   锁的 key
     * @param waitTime  等待时间
     * @param leaseTime 锁超时时间
     * @param unit      时间单位
     * @param supplier  业务逻辑
     * @param <T>       返回值类型
     * @return 业务逻辑的返回值
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> supplier) {
        RLock lock = getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, unit);
            if (!acquired) {
                log.warn("获取分布式锁失败，lockKey: {}", lockKey);
                return null;
            }
            return supplier.get();
        } catch (InterruptedException e) {
            log.error("获取锁被中断，lockKey: {}", lockKey, e);
            Thread.currentThread().interrupt();
            return null;
        } finally {
            unlock(lock);
        }
    }

    /**
     * 使用分布式锁执行业务逻辑（不指定锁超时时间，使用看门狗自动续期）
     *
     * @param lockKey  锁的 key
     * @param waitTime 等待时间
     * @param unit     时间单位
     * @param supplier 业务逻辑
     * @param <T>      返回值类型
     * @return 业务逻辑的返回值
     */
    public <T> T executeWithLock(String lockKey, long waitTime, TimeUnit unit, Supplier<T> supplier) {
        RLock lock = getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(waitTime, unit);
            if (!acquired) {
                log.warn("获取分布式锁失败，lockKey: {}", lockKey);
                return null;
            }
            return supplier.get();
        } catch (InterruptedException e) {
            log.error("获取锁被中断，lockKey: {}", lockKey, e);
            Thread.currentThread().interrupt();
            return null;
        } finally {
            unlock(lock);
        }
    }

    /**
     * 使用分布式锁执行业务逻辑（使用默认时间）
     * 等待时间：3秒，锁超时时间：10秒
     *
     * @param lockKey  锁的 key
     * @param supplier 业务逻辑
     * @param <T>      返回值类型
     * @return 业务逻辑的返回值
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
        return executeWithLock(lockKey, 3, 10, TimeUnit.SECONDS, supplier);
    }

    /**
     * 使用分布式锁执行业务逻辑（无返回值）
     *
     * @param lockKey   锁的 key
     * @param waitTime  等待时间
     * @param leaseTime 锁超时时间
     * @param unit      时间单位
     * @param runnable  业务逻辑
     */
    public void executeWithLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit, Runnable runnable) {
        executeWithLock(lockKey, waitTime, leaseTime, unit, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 使用分布式锁执行业务逻辑（无返回值，不指定锁超时时间，使用看门狗自动续期）
     *
     * @param lockKey  锁的 key
     * @param waitTime 等待时间
     * @param unit     时间单位
     * @param runnable 业务逻辑
     */
    public void executeWithLock(String lockKey, long waitTime, TimeUnit unit, Runnable runnable) {
        executeWithLock(lockKey, waitTime, unit, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 使用分布式锁执行业务逻辑（无返回值，使用默认时间）
     *
     * @param lockKey  锁的 key
     * @param runnable 业务逻辑
     */
    public void executeWithLock(String lockKey, Runnable runnable) {
        executeWithLock(lockKey, 3, 10, TimeUnit.SECONDS, runnable);
    }

    // ============================== 可重入锁 ==============================

    /**
     * 获取可重入锁（公平锁）
     *
     * @param lockKey 锁的 key
     * @return 公平锁对象
     */
    public RLock getFairLock(String lockKey) {
        return redissonClient.getFairLock(buildKey(lockKey));
    }

    // ============================== 读写锁 ==============================

    /**
     * 获取读写锁
     *
     * @param lockKey 锁的 key
     * @return 读写锁对象
     */
    public RReadWriteLock getReadWriteLock(String lockKey) {
        return redissonClient.getReadWriteLock(buildKey(lockKey));
    }

    /**
     * 获取读锁
     *
     * @param lockKey 锁的 key
     * @return 读锁对象
     */
    public RLock getReadLock(String lockKey) {
        return getReadWriteLock(lockKey).readLock();
    }

    /**
     * 获取写锁
     *
     * @param lockKey 锁的 key
     * @return 写锁对象
     */
    public RLock getWriteLock(String lockKey) {
        return getReadWriteLock(lockKey).writeLock();
    }

    // ============================== 信号量 ==============================

    /**
     * 获取信号量
     *
     * @param semaphoreKey 信号量的 key
     * @return 信号量对象
     */
    public RSemaphore getSemaphore(String semaphoreKey) {
        return redissonClient.getSemaphore(buildKey(semaphoreKey));
    }

    /**
     * 尝试获取信号量
     *
     * @param semaphoreKey 信号量的 key
     * @param permits      许可数量
     * @return true-获取成功，false-获取失败
     */
    public boolean tryAcquire(String semaphoreKey, int permits) {
        RSemaphore semaphore = getSemaphore(semaphoreKey);
        return semaphore.tryAcquire(permits);
    }

    /**
     * 释放信号量
     *
     * @param semaphoreKey 信号量的 key
     * @param permits      许可数量
     */
    public void releaseSemaphore(String semaphoreKey, int permits) {
        RSemaphore semaphore = getSemaphore(semaphoreKey);
        semaphore.release(permits);
    }

    // ============================== 计数器 ==============================

    /**
     * 获取计数器
     *
     * @param counterKey 计数器的 key
     * @return 计数器对象
     */
    public RCountDownLatch getCountDownLatch(String counterKey) {
        return redissonClient.getCountDownLatch(buildKey(counterKey));
    }

    /**
     * 设置计数器的值
     *
     * @param counterKey 计数器的 key
     * @param count      计数值
     */
    public void setCountDownLatch(String counterKey, long count) {
        RCountDownLatch latch = getCountDownLatch(counterKey);
        latch.trySetCount(count);
    }

    /**
     * 计数器减一
     *
     * @param counterKey 计数器的 key
     */
    public void countDown(String counterKey) {
        RCountDownLatch latch = getCountDownLatch(counterKey);
        latch.countDown();
    }

    /**
     * 等待计数器到0
     *
     * @param counterKey 计数器的 key
     * @param timeout    超时时间
     * @param unit       时间单位
     * @return true-计数完成，false-超时
     */
    public boolean await(String counterKey, long timeout, TimeUnit unit) {
        RCountDownLatch latch = getCountDownLatch(counterKey);
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            log.error("等待计数器被中断，counterKey: {}", counterKey, e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ============================== 分布式集合 ==============================

    /**
     * 获取分布式 Set
     *
     * @param key 键
     * @param <T> 元素类型
     * @return 分布式 Set
     */
    public <T> RSet<T> getSet(String key) {
        return redissonClient.getSet(buildKey(key));
    }

    /**
     * 获取分布式 List
     *
     * @param key 键
     * @param <T> 元素类型
     * @return 分布式 List
     */
    public <T> RList<T> getList(String key) {
        return redissonClient.getList(buildKey(key));
    }

    /**
     * 获取分布式 Map
     *
     * @param key 键
     * @param <K> Key 类型
     * @param <V> Value 类型
     * @return 分布式 Map
     */
    public <K, V> RMap<K, V> getMap(String key) {
        return redissonClient.getMap(buildKey(key));
    }

    /**
     * 获取分布式 Queue
     *
     * @param key 键
     * @param <T> 元素类型
     * @return 分布式 Queue
     */
    public <T> RQueue<T> getQueue(String key) {
        return redissonClient.getQueue(buildKey(key));
    }

    /**
     * 获取分布式 Deque（双端队列）
     *
     * @param key 键
     * @param <T> 元素类型
     * @return 分布式 Deque
     */
    public <T> RDeque<T> getDeque(String key) {
        return redissonClient.getDeque(buildKey(key));
    }

    /**
     * 获取分布式阻塞队列
     *
     * @param key 键
     * @param <T> 元素类型
     * @return 分布式阻塞队列
     */
    public <T> RBlockingQueue<T> getBlockingQueue(String key) {
        return redissonClient.getBlockingQueue(buildKey(key));
    }

    // ============================== 限流器 ==============================

    /**
     * 获取限流器
     *
     * @param rateLimiterKey 限流器的 key
     * @return 限流器对象
     */
    public RRateLimiter getRateLimiter(String rateLimiterKey) {
        return redissonClient.getRateLimiter(buildKey(rateLimiterKey));
    }

    /**
     * 设置限流器
     *
     * @param rateLimiterKey 限流器的 key
     * @param rate           速率（每单位时间允许通过的请求数）
     * @param rateInterval   速率间隔
     * @param unit           时间单位
     * @return true-设置成功，false-设置失败
     */
    public boolean setRateLimit(String rateLimiterKey, long rate, long rateInterval, RateIntervalUnit unit) {
        RRateLimiter rateLimiter = getRateLimiter(rateLimiterKey);
        return rateLimiter.trySetRate(RateType.OVERALL, rate, rateInterval, unit);
    }

    /**
     * 尝试获取令牌
     *
     * @param rateLimiterKey 限流器的 key
     * @return true-获取成功，false-获取失败
     */
    public boolean tryAcquireToken(String rateLimiterKey) {
        RRateLimiter rateLimiter = getRateLimiter(rateLimiterKey);
        return rateLimiter.tryAcquire();
    }

    /**
     * 尝试获取指定数量的令牌
     *
     * @param rateLimiterKey 限流器的 key
     * @param permits        令牌数量
     * @return true-获取成功，false-获取失败
     */
    public boolean tryAcquireToken(String rateLimiterKey, long permits) {
        RRateLimiter rateLimiter = getRateLimiter(rateLimiterKey);
        return rateLimiter.tryAcquire(permits);
    }

    // ============================== 布隆过滤器 ==============================

    /**
     * 获取布隆过滤器
     *
     * @param bloomFilterKey 布隆过滤器的 key
     * @param <T>            元素类型
     * @return 布隆过滤器对象
     */
    public <T> RBloomFilter<T> getBloomFilter(String bloomFilterKey) {
        return redissonClient.getBloomFilter(buildKey(bloomFilterKey));
    }

    /**
     * 初始化布隆过滤器
     *
     * @param bloomFilterKey      布隆过滤器的 key
     * @param expectedInsertions  预期插入元素数量
     * @param falseProbability    期望误判率（0-1之间的小数）
     * @param <T>                 元素类型
     * @return true-初始化成功，false-初始化失败
     */
    public <T> boolean initBloomFilter(String bloomFilterKey, long expectedInsertions, double falseProbability) {
        RBloomFilter<T> bloomFilter = getBloomFilter(bloomFilterKey);
        return bloomFilter.tryInit(expectedInsertions, falseProbability);
    }

    /**
     * 添加元素到布隆过滤器
     *
     * @param bloomFilterKey 布隆过滤器的 key
     * @param value          元素值
     * @param <T>            元素类型
     * @return true-添加成功，false-已存在
     */
    public <T> boolean addToBloomFilter(String bloomFilterKey, T value) {
        RBloomFilter<T> bloomFilter = getBloomFilter(bloomFilterKey);
        return bloomFilter.add(value);
    }

    /**
     * 判断元素是否可能存在于布隆过滤器中
     *
     * @param bloomFilterKey 布隆过滤器的 key
     * @param value          元素值
     * @param <T>            元素类型
     * @return true-可能存在，false-一定不存在
     */
    public <T> boolean mightContain(String bloomFilterKey, T value) {
        RBloomFilter<T> bloomFilter = getBloomFilter(bloomFilterKey);
        return bloomFilter.contains(value);
    }

    // ============================== 分布式对象 ==============================

    /**
     * 获取分布式原子Long
     *
     * @param key 键
     * @return 分布式原子Long
     */
    public RAtomicLong getAtomicLong(String key) {
        return redissonClient.getAtomicLong(buildKey(key));
    }

    /**
     * 获取分布式原子Double
     *
     * @param key 键
     * @return 分布式原子Double
     */
    public RAtomicDouble getAtomicDouble(String key) {
        return redissonClient.getAtomicDouble(buildKey(key));
    }

    /**
     * 获取分布式 Bucket（桶）
     *
     * @param key 键
     * @param <T> 元素类型
     * @return 分布式 Bucket
     */
    public <T> RBucket<T> getBucket(String key) {
        return redissonClient.getBucket(buildKey(key));
    }

    // ============================== 发布订阅 ==============================

    /**
     * 获取主题（用于发布订阅）
     *
     * @param topicName 主题名称
     * @param <T>       消息类型
     * @return 主题对象
     */
    public <T> RTopic getTopic(String topicName) {
        return redissonClient.getTopic(buildKey(topicName));
    }

    /**
     * 发布消息
     *
     * @param topicName 主题名称
     * @param message   消息
     * @param <T>       消息类型
     * @return 接收到消息的订阅者数量
     */
    public <T> long publish(String topicName, T message) {
        RTopic topic = getTopic(topicName);
        return topic.publish(message);
    }

    /**
     * 订阅消息
     *
     * @param topicName 主题名称
     * @param listener  消息监听器
     * @param <T>       消息类型
     * @return 监听器ID
     */
    public <T> int subscribe(String topicName, org.redisson.api.listener.MessageListener<T> listener) {
        RTopic topic = getTopic(topicName);
        return topic.addListener(Object.class, listener);
    }
}


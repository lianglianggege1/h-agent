package com.h.backend.shared.infrastructure.utils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis 工具类
 * 封装常用的 Redis 操作，提供简洁优雅的 API
 * 所有 key 自动添加服务名前缀
 *
 */
@Slf4j
@Component
public class RedisUtil {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

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
        return applicationName + "_" + key;
    }

    /**
     * 批量构建带服务名前缀的 Redis key
     *
     * @param keys 原始 key 数组
     * @return 带前缀的 key 列表
     */
    private List<String> buildKeys(String... keys) {
        if (keys == null || keys.length == 0) {
            return Collections.emptyList();
        }
        return Arrays.stream(keys)
                .map(this::buildKey)
                .collect(Collectors.toList());
    }

    // ============================== String 操作 ==============================

    /**
     * 设置缓存（永久）
     *
     * @param key   键
     * @param value 值
     * @return true-成功，false-失败
     */
    public boolean set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(buildKey(key), value);
            return true;
        } catch (Exception e) {
            log.error("Redis set 操作失败，key: {}", key, e);
            return false;
        }
    }

    /**
     * 设置缓存（带过期时间）
     *
     * @param key     键
     * @param value   值
     * @param timeout 过期时间（秒）
     * @return true-成功，false-失败
     */
    public boolean set(String key, Object value, long timeout) {
        return set(key, value, timeout, TimeUnit.SECONDS);
    }

    /**
     * 设置缓存（带过期时间和时间单位）
     *
     * @param key      键
     * @param value    值
     * @param timeout  过期时间
     * @param timeUnit 时间单位
     * @return true-成功，false-失败
     */
    public boolean set(String key, Object value, long timeout, TimeUnit timeUnit) {
        try {
            String fullKey = buildKey(key);
            if (timeout > 0) {
                redisTemplate.opsForValue().set(fullKey, value, timeout, timeUnit);
            } else {
                redisTemplate.opsForValue().set(fullKey, value);
            }
            return true;
        } catch (Exception e) {
            log.error("Redis set 操作失败，key: {}, timeout: {}", key, timeout, e);
            return false;
        }
    }

    /**
     * 如果 key 不存在则设置（SETNX），带过期时间
     *
     * @param key      键
     * @param value    值
     * @param timeout  过期时间
     * @param timeUnit 时间单位
     * @return true-设置成功（key不存在），false-设置失败（key已存在）
     */
    public Boolean setIfAbsent(String key, Object value, long timeout, TimeUnit timeUnit) {
        try {
            String fullKey = buildKey(key);
            return redisTemplate.opsForValue().setIfAbsent(fullKey, value, timeout, timeUnit);
        } catch (Exception e) {
            log.error("Redis setIfAbsent 操作失败，key: {}, timeout: {}", key, timeout, e);
            return false;
        }
    }

    /**
     * 获取缓存
     *
     * @param key 键
     * @return 值
     */
    public Object get(String key) {
        return key == null ? null : redisTemplate.opsForValue().get(buildKey(key));
    }

    /**
     * 获取缓存（泛型）
     *
     * @param key   键
     * @param clazz 类型
     * @param <T>   泛型
     * @return 值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object value = get(key);
        return value == null ? null : (T) value;
    }

    /**
     * 递增
     *
     * @param key   键
     * @param delta 递增步长
     * @return 递增后的值
     */
    public Long increment(String key, long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("递增因子必须大于0");
        }
        return redisTemplate.opsForValue().increment(buildKey(key), delta);
    }

    /**
     * 递增（步长为1）
     *
     * @param key 键
     * @return 递增后的值
     */
    public Long increment(String key) {
        return increment(key, 1);
    }

    /**
     * 递减
     *
     * @param key   键
     * @param delta 递减步长
     * @return 递减后的值
     */
    public Long decrement(String key, long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("递减因子必须大于0");
        }
        return redisTemplate.opsForValue().decrement(buildKey(key), delta);
    }

    /**
     * 递减（步长为1）
     *
     * @param key 键
     * @return 递减后的值
     */
    public Long decrement(String key) {
        return decrement(key, 1);
    }

    // ============================== Hash 操作 ==============================

    /**
     * 获取 Hash 中的值
     *
     * @param key  键
     * @param item Hash 键
     * @return 值
     */
    public Object hGet(String key, String item) {
        return redisTemplate.opsForHash().get(buildKey(key), item);
    }

    /**
     * 获取 Hash 中的值（泛型）
     *
     * @param key   键
     * @param item  Hash 键
     * @param clazz 类型
     * @param <T>   泛型
     * @return 值
     */
    @SuppressWarnings("unchecked")
    public <T> T hGet(String key, String item, Class<T> clazz) {
        Object value = hGet(key, item);
        return value == null ? null : (T) value;
    }

    /**
     * 获取整个 Hash
     *
     * @param key 键
     * @return Hash
     */
    public Map<Object, Object> hGetAll(String key) {
        return redisTemplate.opsForHash().entries(buildKey(key));
    }

    /**
     * 设置 Hash 缓存
     *
     * @param key 键
     * @param map Hash
     * @return true-成功，false-失败
     */
    public boolean hSetAll(String key, Map<String, Object> map) {
        try {
            redisTemplate.opsForHash().putAll(buildKey(key), map);
            return true;
        } catch (Exception e) {
            log.error("Redis hSetAll 操作失败，key: {}", key, e);
            return false;
        }
    }

    /**
     * 设置 Hash 缓存（带过期时间）
     *
     * @param key     键
     * @param map     Hash
     * @param timeout 过期时间（秒）
     * @return true-成功，false-失败
     */
    public boolean hSetAll(String key, Map<String, Object> map, long timeout) {
        try {
            String fullKey = buildKey(key);
            redisTemplate.opsForHash().putAll(fullKey, map);
            if (timeout > 0) {
                redisTemplate.expire(fullKey, timeout, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.error("Redis hSetAll 操作失败，key: {}, timeout: {}", key, timeout, e);
            return false;
        }
    }

    /**
     * 设置 Hash 中的单个值
     *
     * @param key   键
     * @param item  Hash 键
     * @param value 值
     * @return true-成功，false-失败
     */
    public boolean hSet(String key, String item, Object value) {
        try {
            redisTemplate.opsForHash().put(buildKey(key), item, value);
            return true;
        } catch (Exception e) {
            log.error("Redis hSet 操作失败，key: {}, item: {}", key, item, e);
            return false;
        }
    }

    /**
     * 设置 Hash 中的单个值（带过期时间）
     *
     * @param key     键
     * @param item    Hash 键
     * @param value   值
     * @param timeout 过期时间（秒）
     * @return true-成功，false-失败
     */
    public boolean hSet(String key, String item, Object value, long timeout) {
        try {
            String fullKey = buildKey(key);
            redisTemplate.opsForHash().put(fullKey, item, value);
            if (timeout > 0) {
                redisTemplate.expire(fullKey, timeout, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.error("Redis hSet 操作失败，key: {}, item: {}, timeout: {}", key, item, timeout, e);
            return false;
        }
    }

    /**
     * 删除 Hash 中的值
     *
     * @param key   键
     * @param items Hash 键（可以多个）
     */
    public void hDelete(String key, Object... items) {
        redisTemplate.opsForHash().delete(buildKey(key), items);
    }

    /**
     * 判断 Hash 中是否有该项的值
     *
     * @param key  键
     * @param item Hash 键
     * @return true-存在，false-不存在
     */
    public boolean hHasKey(String key, String item) {
        return redisTemplate.opsForHash().hasKey(buildKey(key), item);
    }

    /**
     * Hash 递增
     *
     * @param key   键
     * @param item  Hash 键
     * @param delta 递增步长
     * @return 递增后的值
     */
    public double hIncrement(String key, String item, double delta) {
        return redisTemplate.opsForHash().increment(buildKey(key), item, delta);
    }

    /**
     * Hash 递减
     *
     * @param key   键
     * @param item  Hash 键
     * @param delta 递减步长
     * @return 递减后的值
     */
    public double hDecrement(String key, String item, double delta) {
        return redisTemplate.opsForHash().increment(buildKey(key), item, -delta);
    }

    // ============================== Set 操作 ==============================

    /**
     * 获取 Set 中的所有值
     *
     * @param key 键
     * @return Set
     */
    public Set<Object> sGet(String key) {
        try {
            return redisTemplate.opsForSet().members(buildKey(key));
        } catch (Exception e) {
            log.error("Redis sGet 操作失败，key: {}", key, e);
            return new HashSet<>();
        }
    }

    /**
     * 判断 Set 中是否有该值
     *
     * @param key   键
     * @param value 值
     * @return true-存在，false-不存在
     */
    public boolean sHasKey(String key, Object value) {
        try {
            Boolean result = redisTemplate.opsForSet().isMember(buildKey(key), value);
            return result != null && result;
        } catch (Exception e) {
            log.error("Redis sHasKey 操作失败，key: {}", key, e);
            return false;
        }
    }

    /**
     * 将数据放入 Set
     *
     * @param key    键
     * @param values 值（可以多个）
     * @return 成功个数
     */
    public long sSet(String key, Object... values) {
        try {
            Long count = redisTemplate.opsForSet().add(buildKey(key), values);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.error("Redis sSet 操作失败，key: {}", key, e);
            return 0;
        }
    }

    /**
     * 将数据放入 Set（带过期时间）
     *
     * @param key     键
     * @param timeout 过期时间（秒）
     * @param values  值（可以多个）
     * @return 成功个数
     */
    public long sSetWithExpire(String key, long timeout, Object... values) {
        try {
            String fullKey = buildKey(key);
            Long count = redisTemplate.opsForSet().add(fullKey, values);
            if (timeout > 0) {
                redisTemplate.expire(fullKey, timeout, TimeUnit.SECONDS);
            }
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.error("Redis sSet 操作失败，key: {}, timeout: {}", key, timeout, e);
            return -1;
        }
    }

    /**
     * 获取 Set 的长度
     *
     * @param key 键
     * @return 长度
     */
    public long sGetSize(String key) {
        try {
            Long size = redisTemplate.opsForSet().size(buildKey(key));
            return size == null ? 0 : size;
        } catch (Exception e) {
            log.error("Redis sGetSize 操作失败，key: {}", key, e);
            return 0;
        }
    }

    /**
     * 移除 Set 中的值
     *
     * @param key    键
     * @param values 值（可以多个）
     * @return 移除的个数
     */
    public long sRemove(String key, Object... values) {
        try {
            Long count = redisTemplate.opsForSet().remove(buildKey(key), values);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.error("Redis sRemove 操作失败，key: {}", key, e);
            return 0;
        }
    }

    // ============================== List 操作 ==============================

    /**
     * 获取 List 中的内容
     *
     * @param key   键
     * @param start 开始
     * @param end   结束（0 到 -1 代表所有值）
     * @return List
     */
    public List<Object> lGet(String key, long start, long end) {
        try {
            return redisTemplate.opsForList().range(buildKey(key), start, end);
        } catch (Exception e) {
            log.error("Redis lGet 操作失败，key: {}", key, e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取 List 的长度
     *
     * @param key 键
     * @return 长度
     */
    public long lGetSize(String key) {
        try {
            Long size = redisTemplate.opsForList().size(buildKey(key));
            return size == null ? 0 : size;
        } catch (Exception e) {
            log.error("Redis lGetSize 操作失败，key: {}", key, e);
            return 0;
        }
    }

    /**
     * 通过索引获取 List 中的值
     *
     * @param key   键
     * @param index 索引（index>=0 时，0 表头，1 第二个元素；index<0 时，-1 表尾，-2 倒数第二个元素）
     * @return 值
     */
    public Object lGetIndex(String key, long index) {
        try {
            return redisTemplate.opsForList().index(buildKey(key), index);
        } catch (Exception e) {
            log.error("Redis lGetIndex 操作失败，key: {}, index: {}", key, index, e);
            return null;
        }
    }

    /**
     * 将值放入 List（右边）
     *
     * @param key   键
     * @param value 值
     * @return true-成功，false-失败
     */
    public boolean lSet(String key, Object value) {
        try {
            redisTemplate.opsForList().rightPush(buildKey(key), value);
            return true;
        } catch (Exception e) {
            log.error("Redis lSet 操作失败，key: {}", key, e);
            return false;
        }
    }

    /**
     * 将值放入 List（右边，带过期时间）
     *
     * @param key     键
     * @param value   值
     * @param timeout 过期时间（秒）
     * @return true-成功，false-失败
     */
    public boolean lSet(String key, Object value, long timeout) {
        try {
            String fullKey = buildKey(key);
            redisTemplate.opsForList().rightPush(fullKey, value);
            if (timeout > 0) {
                redisTemplate.expire(fullKey, timeout, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.error("Redis lSet 操作失败，key: {}, timeout: {}", key, timeout, e);
            return false;
        }
    }

    /**
     * 将 List 放入缓存（右边）
     *
     * @param key   键
     * @param value List
     * @return true-成功，false-失败
     */
    public boolean lSetAll(String key, List<Object> value) {
        try {
            redisTemplate.opsForList().rightPushAll(buildKey(key), value);
            return true;
        } catch (Exception e) {
            log.error("Redis lSetAll 操作失败，key: {}", key, e);
            return false;
        }
    }

    /**
     * 将 List 放入缓存（右边，带过期时间）
     *
     * @param key     键
     * @param value   List
     * @param timeout 过期时间（秒）
     * @return true-成功，false-失败
     */
    public boolean lSetAll(String key, List<Object> value, long timeout) {
        try {
            String fullKey = buildKey(key);
            redisTemplate.opsForList().rightPushAll(fullKey, value);
            if (timeout > 0) {
                redisTemplate.expire(fullKey, timeout, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.error("Redis lSetAll 操作失败，key: {}, timeout: {}", key, timeout, e);
            return false;
        }
    }

    /**
     * 从 List 左侧弹出一个元素（LPOP）
     *
     * @param key 键
     * @return 弹出的元素，若为空返回 null
     */
    public Object lLeftPop(String key) {
        try {
            return redisTemplate.opsForList().leftPop(buildKey(key));
        } catch (Exception e) {
            log.error("Redis lLeftPop 操作失败，key: {}", key, e);
            return null;
        }
    }

    /**
     * 根据索引修改 List 中的某条数据
     *
     * @param key   键
     * @param index 索引
     * @param value 值
     * @return true-成功，false-失败
     */
    public boolean lUpdateIndex(String key, long index, Object value) {
        try {
            redisTemplate.opsForList().set(buildKey(key), index, value);
            return true;
        } catch (Exception e) {
            log.error("Redis lUpdateIndex 操作失败，key: {}, index: {}", key, index, e);
            return false;
        }
    }

    /**
     * 移除 List 中 N 个值为 value 的元素
     *
     * @param key   键
     * @param count 移除多少个
     * @param value 值
     * @return 移除的个数
     */
    public long lRemove(String key, long count, Object value) {
        try {
            Long remove = redisTemplate.opsForList().remove(buildKey(key), count, value);
            return remove == null ? 0 : remove;
        } catch (Exception e) {
            log.error("Redis lRemove 操作失败，key: {}, count: {}", key, count, e);
            return 0;
        }
    }

    // ============================== 通用操作 ==============================

    /**
     * 指定缓存失效时间
     *
     * @param key     键
     * @param timeout 时间（秒）
     * @return true-成功，false-失败
     */
    public boolean expire(String key, long timeout) {
        return expire(key, timeout, TimeUnit.SECONDS);
    }

    /**
     * 指定缓存失效时间
     *
     * @param key      键
     * @param timeout  时间
     * @param timeUnit 时间单位
     * @return true-成功，false-失败
     */
    public boolean expire(String key, long timeout, TimeUnit timeUnit) {
        try {
            if (timeout > 0) {
                Boolean result = redisTemplate.expire(buildKey(key), timeout, timeUnit);
                return result != null && result;
            }
            return false;
        } catch (Exception e) {
            log.error("Redis expire 操作失败，key: {}, timeout: {}", key, timeout, e);
            return false;
        }
    }

    /**
     * 获取过期时间
     *
     * @param key 键
     * @return 时间（秒），返回 -1 代表永久有效，-2 代表键不存在
     */
    public long getExpire(String key) {
        Long expire = redisTemplate.getExpire(buildKey(key), TimeUnit.SECONDS);
        return expire == null ? -2 : expire;
    }

    /**
     * 判断 key 是否存在
     *
     * @param key 键
     * @return true-存在，false-不存在
     */
    public boolean hasKey(String key) {
        try {
            Boolean result = redisTemplate.hasKey(buildKey(key));
            return result != null && result;
        } catch (Exception e) {
            log.error("Redis hasKey 操作失败，key: {}", key, e);
            return false;
        }
    }

    /**
     * 删除缓存
     *
     * @param keys 键（可以传一个或多个）
     */
    public void delete(String... keys) {
        if (keys != null && keys.length > 0) {
            List<String> fullKeys = buildKeys(keys);
            if (fullKeys.size() == 1) {
                redisTemplate.delete(fullKeys.get(0));
            } else {
                redisTemplate.delete(fullKeys);
            }
        }
    }

    /**
     * 删除缓存
     *
     * @param keys 键集合
     */
    public void delete(Collection<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            List<String> fullKeys = keys.stream()
                    .map(this::buildKey)
                    .collect(Collectors.toList());
            redisTemplate.delete(fullKeys);
        }
    }

    /**
     * 获取匹配的所有键
     *
     * @param pattern 匹配模式（例如：user:*）
     * @return 键集合
     */
    public Set<String> keys(String pattern) {
        try {
            return redisTemplate.keys(buildKey(pattern));
        } catch (Exception e) {
            log.error("Redis keys 操作失败，pattern: {}", pattern, e);
            return new HashSet<>();
        }
    }

    // ============================== Lua 脚本操作 ==============================

    /**
     * 执行 Lua 脚本（自动添加服务名前缀）
     *
     * @param script  Lua 脚本
     * @param keys    Key 列表
     * @param args    参数列表
     * @param <T>     返回值类型
     * @return 执行结果
     */
    public <T> T executeScript(String script, List<String> keys, Object... args) {
        try {
            DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptText(script);
            // 注意：这里需要调用者指定返回类型，所以提供一个重载方法
            List<String> fullKeys = keys != null ? keys.stream().map(this::buildKey).collect(Collectors.toList()) : Collections.emptyList();
            return redisTemplate.execute(redisScript, fullKeys, args);
        } catch (Exception e) {
            log.error("Redis executeScript 操作失败，script: {}", script, e);
            return null;
        }
    }

    /**
     * 执行 Lua 脚本（不添加服务名前缀）
     * 用于需要全局唯一 key 的场景，如分布式锁、雪花ID WorkerId 租约等
     *
     * @param script      Lua 脚本
     * @param resultType  返回值类型
     * @param keys        Key 列表（不添加前缀）
     * @param args        参数列表
     * @param <T>         返回值类型
     * @return 执行结果
     */
    public <T> T executeScript(String script, Class<T> resultType, List<String> keys, Object... args) {
        try {
            DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptText(script);
            redisScript.setResultType(resultType);
            List<String> fullKeys = keys != null ? keys.stream().map(this::buildKey).toList() : Collections.emptyList();
            return redisTemplate.execute(redisScript, fullKeys, args);
        } catch (Exception e) {
            log.error("Redis executeScrip 操作失败，script: {}", script, e);
            return null;
        }
    }
}


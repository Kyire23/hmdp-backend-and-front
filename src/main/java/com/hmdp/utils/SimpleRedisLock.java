package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName: SimpleRedisLock
 * @Description: 实现Redis分布式锁
 * @Version: 1.0
 */


public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;  //业务的名称（也就是锁的名称）

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";

    /**
     * UUID是指在一台机器上生成的数字，它保证对在同一时空中的所有机器都是唯一的
     * UUID由以下几部分的组合：
     * （1）当前日期和时间，UUID的第一个部分与时间有关，如果你在生成一个UUID之后，过几秒又生成一个UUID，则第一个部分不同，其余相同。
     * （2）时钟序列。
     * （3）全局唯一的IEEE机器识别号，如果有网卡，从网卡MAC地址获得，没有网卡以其他方式获得。
     * 通过组成可以看出，首先每台机器的mac地址是不一样的，那么如果出现重复，可能是同一时间下生成的id可能相同，不会存在不同时间内生成重复的数据
    */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";   //线程标识前缀(区分集群下不同主机(不同JVM) 而ThreadID在jvm内部是自增生成的)

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("./lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识  (UUID:区分不同JVM + 线程id：区分同一个JVM中的不同线程)
        String threadId = ID_PREFIX + Thread.currentThread().getId();  //解决redis分布式锁误删问题
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //注意自动拆箱时success为空指针的可能性
        return Boolean.TRUE.equals(success);   //也是hutool的 BooleanUtil.isTrue() 的实现方法
    }

    /**
    *@Description:  Redis+Lua脚本 改善分布式锁原子性问题
    */
    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );
    }

    /**
    *@Description: 解决redis误删的旧方法 （ 无法保证原子性，存在线程安全问题 ）
    */
//    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//
//        //*********************** 由于此处是分开执行的，所以可能会导致判断一致后，在删除前出现阻塞，锁失效，新线程拿锁，旧线程继续执行删掉了新线程的锁（并发问题） ***********************************************************
//        /**  所以关键问题是确保 拿、验证、删除 的原子性操作  => redis+Lua脚本
//        */
//        //获取锁中标识并判断标识是否一致
////        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
////        if(threadId.equals(id)){
////            stringRedisTemplate.delete(KEY_PREFIX + name);
////        }
//        //**********************************************************************************
//    }
}

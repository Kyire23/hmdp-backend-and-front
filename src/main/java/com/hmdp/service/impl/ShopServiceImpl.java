package com.hmdp.service.impl;

import ch.qos.logback.core.pattern.color.RedCompositeConverter;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES, (id2) -> getById(id2));

        //互斥锁解决缓存击穿
        if (shop == null) {
            return Result.fail("店铺不存在!");
        }

        return Result.ok(shop);
    }


    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        boolean isWait = true;
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        Shop shop = null;
        //1,从redis查询商铺缓存
        try {
            while (isWait){
                // 1. 从redis中查询商铺缓存(命中)
                String cacheShopJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(cacheShopJson)){
                    shop = JSONUtil.toBean(cacheShopJson, Shop.class);
                    return shop;
                }

                //实现缓存重建
                //1. 获取互斥锁
                boolean isLock = tryLock(lockKey);
                //2.判断是否获取成功
                if(!isLock){
                    //2.1 失败，则休眠并重试
                    Thread.sleep(50);
                }else {
                    isWait = false;
                }
            }
            //2.2 成功
            //2.2.1 再次检查缓存，做doublecheck，防止是刚好前一个更新完缓存后放了锁，然后被你拿到了
            String cacheShopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(cacheShopJson)){
                shop = JSONUtil.toBean(cacheShopJson, Shop.class);
                unlock(lockKey);  //放锁
                return shop;
            }
            //2.2.2 根据id查询数据库
            shop = getById(id);
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            //释放互斥锁
            unlock(lockKey);
        }

        //返回
        return shop;

    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("Shop id is not null");
        }
        //1,更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}

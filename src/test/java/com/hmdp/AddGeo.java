package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
public class AddGeo {
    @Autowired
    private IShopService shopService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loadShopData() {
        // 1. 先从数据库查询出所有商铺
        //    这里查的是完整商铺表，目的是把后续“附近商铺查询”所需的坐标数据预先导入 Redis
        List<Shop> shopList = shopService.list();

        // 2. 按商铺类型分组
        //    分组后的结构：
        //    key   -> typeId（商铺类型）
        //    value -> 该类型下的所有商铺集合
        //
        //    为什么要按类型分组？
        //    因为后续用户查询“附近商户”时，往往是按分类查，例如：
        //    美食、KTV、酒店……
        //    所以提前按 typeId 拆分存储，可以让查询时直接命中对应分类的 GEO key，
        //    避免把所有类型混在一起再过滤，效率更高。
        Map<Long, List<Shop>> shopMap = shopList.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));

        // 3. 遍历每一个商铺类型分组，准备分别写入 Redis
        //
        //    这里为什么要用 shopMap.entrySet()？
        //    因为我们在遍历 Map 时，这里“同时需要 key 和 value”：
        //    - key   是 typeId，要用来拼接 Redis 的 GEO key
        //    - value 是该 typeId 下的商铺集合，要用来批量封装坐标数据
        //
        //    entrySet() 返回的是“键值对集合”，每个元素都是一个 Map.Entry<K,V>，
        //    也就是一组“键 + 值”。
        //
        //    它并不是说“Map 必须先获取键值对之后才能遍历”，这句话不准确。
        //    实际上 Map 有多种遍历方式：
        //    1. 遍历 keySet()：适合只需要 key 的情况
        //    2. 遍历 values()：适合只需要 value 的情况
        //    3. 遍历 entrySet()：适合同时需要 key 和 value 的情况
        //
        //    这里如果你改成 keySet()，也能写，但你还得再通过 key 去 map.get(key) 取 value，
        //    写法更绕，而且语义不如 entrySet() 直接。
        for (Map.Entry<Long, List<Shop>> entry : shopMap.entrySet()) {

            // 3.1 当前这组的类型 id
            Long typeId = entry.getKey();

            // 3.2 构造 Redis GEO 的 key
            //     例如：shop:geo:1、shop:geo:2
            //     本质上这个 key 对应的底层结构仍然是一个 ZSET
            String key = "shop:geo:" + typeId;

            // 3.3 取出当前类型下的所有商铺
            List<Shop> shops = entry.getValue();

            // 3.4 创建一个集合，用来批量封装 GeoLocation 对象
            //
            //     为什么这里要定义成：
            //     List<RedisGeoCommands.GeoLocation<String>> locations ？
            //
            //     因为后面调用的这个批量添加方法：
            //     stringRedisTemplate.opsForGeo().add(key, locations);
            //     它要求传入的第二个参数本身就是一个“GeoLocation 集合”。
            //
            //     也就是说，Redis 的 GEO 批量写入接口不是让你直接传：
            //     List<Shop>
            //     也不是让你直接传：
            //     List<Point>
            //     更不是传两个分离的列表（一个 id 列表 + 一个坐标列表）。
            //
            //     它需要的是：
            //     “每一个元素都已经被封装成一个标准的 GEO 位置对象”，
            //     然后再把这些对象组成一个集合，统一交给 add 方法。
            //
            //     这个集合里的泛型为什么是 String？
            //     因为 GeoLocation<String> 里的 String，表示 member/name 的类型。
            //     而这里我们存入 Redis GEO 的 member 是商铺 id 的字符串形式：
            //     shop.getId().toString()
            //     所以泛型要写 String。
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());

            // 3.5 把当前分类下的每个商铺都转成 Redis 能识别的 GEO 数据格式
            for (Shop shop : shops) {

                // RedisGeoCommands.GeoLocation<>(name, point)
                // 这个构造方法的两个参数含义分别是：
                //
                // 第一个参数：name / member
                // 表示这个地理位置条目的成员名。
                // 在当前业务里，我们把商铺 id 作为 member 存进去，
                // 这样后续查询附近商铺时，Redis 返回的就是商铺 id。
                //
                // 第二个参数：point
                // 表示这个成员对应的地理坐标点，也就是经纬度。
                // Point(shop.getX(), shop.getY()) 中：
                // - shop.getX() 是经度
                // - shop.getY() 是纬度
                //
                // 也就是说，一个 GeoLocation 对象本质上就是：
                // “某个 member 对应某个坐标点”的封装体。
                //
                // 正因为 add(key, locations) 需要的不是零散参数，
                // 而是一组标准化的“成员 + 坐标”对象，
                // 所以这里必须先封装成 GeoLocation 集合，最后才能批量写入。
                locations.add(
                        new RedisGeoCommands.GeoLocation<>(
                                shop.getId().toString(),   // member：商铺 ID
                                new Point(shop.getX(), shop.getY()) // point：经纬度坐标
                        )
                );
            }

            // 3.6 批量写入 Redis GEO
            //     这一句的含义是：
            //     把当前类型下的所有商铺位置，一次性加入到 key 对应的 GEO 集合中
            //
            //     底层本质：
            //     - key 还是 Redis 的 key
            //     - member 是商铺 id
            //     - score 是坐标转换后的 Geohash 数值
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }
}

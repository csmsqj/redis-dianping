package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;
    @Override
    // 关注或取关
    public Result follow(Long followUserId, Boolean isFollow) {

        if(UserHolder.getUser()==null) {
            return Result.fail("请先登录");
        }
        Long userId = UserHolder.getUser().getId();
        if(isFollow) {
            // 关注
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean b = save(follow);
            if(!b) {
                return Result.fail("关注失败");
            }
//后续还有共同关注，共同关注如果用数据库的话。那么要查多次表，还要求交集，是这样是很不好的，所以将其放入 REDIS 当中
            //由于要查交集，所以放在 set 数据结构当中
stringRedisTemplate.opsForSet().add("blog:follow:"+userId, followUserId.toString());
        }
        else{
            // 取关
            boolean b = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if(!b) {
                return Result.fail("取关失败");
            }
            // 取关成功后，删除 REDIS 中的记录
            stringRedisTemplate.opsForSet().remove("blog:follow:"+userId, followUserId.toString());
        }


        return Result.ok();
    }

    @Override
    // 查询是否关注
    public Result isFollow(Long followUserId) {
        Boolean b = stringRedisTemplate.opsForSet()
                .isMember("blog:follow:" + UserHolder.getUser().getId(), followUserId.toString());
        return Result.ok(BooleanUtil.isTrue(b));

    }

    @Override
    // 查询共同关注
    public Result followCommons(Long followUserId)
    {

String keyUser= "blog:follow:"+UserHolder.getUser().getId();
String keyFollow= "blog:follow:"+followUserId;
//集合集的键所对应的值，为元素封装到集合当中
        Set<String> set = stringRedisTemplate.opsForSet().intersect(keyUser, keyFollow);
        if(CollectionUtils.isEmpty(set)){
            //没有共同关注
            return Result.ok(Collections.emptyList());
        }
        //查询共同关注所以要根据关注的 ID 来查询用户信息，所以要将字符串转换为 Long 类型
        List<Long> list = set.stream().map((str) -> {
            return Long.valueOf(str);
        }).collect(Collectors.toList());

        List<UserDTO> collect = userService.listByIds(list).stream()
                .map(user -> {
                    //这里方法中第2个，如果说是写对象，那么就是把值复制给对应的对象之后，再把对象返回
                    //最好方法是直接糊涂包下的用 CLASS 直接返回对应的类型
                    return BeanUtil.copyProperties(user, UserDTO.class);
                }).collect(Collectors.toList());

        return Result.ok(collect);
    }
}

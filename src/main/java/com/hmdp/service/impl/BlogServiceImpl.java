package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
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
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private UserServiceImpl userService;
@Autowired
private StringRedisTemplate stringRedisTemplate;
@Autowired
private IFollowService followService;

    @Override
    // 根据id查询博客
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
return Result.fail("博客不存在");
        }
        //根据用户 ID 设置博客中用户所对应的信息
        queryUser(blog);
        //同时还要在 Redis 当中判断是否点赞,将其加入博客字段中
        isBlogLiked(blog);
        return Result.ok(blog);

    }

    // 是否点赞,将值给博客字段
    private void isBlogLiked(Blog blog) {
        if(UserHolder.getUser()==null){
            //如果没有登录，就直接返回
            return;
        }

        Long userId = UserHolder.getUser().getId();
        //点赞之后，在在 redis中查是否有该键值，对判断是否点过赞
        Boolean b = stringRedisTemplate.opsForZSet().score("blog:liked:" + blog.getId(), userId.toString()) != null;
if(BooleanUtil.isTrue(b)) {
blog.setIsLike(true);
}
else{
    blog.setIsLike(false);
}
}



    private void queryUser(Blog blog) {
        Long userId = blog.getUserId();
        User byId = userService.getById(userId);
        blog.setIcon(byId.getIcon());
        blog.setName(byId.getNickName());
    }

    @Override
    public Result likeBlog(Long id) {
        log.info("点赞博客，id={}", id);
        //判断用户是否已经点赞
        //通过 Redis 来查博客的 ID 作为键，用户 ID 作为值
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked:" + id, userId.toString());


        //如果没有点赞，就执行点赞操作；如果已经点赞，就执行取消点赞操作
if(score==null){
    //如果没有点赞，就执行点赞操作
    boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
    if(isSuccess){
        stringRedisTemplate.opsForZSet().add("blog:liked:" + id, userId.toString(),System.currentTimeMillis());
    }
}
else{
    //如果已经点赞，就执行取消点赞操作
    boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
    if(isSuccess){
        stringRedisTemplate.opsForZSet().remove("blog:liked:" + id, userId.toString());
    }
}

        return null;
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->
            queryUser(blog)
        );
        return Result.ok(records);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        log.info("查询博客点赞列表(按时间先后，从前往后排去)，id={}", id);
        Set<String> set = stringRedisTemplate.opsForZSet().range("blog:liked:" + id, 0, -1);
        //如果没人点赞，就直接返回空列表
        if(CollectionUtils.isEmpty(set)){
            return Result.ok(Collections.emptyList());
        }
        //将其转变为类型为Long类型的列表
        List<Long> list = set.stream().map(s -> Long.valueOf(s)).collect(Collectors.toList());
//需要的是用户的信息，所以要需要的是用户的 ID。根据用户 ID 列表来查询
        //这里不能直接传集合，因为集合的话传集合的默认是无序的，使用的是 IN，必须自己在后面指定顺序
        List<User> users = userService.query().in("id", list)
                .last("ORDER BY FIELD(id," + StrUtil.join(",", list) + ")").list();
        //将 User 转换为 UserDTO
        List<UserDTO> collect = users.stream().map(
                u -> BeanUtil.copyProperties(u, UserDTO.class)).collect(Collectors.toList());
return Result.ok(collect);
    }

    @Override
    //为了实现关注推送功能，在保存的时候，推送到粉丝的收件箱这里使用推模式
    //B 模型要求收件箱可以按时间戳排序，然后可以滚动分页，所以必须得用 Redis 相关的数据结构实现
    public Result saveBlog(Blog blog) {
        log.info("保存博客，blog={}", blog);
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean b = save(blog);
if(!b){
    return Result.fail("保存博客失败");
}
//查询所有的粉丝,之前的 redis结构为登录用户:用户关注的人，它的值为关注的人，为共同关注问题。这里查询粉丝得在粉丝表当中查
        List<Follow> followUserId = followService.query().eq("follow_user_id", user.getId()).list();
//以关注该博客的人的 ID 作为键，博客 ID 作为值,保存到 Redis，Redis 作为收件箱
        for (Follow follow : followUserId) {
            Long userId = follow.getUserId();
String key="blog:feed:"+userId;
//用户获取为滚动分页索引，用 Zset 数据结构,以现在时间为时间戳
stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    //根据前端的参数显示对应博客信息，按照时间戳前后进行排序
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //去收件箱所有的笔记实现滚动翻页
        //1在 Redis 当中获取当前用户
String key="blog:feed:"+UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        //如果没有数据了，就直接返回
if(CollectionUtils.isEmpty(typedTuples)) {
return  Result.ok(Collections.emptyList());
}
//解析数据：解析出blogId从而将笔记内容返回、同时要给前端返回偏移量,时间戳->偏移量
        ArrayList<Long> list = new ArrayList<Long>();
Integer cnt=1;
Long min=0L;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String value = typedTuple.getValue();
            Long vBlog= Long.valueOf(value);
list.add(vBlog);
            Long score = typedTuple.getScore().longValue();
            if(score.equals(min)){
                cnt++;
            }
            else{
                min=score;
                cnt=1;
            }

        }

        //根据博客 ID 查询博客内容,要求按照查询的顺序排序,因为 Redis 是以按时间戳排序的，那么加入列表当中也是有序的
        List<Blog> blogs = query().in("id", list)
                .last("ORDER BY FIELD(id," + StrUtil.join(",", list) + ")").list();
// 由于前端还需要博客视图信息和是否点赞的高亮,设置每篇博客的发布者视图信息和红心点赞展示高亮
        blogs.forEach(blog -> {
            this.queryUser(blog);
            this.isBlogLiked(blog);
        });
        ScrollResult r = new ScrollResult();
        r.setList(blogs);       // 业务层博客数据清单
        r.setOffset(cnt);        // 本轮推算出的抵消偏移值（赋给前端作下一次请求的 offset）
        r.setMinTime(min);  // 本轮推算出的最小底部锚点（赋给前端作下一次请求的 max）

        return Result.ok(r);
    }


}

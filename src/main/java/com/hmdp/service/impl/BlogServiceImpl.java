package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
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
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IFollowService followService;

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        //将博客id发送给粉丝
        // 1. 查粉丝 select * from tb_follow where follow_user_id = userId
        Long userId = UserHolder.getUser().getId();
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        //推送笔记id给所有粉丝
        for(Follow follow:follows){
            // 获取粉丝id
            Long fancyId = follow.getId();
            // 推送
            String key = RedisConstants.FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在！");
        }
        queryBlogUser(blog);
        // 查询blog是否被点赞了
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     *@Description: 判断博客是否被当前用户点了赞
     *@Param: [blog]
     *@return: void
     */
    private void isBlogLiked(Blog blog) {
        //因为首页也会调用这个函数，而查看首页时用户不一定会登录
        String key = RedisConstants.BLOG_LIKED_KEY+blog.getId();
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }

    /**
     *@Description: 查询博客的用户信息
     *@Param: [blog]
     *@return: void
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result likeBlog(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY+id;
        //判断当前登录用户是否点赞了
        UserDTO user = UserHolder.getUser();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        if(score == null){
            //未点赞，可以点赞
            // 修改点赞数量
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,user.getId().toString(),System.currentTimeMillis());
            }
        }else{
            //已点赞，取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, user.getId().toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY+id;
        //1,查询top5的点赞用户zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptySet());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        //3,根据用户id查询用户WHERE id IN(5,1)ORDER BY FIELD(id,5,1)
        String idStr = StrUtil.join(",",ids);

        List<UserDTO> userDTOS = userService.
        query().in("id",ids).last("ORDER BY FIELD(id,"+idStr+")").list()
        .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userID = UserHolder.getUser().getId();
        String key = RedisConstants.FEED_KEY+userID;
        //查询收件箱  ZREVRANGEBYSCORE key max 0 offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //解析数据：blogId、minTime(时间戳)、offset 分数值等于最小值时间所有元素的个数
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int offsetNew = 1;
        //一个或多个元组 ZSetOperations.TypedTuple
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            //时间戳
            long time = tuple.getScore().longValue();
            if(time == minTime) offsetNew++;
            else{
                minTime = time;
                offsetNew = 1;
            }
        }
        //根据id查blog
        String idstr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idstr + ")").list();
        blogs.forEach(blog -> {
            queryBlogUser(blog);
            // 查询blog是否被点赞了
            isBlogLiked(blog);}
        );
        return Result.ok(new ScrollResult(blogs,minTime,offsetNew));
    }
}
package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@Slf4j
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    //FOLLOW 为要关注的用户
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        log.info("要关注用户：{}，关注状态：{}", followUserId, isFollow);

        return followService.follow(followUserId, isFollow);
    }


    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
       log.info("查询是否被关注");

        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long followUserId) {
        log.info("查询共同关注");
        return followService.followCommons(followUserId);
    }



}

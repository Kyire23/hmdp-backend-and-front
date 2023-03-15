package com.hmdp.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


/**
*@Description: 逻辑过期 实体类
*/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedisData {
    //逻辑过期时间
    private LocalDateTime expireTime;
    private Object data;
}

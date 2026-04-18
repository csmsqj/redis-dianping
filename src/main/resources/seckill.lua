--[[Redis 秒杀的原本逻辑是通过数据库查询先判断库存，在判断在数据库订单表当中查询是否是一人一单,再进行扣减数据库库存，增加订单]]
--[[秒杀过程必须要快，它是高并发的，所以把判断库存放在 Redis 当中，判断是否一人一单也通过 Redis 来判断]]
local stockKey=KEYS[1]
local orderKey=KEYS[2]
local userId=ARGV[1]
--[[如果说存库不存在，返回零，代表秒杀失败]]
local stockvalue=redis.call('get', stockKey)
if(not stockvalur or tonumber(stockvalue)<=0) then
    return 0
end
--[[在判断一人一单，如果说 Redis 当中有，那么返回一代表已经重复购买]]
if(redis.call('sismember', orderKey, userId) == 1) then
    return 1
end
--[[扣减库存]]
redis.call('incrby', stockKey, -1)
--[[把用户添加到redis订单当中]]
redis.call('sadd', orderKey, userId)
return 2
-- 优惠卷秒杀lua脚本
-- 需要的属性 1，用户id 2，优惠卷id 3，订单id
local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]

-- 库存key，订单key
local stockKey = "seckill:stock:"..voucherId
local orderKey = "seckill:order:"..voucherId

--逻辑判断
-- 判断库存是否充足
if(tonumber(redis.call("get",stockKey)) <= 0)
then
-- 否-返回1
return 1
end
-- 判断用户是否下单
if(redis.call("sismember",orderKey,userId)==1)
then
-- 是，返回2
return 2
end
-- 扣减库存
redis.call("incrby",stockKey,-1)
-- 存入集合
redis.call("sadd",orderKey,userId)
-- 发送订单 XADD stream.order * k1 v1 k2 v2
redis.call("XADD","stream.orders","*","userId",userId,"voucherId",voucherId,"id",orderId)
return 0
-- 比较当前线程与锁的key是否相等
if(redis.call("get",KEYS[1]) == ARGV[1])
then
    return redis.call("deL",KEYS[1])
 end
 return 0;
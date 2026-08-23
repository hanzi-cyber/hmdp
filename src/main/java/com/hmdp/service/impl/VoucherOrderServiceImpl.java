package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTaskes = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true) {
                try {
                    //获取订单
                    VoucherOrder voucherOrder = orderTaskes.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }

        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 尝试获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("请勿重复下单");
            return;
        }

        try {
            //获取代理
            proxy.creatOrder(voucherOrder);
        } finally {
            // 判断
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
       //执行lua脚本判断是否符合秒杀条件
       Long userId = UserHolder.getUser().getId();

       Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
               Collections.emptyList(),
               voucherId.toString(), userId.toString());
       if(result!=0){
           return Result.fail(result==1?"库存不足":"用户已购买过");
       }
       Long orderId = redisIdWorker.nextId("order");
       //保存到阻塞队列
        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTaskes.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回订单id
        return Result.ok(orderId);


        }
        /**
         * SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
         * if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
         * return Result.fail("秒杀活动未开始");
         * }
         * if(voucher.getEndTime().isBefore(LocalDateTime.now())){
         * return Result.fail("秒杀活动已结束");
         * }
         * Integer stock = voucher.getStock();
         * if(stock<1){
         * return Result.fail("库存不足");
         * }
         * Long userId = UserHolder.getUser().getId();
         * //创建锁对象
         * //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
         * RLock lock = redissonClient.getLock("lock:order:" + userId);
         * <p>
         * // 尝试获取锁
         * boolean isLock = lock.tryLock();
         * if(!isLock){
         * return Result.fail("请勿重复下单");
         * }
         * <p>
         * try {
         * //获取代理
         * IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
         * return proxy.creatOrder(voucherId);
         * } finally {
         * // 判断
         * lock.unlock();
         * <p>
         * <p>
         * }
         */

    @Transactional
    public void creatOrder(VoucherOrder voucherOrder) {
        // 查询订单
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 判断用户是否已经购买过该优惠券
        if(count>0){
            log.error("用户已购买过该优惠券");
            return;
        }
        // 扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//cas解决库存超卖
                .update();
        if(!success){
            log.error("库存不足");
            return;
        }
        // 保存订单
        save(voucherOrder);

    }
}

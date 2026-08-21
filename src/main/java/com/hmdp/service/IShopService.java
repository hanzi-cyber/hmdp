package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result updateshop(Shop shop);

    /**
     * 事务提交后执行缓存失效（延迟双删）。
     * 不能直接在 @Transactional 方法内部删除，否则会出现删完缓存但事务未提交，
     * 被并发读回填旧值的一致性问题。
     *
     * @param shopId 店铺id
     */
    void evictShopCacheAfterCommit(Long shopId);
}

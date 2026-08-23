package com.hmdp.utils;

public interface Ilock {

    // 尝试获取锁,过期后自动释放
    boolean tryLock(long timeoutSec);


    // 释放锁
    void unlock();
}

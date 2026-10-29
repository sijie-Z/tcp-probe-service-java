package com.example.tcpprobe.service;

/** 活动任务数达到上限（背压）。 */
public class TaskCapacityException extends RuntimeException {

    public TaskCapacityException(int maxActive) {
        super("活动任务数已达上限 " + maxActive + "，请稍后重试");
    }
}

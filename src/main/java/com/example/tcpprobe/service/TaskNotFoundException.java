package com.example.tcpprobe.service;

/** 任务不存在或已过期（TTL 回收）。 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("任务不存在或已过期: " + taskId);
    }
}

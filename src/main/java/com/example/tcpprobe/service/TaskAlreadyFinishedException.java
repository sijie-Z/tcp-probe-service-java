package com.example.tcpprobe.service;

/** 任务已处于终态（且不是 cancelled），无法取消。 */
public class TaskAlreadyFinishedException extends RuntimeException {

    public TaskAlreadyFinishedException(String taskId) {
        super("任务已结束，无法取消: " + taskId);
    }
}

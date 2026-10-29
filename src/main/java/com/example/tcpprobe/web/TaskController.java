package com.example.tcpprobe.web;

import com.example.tcpprobe.domain.TaskState;
import com.example.tcpprobe.model.CreateTaskRequest;
import com.example.tcpprobe.model.TaskResponse;
import com.example.tcpprobe.model.TaskSummary;
import com.example.tcpprobe.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 探测任务接口。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskResponse> create(
            @Valid @RequestBody CreateTaskRequest request,
            @RequestParam(value = "wait", defaultValue = "false") boolean wait,
            @RequestParam(value = "wait_timeout_ms", defaultValue = "10000")
            @Min(value = 1, message = "wait_timeout_ms 取值范围 1-60000")
            @Max(value = 60000, message = "wait_timeout_ms 取值范围 1-60000") long waitTimeoutMs) {
        TaskState task = service.submit(request);
        if (wait) {
            task.awaitCompletion(waitTimeoutMs);
        }
        int statusCode = task.getStatus().isTerminal() ? 200 : 202;
        return ResponseEntity.status(statusCode).body(task.snapshot());
    }

    @GetMapping("/tasks")
    public List<TaskSummary> list(
            @RequestParam(value = "limit", defaultValue = "20")
            @Min(value = 1, message = "limit 不能小于 1")
            @Max(value = 100, message = "limit 不能大于 100") int limit) {
        return service.recent(limit);
    }

    @GetMapping("/tasks/{taskId}")
    public TaskResponse get(@PathVariable String taskId) {
        return service.get(taskId).snapshot();
    }

    @DeleteMapping("/tasks/{taskId}")
    public TaskResponse cancel(@PathVariable String taskId) {
        return service.cancel(taskId).snapshot();
    }
}

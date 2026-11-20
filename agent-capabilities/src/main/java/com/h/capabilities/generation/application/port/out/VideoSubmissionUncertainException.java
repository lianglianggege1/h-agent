package com.h.capabilities.generation.application.port.out;

/** Creation may have reached the provider. Never automatically resubmit this request. */
public class VideoSubmissionUncertainException extends RuntimeException {
    public VideoSubmissionUncertainException(Throwable cause) {
        super("提交结果尚未确认：请求可能已经创建任务，请勿自动重复提交", cause);
    }
}
